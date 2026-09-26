package org.github.lscoughlin.clarity.daemon;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.github.lscoughlin.clarity.parser.ClarityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Directory registration and event intake over {@link WatchService}.
 * Pure intake only: callers decide what a drained batch means. Index
 * output dirs, build trees, and runtime files are never registered or
 * reported.
 */
final class CorpusWatcher implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(CorpusWatcher.class);

    /** Directory names never watched, at any depth. */
    private static final Set<String> NEVER_WATCH =
            Set.of("target", "build", "node_modules", ".git");

    /** One drained batch of watch events. */
    record Drain(boolean overflow, boolean configChanged, Set<String> changedFiles) {
        static Drain empty() {
            return new Drain(false, false, Set.of());
        }
    }

    private final Path baseDir;
    private final Path configFile;
    private final Set<Path> excludedDirs;
    private final WatchService watch;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private volatile boolean closed;

    private CorpusWatcher(
            Path baseDir, Path configFile, Set<Path> excludedDirs, WatchService watch) {
        this.baseDir = baseDir;
        this.configFile = configFile;
        this.excludedDirs = excludedDirs;
        this.watch = watch;
    }

    static CorpusWatcher register(Path baseDir, ClarityConfig config) throws IOException {
        var base = baseDir.toAbsolutePath().normalize();
        var excluded = new HashSet<Path>();
        for (ClarityConfig.IndexConfig index : config.index().values()) {
            var indexPath = Path.of(index.indexPath());
            if (!indexPath.isAbsolute()) {
                indexPath = base.resolve(indexPath);
            }
            excluded.add(indexPath.normalize());
        }
        CorpusWatcher watcher =
                new CorpusWatcher(
                        base,
                        base.resolve(ConfigLoader.CONFIG_RELATIVE),
                        excluded,
                        FileSystems.getDefault().newWatchService());
        watcher.ensureRegistered();
        return watcher;
    }

    /** Registers every non-excluded directory, including newly created ones. */
    void ensureRegistered() throws IOException {
        List<Path> dirs;
        try (Stream<Path> walk = Files.walk(baseDir)) {
            dirs = walk.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList();
        }
        for (var dir : dirs) {
            registerIfNeeded(dir.toAbsolutePath().normalize());
        }
    }

    /** Drains all currently pending events, waiting up to the timeout for the first. */
    Drain drainMillis(long timeoutMillis) {
        var overflow = false;
        var configChanged = false;
        var changed = new HashSet<String>();
        try {
            WatchKey key = watch.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            while (key != null) {
                var dir = keys.get(key);
                if (dir != null) {
                    for (var event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            overflow = true;
                            continue;
                        }
                        switch (handleEvent(dir, event)) {
                            case Outcome.FileChanged(String relative) -> changed.add(relative);
                            case Outcome.ConfigChanged() -> configChanged = true;
                            case Outcome.Ignored() -> {}
                        }
                    }
                }
                key.reset();
                key = watch.poll();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            LOG.atDebug().setMessage("watch service closed").log();
        }
        return new Drain(overflow, configChanged, changed);
    }

    /** Registered directories, for tests. */
    Set<Path> registeredDirs() {
        return Set.copyOf(new HashSet<>(keys.values()));
    }

    @Override
    public void close() throws IOException {
        closed = true;
        watch.close();
    }

    private sealed interface Outcome {
        record FileChanged(String relative) implements Outcome {}

        record ConfigChanged() implements Outcome {}

        record Ignored() implements Outcome {}
    }

    private Outcome handleEvent(Path dir, WatchEvent<?> event) {
        var name = (Path) event.context();
        if (name == null) {
            return new Outcome.Ignored();
        }
        var full = dir.resolve(name).normalize();
        if (Files.isDirectory(full, LinkOption.NOFOLLOW_LINKS)) {
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                try {
                    registerIfNeeded(full);
                } catch (IOException e) {
                    LOG.atDebug()
                            .setMessage("cannot watch {}")
                            .addArgument(full)
                            .setCause(e)
                            .log();
                }
            }
            return new Outcome.Ignored();
        }
        if (full.equals(configFile)) {
            return new Outcome.ConfigChanged();
        }
        if (isExcludedFile(full)) {
            return new Outcome.Ignored();
        }
        if (!full.startsWith(baseDir)) {
            return new Outcome.Ignored();
        }
        return new Outcome.FileChanged(baseDir.relativize(full).toString());
    }

    private boolean isExcludedFile(Path full) {
        for (var excluded : excludedDirs) {
            if (full.startsWith(excluded)) {
                return true;
            }
        }
        Path relative;
        try {
            relative = baseDir.relativize(full);
        } catch (IllegalArgumentException e) {
            return true;
        }
        if (relative.getNameCount() > 1 && relative.startsWith(".clarity")) {
            return true;
        }
        return false;
    }

    private void registerIfNeeded(Path dir) throws IOException {
        if (isExcludedDir(dir) || keys.containsValue(dir)) {
            return;
        }
        WatchKey key =
                dir.register(
                        watch,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_DELETE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
        keys.put(key, dir);
    }

    private boolean isExcludedDir(Path dir) {
        if (excludedDirs.contains(dir)) {
            return true;
        }
        for (var element : baseDir.relativize(dir)) {
            if (NEVER_WATCH.contains(element.toString())) {
                return true;
            }
        }
        return false;
    }
}

package org.github.lscoughlin.clarity.daemon;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one {@link IndexService} and its {@link CorpusWatcher}. All state
 * changes happen under this object's monitor, so socket-triggered and
 * watch-triggered passes serialize and searches never observe a
 * half-reloaded service.
 */
public final class Daemon implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(Daemon.class);

    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);
    static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofMinutes(5);
    static final Duration QUIET_PERIOD = Duration.ofSeconds(1);
    static final long POLL_MILLIS = 500;

    private final Path baseDir;
    private final Duration idleTimeout;
    private final Duration scanInterval;
    private final Embedder embedder;
    private IndexService service;
    private CorpusWatcher watcher;
    private long lastActivityNanos = System.nanoTime();
    private long lastEventNanos;
    private long nextScanNanos;
    private int inFlight;
    private boolean reindexQueued;
    private boolean scanQueued;
    private volatile boolean shutdown;

    private Daemon(
            Path baseDir,
            Duration idleTimeout,
            Duration scanInterval,
            Embedder embedder,
            IndexService service,
            CorpusWatcher watcher) {
        this.baseDir = baseDir;
        this.idleTimeout = idleTimeout;
        this.scanInterval = scanInterval;
        this.embedder = embedder;
        this.service = service;
        this.watcher = watcher;
        this.nextScanNanos = System.nanoTime() + scanInterval.toNanos();
    }

    /** Opens the service, runs the initial full index, and registers the watcher. */
    public static Daemon start(Path baseDir, Duration idleTimeout) throws IOException {
        return start(baseDir, idleTimeout, DEFAULT_SCAN_INTERVAL);
    }

    static Daemon start(Path baseDir, Duration idleTimeout, Duration scanInterval)
            throws IOException {
        return start(baseDir, idleTimeout, scanInterval, null);
    }

    /**
     * Starts with an embedder for vector search; a null embedder keeps
     * text-only behavior. The embedder survives config reloads.
     */
    static Daemon start(
            Path baseDir, Duration idleTimeout, Duration scanInterval, Embedder embedder)
            throws IOException {
        IndexService service = IndexService.open(baseDir, ConfigLoader.load(baseDir), embedder);
        service.reindex();
        CorpusWatcher watcher = CorpusWatcher.register(baseDir, service.config());
        Daemon daemon = new Daemon(baseDir, idleTimeout, scanInterval, embedder, service, watcher);
        daemon.touch();
        return daemon;
    }

    public synchronized List<Hit> search(
            String indexName, String query, int topN, SearchBackend.Syntax syntax)
            throws IOException {
        return search(indexName, query, topN, syntax, null);
    }

    public synchronized List<Hit> search(
            String indexName, String query, int topN, SearchBackend.Syntax syntax, String pathPrefix)
            throws IOException {
        return search(indexName, query, topN, syntax, pathPrefix, false);
    }

    public synchronized List<Hit> search(
            String indexName,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            String pathPrefix,
            boolean fullText)
            throws IOException {
        inFlight++;
        try {
            var hits = service.search(indexName, query, topN, syntax, pathPrefix, fullText);
            // Fire-and-forget: queues a pass for the background loop to pick up
            // (within POLL_MILLIS), so callers never wait on it.
            requestReindex();
            touch();
            return hits;
        } finally {
            inFlight--;
        }
    }

    public synchronized Map<String, Integer> counts() throws IOException {
        return service.counts();
    }

    /** Document updates performed per named index; operational visibility and tests. */
    public synchronized Map<String, Long> writeCounts() throws IOException {
        return service.writeCounts();
    }

    /** Queues a pass; the next drain runs it (coalescing bursts). */
    public synchronized void requestReindex() {
        reindexQueued = true;
    }

    /**
     * Runs queued passes until none remain. Watcher-triggered passes always
     * mark activity; a scan-only pass marks activity too when it actually
     * finds changes (note: a search-triggered pass already sets
     * {@code reindexQueued}, so it always counts as activity here regardless
     * of this check).
     */
    public synchronized void drainReindex() throws IOException {
        var activity = reindexQueued;
        reindexQueued = false;
        var scan = scanQueued;
        scanQueued = false;
        if (activity || scan) {
            var stats = service.reindex();
            if (activity || hasChanges(stats)) {
                touch();
            }
        }
    }

    private static boolean hasChanges(Map<String, ReindexStats> stats) {
        return stats.values().stream()
                .anyMatch(s -> s.added() > 0 || s.changed() > 0 || s.removed() > 0);
    }

    /** Reindexes one named index, or every index when {@code indexOrNull} is null. */
    public synchronized Map<String, ReindexStats> reindexNow(String indexOrNull)
            throws IOException {
        inFlight++;
        try {
            var stats =
                    indexOrNull == null
                            ? service.reindex()
                            : Map.of(indexOrNull, service.reindexIndex(indexOrNull));
            touch();
            return stats;
        } finally {
            inFlight--;
        }
    }

    /** Reloads config, service, and watcher registration. Queues a pass. */
    public synchronized void reload() throws IOException {
        service.close();
        watcher.close();
        service = IndexService.open(baseDir, ConfigLoader.load(baseDir), embedder);
        watcher = CorpusWatcher.register(baseDir, service.config());
        reindexQueued = true;
        touch();
        LOG.atInfo().setMessage("reloaded configuration").log();
    }

    /**
     * Applies one drained batch: config reload, debounce accounting, then
     * any due pass. {@code nowNanos} is a parameter for tests.
     */
    synchronized void tick(CorpusWatcher.Drain drain, long nowNanos) throws IOException {
        if (drain.configChanged()) {
            reload();
        }
        if (!drain.changedFiles().isEmpty()) {
            lastEventNanos = nowNanos;
        }
        if (lastEventNanos > 0 && nowNanos - lastEventNanos >= QUIET_PERIOD.toNanos()) {
            lastEventNanos = 0;
            reindexQueued = true;
        }
        if (nowNanos >= nextScanNanos) {
            nextScanNanos = nowNanos + scanInterval.toNanos();
            scanQueued = true;
        }
        drainReindex();
    }

    synchronized boolean idleExpired(long nowNanos) {
        return inFlight == 0 && nowNanos - lastActivityNanos >= idleTimeout.toNanos();
    }

    /** Blocks serving watch passes until shutdown (idle timeout or hook). */
    public void runLoop() {
        while (!shutdown) {
            CorpusWatcher.Drain drain = watcher.drainMillis(POLL_MILLIS);
            var now = System.nanoTime();
            synchronized (this) {
                if (shutdown) {
                    return;
                }
                try {
                    tick(drain, now);
                } catch (IOException e) {
                    LOG.atWarn()
                            .setMessage("reindex pass failed, will retry on next trigger")
                            .setCause(e)
                            .log();
                }
                if (idleExpired(now)) {
                    LOG.atInfo().setMessage("idle timeout reached, shutting down").log();
                    shutdown();
                    return;
                }
            }
        }
    }

    /** Idempotent clean shutdown from any thread. */
    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        try {
            watcher.close();
        } catch (IOException e) {
            LOG.atDebug().setMessage("watcher close failed").setCause(e).log();
        }
        try {
            service.close();
        } catch (IOException e) {
            LOG.atDebug().setMessage("service close failed").setCause(e).log();
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private void touch() {
        lastActivityNanos = System.nanoTime();
    }
}

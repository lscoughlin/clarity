package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.github.lscoughlin.clarity.daemon.CorpusWatcher.Drain;
import org.github.lscoughlin.clarity.parser.ClarityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorpusWatcherTest {

    private static final String CONFIG =
            """
            index:
              docs:
                index_path: index/docs
                markdown:
                  - doc/**/*.md
            """;

    @Test
    void excludesIndexBuildAndRuntimeDirs(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.createDirectories(base.resolve("target"));
        Files.createDirectories(base.resolve("index/docs"));

        try (CorpusWatcher watcher =
                CorpusWatcher.register(base, ClarityConfig.parse(CONFIG))) {
            Set<Path> dirs = watcher.registeredDirs();
            assertTrue(dirs.stream().anyMatch(d -> d.endsWith(".clarity")), "watches config dir");
            assertTrue(dirs.stream().noneMatch(d -> d.endsWith("target")), "skips build output");
            assertTrue(
                    dirs.stream().noneMatch(d -> d.endsWith("docs") && d.toString().contains("index")),
                    "skips own index output");
        }
    }

    @Test
    void reportsFileAndConfigEvents(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(base.resolve(".clarity/config.yaml"), CONFIG);

        try (CorpusWatcher watcher =
                CorpusWatcher.register(base, ClarityConfig.parse(CONFIG))) {
            Files.writeString(base.resolve("doc/new.md"), "# New\nHello watcher.\n");
            Drain fileDrain = awaitChange(watcher, false);
            assertTrue(fileDrain.changedFiles().contains("doc/new.md"), fileDrain.toString());
            assertFalse(fileDrain.configChanged());

            Files.writeString(base.resolve(".clarity/config.yaml"), CONFIG + "# touch\n");
            Drain configDrain = awaitChange(watcher, true);
            assertTrue(configDrain.configChanged(), configDrain.toString());
        }
    }

    @Test
    void ignoresSocketAndLogNoise(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(base.resolve(".clarity/config.yaml"), CONFIG);

        try (CorpusWatcher watcher =
                CorpusWatcher.register(base, ClarityConfig.parse(CONFIG))) {
            Files.writeString(base.resolve(".clarity/clarity.sock"), "junk");
            Files.writeString(base.resolve(".clarity/daemon.log"), "junk\n");
            // Sentinel proves events flow; accumulate every batch until it
            // arrives, then assert the noise never appeared in any of them.
            Files.writeString(base.resolve("doc/sentinel.md"), "# Sentinel\n");
            Set<String> allChanged = new HashSet<>();
            boolean configChanged = false;
            for (int i = 0; i < 100; i++) {
                Drain drain = watcher.drainMillis(200);
                allChanged.addAll(drain.changedFiles());
                configChanged |= drain.configChanged();
                if (allChanged.contains("doc/sentinel.md")) {
                    break;
                }
            }
            assertTrue(allChanged.contains("doc/sentinel.md"), allChanged.toString());
            assertTrue(
                    allChanged.stream().noneMatch(p -> p.contains(".clarity/")),
                    allChanged.toString());
            assertFalse(configChanged);
        }
    }

    private static Drain awaitChange(CorpusWatcher watcher, boolean wantConfig) {
        for (int i = 0; i < 100; i++) {
            Drain drain = watcher.drainMillis(200);
            if (!drain.changedFiles().isEmpty() || (wantConfig && drain.configChanged())) {
                if (!wantConfig && drain.changedFiles().isEmpty()) {
                    continue;
                }
                return drain;
            }
        }
        throw new AssertionError("no watch events arrived within 20s");
    }
}

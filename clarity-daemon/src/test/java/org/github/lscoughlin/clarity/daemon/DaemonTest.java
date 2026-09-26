package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.github.lscoughlin.clarity.daemon.CorpusWatcher.Drain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonTest {

    @Test
    void idleTimeoutShutsDownLoop(@TempDir Path base) throws Exception {
        writeCorpus(base);
        Daemon daemon = Daemon.start(base, Duration.ofMillis(300));
        Thread loop = Thread.ofVirtual().start(daemon::runLoop);
        loop.join(15000);
        assertFalse(loop.isAlive(), "run loop should have exited on idle timeout");
        daemon.shutdown();
    }

    @Test
    void concurrentPassesStayComplete(@TempDir Path base) throws Exception {
        writeCorpus(base);
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10))) {
            var pool = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    futures.add(
                            pool.submit(
                                    () -> {
                                        daemon.requestReindex();
                                        daemon.drainReindex();
                                        return null;
                                    }));
                }
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(Map.of("docs", 1), daemon.counts());
            assertEquals(
                    1,
                    daemon.search("docs", "reindex", 10, SearchBackend.Syntax.TEXT).size());
        }
    }

    @Test
    void debounceCoalescesBursts(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10))) {
            assertEquals(Map.of("docs", 1L), daemon.writeCounts());
            Files.writeString(base.resolve("doc/guide.md"), "# Guide\nEdited body here.\n");

            long burst = System.nanoTime();
            // A burst of event batches inside the quiet period: no pass yet.
            daemon.tick(new Drain(false, false, Set.of("doc/guide.md")), burst);
            daemon.tick(new Drain(false, false, Set.of("doc/guide.md")), burst + 100_000_000L);
            assertEquals(Map.of("docs", 1L), daemon.writeCounts());

            // Quiet elapsed: exactly one pass, one file rewritten.
            daemon.tick(Drain.empty(), burst + 3_000_000_000L);
            assertEquals(Map.of("docs", 2L), daemon.writeCounts());
            assertEquals(
                    1, daemon.search("docs", "Edited", 10, SearchBackend.Syntax.TEXT).size());
        }
    }

    private static void writeCorpus(Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/guide.md"), "# Guide\nHow to reindex the corpus.\n");
    }
}

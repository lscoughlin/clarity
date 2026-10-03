package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void reindexNowRunsImmediatelyAndReturnsStats(@TempDir Path base) throws Exception {
        writeCorpus(base);
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10))) {
            // Daemon.start() already ran the initial full pass; this call
            // should be idempotent (nothing changed since) but return stats.
            Map<String, ReindexStats> stats = daemon.reindexNow(null);
            assertEquals(1, stats.get("docs").docs());
            assertEquals(0, stats.get("docs").added());
            assertEquals(0, stats.get("docs").changed());
        }
    }

    @Test
    void reindexNowSingleIndexValidatesName(@TempDir Path base) throws Exception {
        writeCorpus(base);
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10))) {
            assertThrows(IllegalArgumentException.class, () -> daemon.reindexNow("nope"));
        }
    }

    @Test
    void reindexNowDoesNotClearQueuedWatcherPass(@TempDir Path base) throws Exception {
        writeCorpus(base);
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10))) {
            daemon.requestReindex();
            daemon.reindexNow("docs");

            Files.writeString(base.resolve("doc/guide.md"), "# Guide\nEdited body here.\n");
            daemon.drainReindex();
            assertEquals(Map.of("docs", 2L), daemon.writeCounts());
        }
    }

    @Test
    void searchTriggersBackgroundReindex(@TempDir Path base) throws Exception {
        writeCorpus(base);
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10))) {
            assertEquals(Map.of("docs", 1L), daemon.writeCounts());
            Files.writeString(base.resolve("doc/guide.md"), "# Guide\nEdited body here.\n");

            daemon.search("docs", "reindex", 10, SearchBackend.Syntax.TEXT);
            daemon.drainReindex();

            assertEquals(Map.of("docs", 2L), daemon.writeCounts());
            assertEquals(
                    1, daemon.search("docs", "Edited", 10, SearchBackend.Syntax.TEXT).size());
        }
    }

    /**
     * Regression test for the production stall: search and reindex used to
     * share one monitor, so a slow embedding pass (real observed cause: an
     * ONNX call stuck for minutes under host CPU pressure) blocked every
     * concurrent search for its full duration. A blocking test embedder
     * stands in for "reindex is stuck deep inside a slow call" — search must
     * return promptly regardless.
     */
    @Test
    void searchDoesNotBlockOnInFlightReindex(@TempDir Path base) throws Exception {
        writeCorpus(base);
        // A file untouched by the edit below: the edited file's old chunks
        // are deleted before the blocking embed call even runs (and its new
        // chunks aren't added until after), so it's unsearchable in either
        // state while blocked — this one stays live the whole time and is
        // what proves search is actually serving results, not just fast.
        Files.writeString(base.resolve("doc/stable.md"), "# Stable\nAlways findable content.\n");
        var releaseEmbed = new CountDownLatch(1);
        var blockNextEmbed = new AtomicBoolean(false);
        Embedder blockingEmbedder =
                new Embedder() {
                    @Override
                    public String modelId() {
                        return "test-blocking";
                    }

                    @Override
                    public int dimensions() {
                        return 2;
                    }

                    @Override
                    public float[] embed(String text) {
                        if (blockNextEmbed.get()) {
                            try {
                                assertTrue(
                                        releaseEmbed.await(10, TimeUnit.SECONDS),
                                        "test bug: nothing released the embed latch");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return new float[] {1f, 0f};
                    }
                };

        try (Daemon daemon =
                Daemon.start(base, Duration.ofMinutes(10), Duration.ofMinutes(5), blockingEmbedder)) {
            Files.writeString(base.resolve("doc/guide.md"), "# Guide\nEdited body here.\n");
            blockNextEmbed.set(true);

            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                Future<?> reindexing =
                        pool.submit(
                                () -> {
                                    daemon.reindexNow(null);
                                    return null;
                                });
                // Give the background pass a beat to actually enter the
                // blocking embed call before racing the search against it.
                Thread.sleep(200);

                long startNanos = System.nanoTime();
                List<Hit> hits =
                        daemon.search("docs", "findable", 10, SearchBackend.Syntax.TEXT);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

                assertEquals(1, hits.size());
                assertTrue(
                        elapsedMs < 2000,
                        "search must not wait on the in-flight reindex; took " + elapsedMs + "ms");

                releaseEmbed.countDown();
                reindexing.get(10, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void scanTriggeredReindexWithChangesResetsIdleTimeout(@TempDir Path base) throws Exception {
        writeCorpus(base);
        try (Daemon daemon = Daemon.start(base, Duration.ofMillis(50), Duration.ofMillis(1))) {
            Thread.sleep(80);
            assertTrue(
                    daemon.idleExpired(System.nanoTime()),
                    "sanity: idle window elapsed with no activity yet");

            Files.writeString(base.resolve("doc/guide.md"), "# Guide\nEdited body here.\n");
            // Empty drain (no watcher events): only the scan flag fires, but it
            // finds a real change and must still reset the idle clock.
            daemon.tick(new Drain(false, false, Set.of()), System.nanoTime());

            assertFalse(
                    daemon.idleExpired(System.nanoTime()),
                    "scan pass with real changes must reset idle timeout");
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

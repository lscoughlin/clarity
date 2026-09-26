package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SocketRoundTripTest {

    @Test
    void searchReindexAndHealth(@TempDir Path base) throws IOException {
        writeCorpus(base);
        Path socket = SocketTestHelper.freshSocket("roundtrip");
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10));
                DaemonServer server = DaemonServer.bind(socket)) {
            server.serve(daemon);
            DaemonClient.Target target = DaemonClient.target(base, socket);

            assertEquals(Map.of("docs", 1), DaemonClient.health(target, SocketTestHelper.noSpawn()));
            assertEquals(Map.of("docs", 1), DaemonClient.reindex(target, SocketTestHelper.noSpawn()));

            List<Hit> hits =
                    DaemonClient.search(
                            target,
                            SocketTestHelper.noSpawn(),
                            "docs",
                            "reindex",
                            10,
                            SearchBackend.Syntax.TEXT);
            assertTrue(hits.stream().anyMatch(h -> h.sourcePath().equals("doc/guide.md")));

            assertThrows(
                    IOException.class,
                    () ->
                            DaemonClient.search(
                                    target,
                                    SocketTestHelper.noSpawn(),
                                    "nope",
                                    "x",
                                    1,
                                    SearchBackend.Syntax.TEXT));
        }
    }

    @Test
    void reindexWithStatsReturnsPerFileCounts(@TempDir Path base) throws IOException {
        writeCorpus(base);
        Path socket = SocketTestHelper.freshSocket("reindexstats");
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10));
                DaemonServer server = DaemonServer.bind(socket)) {
            server.serve(daemon);
            DaemonClient.Target target = DaemonClient.target(base, socket);

            Map<String, ReindexStats> stats =
                    DaemonClient.reindexWithStats(target, SocketTestHelper.noSpawn(), null);
            assertEquals(1, stats.get("docs").docs());
            assertEquals(0, stats.get("docs").added());
            assertEquals(0, stats.get("docs").changed());
            assertEquals(0, stats.get("docs").removed());

            Files.writeString(base.resolve("doc/guide.md"), "# Guide\nEdited body here.\n");
            Map<String, ReindexStats> afterEdit =
                    DaemonClient.reindexWithStats(target, SocketTestHelper.noSpawn(), "docs");
            assertEquals(1, afterEdit.get("docs").changed());
            assertEquals(0, afterEdit.get("docs").added());
        }
    }

    @Test
    void reindexWithStatsSingleIndexUnknownNameIsError(@TempDir Path base) throws IOException {
        writeCorpus(base);
        Path socket = SocketTestHelper.freshSocket("reindexstatsunknown");
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10));
                DaemonServer server = DaemonServer.bind(socket)) {
            server.serve(daemon);
            DaemonClient.Target target = DaemonClient.target(base, socket);

            assertThrows(
                    IOException.class,
                    () ->
                            DaemonClient.reindexWithStats(
                                    target, SocketTestHelper.noSpawn(), "nope"));
        }
    }

    @Test
    void truncatedFrameClosesWithoutHang(@TempDir Path base) throws IOException {
        writeCorpus(base);
        Path socket = SocketTestHelper.freshSocket("truncated");
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10));
                DaemonServer server = DaemonServer.bind(socket)) {
            server.serve(daemon);
            // Two bytes of a four-byte header, then EOF: server must close, not hang.
            byte[] reply = SocketTestHelper.sendRaw(socket, new byte[] {0, 0});
            assertEquals(0, reply.length);
            // Server still serves afterwards.
            DaemonClient.health(
                    DaemonClient.target(base, socket), SocketTestHelper.noSpawn());
        }
    }

    @Test
    void searchWithNoSyntaxFieldDefaultsToHybrid(@TempDir Path base) throws IOException {
        writeCorpus(base);
        Path socket = SocketTestHelper.freshSocket("nosyntax");
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10));
                DaemonServer server = DaemonServer.bind(socket)) {
            server.serve(daemon);
            // No "syntax" field at all: on this no-embedder daemon, HYBRID
            // degrades to BM25-only, so a real match still comes back
            // rather than an error — this is the only way to observe the
            // socket layer's default (a no-embedder daemon can't otherwise
            // distinguish HYBRID from TEXT).
            byte[] body =
                    "{\"op\":\"search\",\"index\":\"docs\",\"query\":\"reindex\",\"topN\":10}"
                            .getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[4 + body.length];
            frame[3] = (byte) body.length;
            System.arraycopy(body, 0, frame, 4, body.length);
            byte[] reply = SocketTestHelper.sendRaw(socket, frame);
            String text = new String(reply, 4, reply.length - 4, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"ok\":true"), text);
            assertTrue(text.contains("doc/guide.md"), text);
        }
    }

    @Test
    void unknownOpIsAnErrorEnvelope(@TempDir Path base) throws IOException {
        writeCorpus(base);
        Path socket = SocketTestHelper.freshSocket("unknownop");
        try (Daemon daemon = Daemon.start(base, Duration.ofMinutes(10));
                DaemonServer server = DaemonServer.bind(socket)) {
            server.serve(daemon);
            byte[] body = "{\"op\":\"frobnicate\"}".getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[4 + body.length];
            frame[3] = (byte) body.length;
            System.arraycopy(body, 0, frame, 4, body.length);
            byte[] reply = SocketTestHelper.sendRaw(socket, frame);
            String text = new String(reply, 4, reply.length - 4, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"ok\":false"), text);
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

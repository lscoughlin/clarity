package org.github.lscoughlin.clarity.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.github.lscoughlin.clarity.daemon.Daemon;
import org.github.lscoughlin.clarity.daemon.DaemonServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ClarityMainTest {

    /** In-process daemon + server so CLI tests exercise the thin-client path. */
    private record RunningDaemon(Daemon daemon, DaemonServer server, Path socket)
            implements AutoCloseable {
        private static final AtomicLong IDS = new AtomicLong();

        static RunningDaemon start(Path base, String tag) throws IOException {
            Path dir = Path.of("/tmp/clarity-cli-socktests");
            Files.createDirectories(dir);
            Path socket = dir.resolve(tag + "-" + IDS.incrementAndGet() + ".sock");
            Files.deleteIfExists(socket);
            Daemon daemon = Daemon.start(base, Duration.ofMinutes(10));
            DaemonServer server = DaemonServer.bind(socket);
            server.serve(daemon);
            return new RunningDaemon(daemon, server, socket);
        }

        @Override
        public void close() throws Exception {
            server.close();
            daemon.shutdown();
            Files.deleteIfExists(socket);
        }
    }

    @Test
    void helpListsSubcommands() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new ClarityMain());
        cmd.setOut(new PrintWriter(out));

        assertEquals(0, cmd.execute("--help"));
        assertTrue(out.toString().contains("index"));
        assertTrue(out.toString().contains("query"));
        assertTrue(out.toString().contains("mcp"));
        assertTrue(out.toString().contains("daemon"));
    }

    @Test
    void indexThenQueryRoundTrip(@TempDir Path base) throws Exception {
        writeCorpus(base, "# Guide\nHow to reindex the corpus.\n");
        try (RunningDaemon daemon = RunningDaemon.start(base, "roundtrip")) {
            StringWriter out = new StringWriter();
            CommandLine index = new CommandLine(new ClarityMain());
            index.setOut(new PrintWriter(out));
            assertEquals(
                    0,
                    index.execute(
                            "index",
                            "--dir",
                            base.toString(),
                            "--socket",
                            daemon.socket().toString(),
                            "--lock",
                            daemon.socket()
                                    .getParent()
                                    .resolve("roundtrip-daemon.lock")
                                    .toString()));
            assertTrue(out.toString().contains("docs=1"), out.toString());

            out.getBuffer().setLength(0);
            CommandLine query = new CommandLine(new ClarityMain());
            query.setOut(new PrintWriter(out));
            assertEquals(
                    0,
                    query.execute(
                            "query",
                            "--dir",
                            base.toString(),
                            "--socket",
                            daemon.socket().toString(),
                            "docs",
                            "reindex"));
            assertTrue(out.toString().contains("doc/guide.md"), out.toString());
        }
    }

    @Test
    void rawSyntaxFlagAndBreadcrumb(@TempDir Path base) throws Exception {
        writeCorpus(base, "# Setup\nInstall it.\n\n## Linux\nUse apt.\n");
        try (RunningDaemon daemon = RunningDaemon.start(base, "raw")) {
            CommandLine index = new CommandLine(new ClarityMain());
            index.setOut(new PrintWriter(new StringWriter()));
            assertEquals(
                    0,
                    index.execute(
                            "index",
                            "--dir",
                            base.toString(),
                            "--socket",
                            daemon.socket().toString()));

            StringWriter out = new StringWriter();
            CommandLine query = new CommandLine(new ClarityMain());
            query.setOut(new PrintWriter(out));
            assertEquals(
                    0,
                    query.execute(
                            "query",
                            "--dir",
                            base.toString(),
                            "--socket",
                            daemon.socket().toString(),
                            "--query-syntax",
                            "raw",
                            "docs",
                            "\"Use apt\""));
            assertTrue(out.toString().contains("doc/guide.md:4 [Setup / Linux]"), out.toString());
        }
    }

    @Test
    void pathPrefixFlagScopesResults(@TempDir Path base) throws Exception {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc/deploy"));
        Files.createDirectories(base.resolve("doc/control"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                """
                index:
                  docs:
                    index_path: index/docs
                    markdown:
                      - doc/**/*.md
                """);
        Files.writeString(
                base.resolve("doc/deploy/setup.md"), "# Setup\nDeploy the widget here.\n");
        Files.writeString(
                base.resolve("doc/control/setup.md"), "# Setup\nControl the widget here.\n");
        try (RunningDaemon daemon = RunningDaemon.start(base, "pathprefix")) {
            CommandLine index = new CommandLine(new ClarityMain());
            index.setOut(new PrintWriter(new StringWriter()));
            assertEquals(
                    0,
                    index.execute(
                            "index",
                            "--dir",
                            base.toString(),
                            "--socket",
                            daemon.socket().toString()));

            StringWriter out = new StringWriter();
            CommandLine query = new CommandLine(new ClarityMain());
            query.setOut(new PrintWriter(out));
            assertEquals(
                    0,
                    query.execute(
                            "query",
                            "--dir",
                            base.toString(),
                            "--socket",
                            daemon.socket().toString(),
                            "--path-prefix",
                            "doc/deploy",
                            "docs",
                            "widget"));
            assertTrue(out.toString().contains("doc/deploy/setup.md"), out.toString());
            assertTrue(!out.toString().contains("doc/control/setup.md"), out.toString());
        }
    }

    @Test
    void unknownIndexIsExitOne(@TempDir Path base) throws Exception {
        Files.createDirectories(base.resolve(".clarity"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n");
        try (RunningDaemon daemon = RunningDaemon.start(base, "unknown")) {
            StringWriter err = new StringWriter();
            CommandLine cmd = new CommandLine(new ClarityMain());
            cmd.setErr(new PrintWriter(err));

            assertEquals(
                    1,
                    cmd.execute(
                            "query",
                            "--dir",
                            base.toString(),
                            "--socket",
                            daemon.socket().toString(),
                            "nope",
                            "x"));
            assertTrue(err.toString().contains("unknown index"), err.toString());
        }
    }

    private static void writeCorpus(Path base, String guide) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                """
                index:
                  docs:
                    index_path: index/docs
                    markdown:
                      - doc/**/*.md
                """);
        Files.writeString(base.resolve("doc/guide.md"), guide);
    }
}

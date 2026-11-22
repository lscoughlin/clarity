package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Runs the real daemon entry point: option parsing, idle stop and SIGTERM stop. */
class DaemonMainLifecycleTest {

    @Test
    void badOptionValueFailsWithMessage(@TempDir Path base) throws Exception {
        var result = run(base, null, name -> null, "--idle-timeout=soon");
        assertEquals(2, result.exit());
        assertTrue(result.err().contains("invalid idle timeout 'soon'"), result.err());
    }

    @Test
    void emptyOptionValueFails(@TempDir Path base) throws Exception {
        assertEquals(2, run(base, null, name -> null, "--idle-timeout=").exit());
    }

    @Test
    void badEnvironmentValueFailsWithMessage(@TempDir Path base) throws Exception {
        UnaryOperator<String> env = name -> IdleTimeout.ENV_VAR.equals(name) ? "-3" : null;
        var result = run(base, null, env);
        assertEquals(2, result.exit());
        assertTrue(result.err().contains(IdleTimeout.ENV_VAR), result.err());
    }

    @Test
    void shortTimeoutStopsAfterAboutOneSecond(@TempDir Path base) throws Exception {
        writeCorpus(base);
        var socket = SocketTestHelper.freshSocket("idle1");
        var main = newMain(name -> null);
        var exit = start(main, base, socket, "--idle-timeout=1s");
        awaitSocket(socket);
        var upAt = System.nanoTime();
        assertEquals(0, exit.get(10, TimeUnit.SECONDS));
        var idleMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - upAt);
        assertTrue(idleMillis < 4000, "stopped too late: " + idleMillis + " ms");
        assertFalse(Files.exists(socket), "socket file must be removed");
    }

    @Test
    void zeroTimeoutStaysUpAndOptionBeatsEnvironment(@TempDir Path base) throws Exception {
        writeCorpus(base);
        var socket = SocketTestHelper.freshSocket("idle0");
        // The variable says 1s. The option 0 must win.
        UnaryOperator<String> env = name -> IdleTimeout.ENV_VAR.equals(name) ? "1s" : null;
        var exit = start(newMain(env), base, socket, "--idle-timeout=0");
        awaitSocket(socket);
        Thread.sleep(3000);
        assertFalse(exit.isDone(), "daemon with timeout 0 must stay up");
        assertTrue(Files.exists(socket));
        // Stop it as a supervisor would. This also proves the in-process SIGTERM path.
        sun.misc.Signal.raise(new sun.misc.Signal("TERM"));
        assertEquals(0, exit.get(10, TimeUnit.SECONDS));
        assertFalse(Files.exists(socket), "socket file must be removed");
    }

    @Test
    void sigtermStopsRealProcessCleanlyWithExitZero(@TempDir Path base) throws Exception {
        writeCorpus(base);
        var socket = SocketTestHelper.freshSocket("term");
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process =
                new ProcessBuilder(
                                List.of(
                                        java,
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        DaemonMainTestLauncher.class.getName(),
                                        "--dir",
                                        base.toString(),
                                        "--socket",
                                        socket.toString(),
                                        "--idle-timeout=0"))
                        .redirectErrorStream(true)
                        .redirectOutput(base.resolve("daemon.log").toFile())
                        .start();
        try {
            awaitSocket(socket);
            process.destroy(); // SIGTERM
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "daemon did not stop on SIGTERM");
            assertEquals(0, process.exitValue(), Files.readString(base.resolve("daemon.log")));
            assertFalse(Files.exists(socket), "socket file must be removed");
        } finally {
            process.destroyForcibly();
            Files.deleteIfExists(socket);
        }
    }

    private record Result(int exit, String err) {}

    private static DaemonMain newMain(UnaryOperator<String> env) {
        var main = new DaemonMain();
        main.env = env;
        main.embedderLoader = () -> null;
        return main;
    }

    private static Result run(Path base, Path socket, UnaryOperator<String> env, String... args) {
        var err = new StringWriter();
        var line = new CommandLine(newMain(env));
        line.setErr(new PrintWriter(err, true));
        var all = new java.util.ArrayList<>(List.of("--dir", base.toString()));
        all.addAll(List.of(args));
        var exit = line.execute(all.toArray(String[]::new));
        return new Result(exit, err.toString());
    }

    private static CompletableFuture<Integer> start(
            DaemonMain main, Path base, Path socket, String... extra) {
        var args = new java.util.ArrayList<>(List.of("--dir", base.toString(), "--socket", socket.toString()));
        args.addAll(List.of(extra));
        return CompletableFuture.supplyAsync(
                () -> new CommandLine(main).execute(args.toArray(String[]::new)));
    }

    private static void awaitSocket(Path socket) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (Files.exists(socket)) {
                try {
                    DaemonClient.health(DaemonClient.target(socket.getParent(), socket), t -> {});
                    return;
                } catch (IOException e) {
                    // Not serving yet: the initial index is still running.
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("daemon did not come up at " + socket);
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

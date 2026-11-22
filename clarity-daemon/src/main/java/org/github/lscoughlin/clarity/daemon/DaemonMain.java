package org.github.lscoughlin.clarity.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Entry point: binds the socket, runs the initial index, serves watch
 * passes and client requests until SIGINT, SIGTERM or the idle timeout,
 * then shuts down cleanly (writers closed, socket unlinked, exit 0).
 */
@Command(
        name = "daemon",
        mixinStandardHelpOptions = true,
        description = "Index a local documentation corpus and serve queries over a Unix socket.")
public final class DaemonMain implements Callable<Integer> {
    private static final Logger LOG = LoggerFactory.getLogger(DaemonMain.class);

    @Spec CommandSpec spec;

    @Option(names = "--dir", defaultValue = ".", description = "Project directory to serve.")
    Path dir;

    @Option(
            names = "--socket",
            description = "Daemon socket path (default: <dir>/.clarity/clarity.sock).")
    Path socketOverride;

    @Option(
            names = "--idle-timeout",
            paramLabel = "<duration>",
            description =
                    "Stop after this much time without queries. Use seconds, or a number"
                            + " with suffix s, m or h (for example 30m). 0 means never stop"
                            + " when idle. Overrides CLARITY_DAEMON_IDLE_TIMEOUT."
                            + " Default: 10m.")
    String idleTimeoutOption;

    /** Environment lookup; tests replace it. */
    UnaryOperator<String> env = System::getenv;

    /** Embedder loader; tests replace it to avoid the model download. */
    Supplier<Embedder> embedderLoader = () -> OnnxEmbedder.tryLoad().orElse(null);

    public static void main(String[] args) {
        System.exit(new CommandLine(new DaemonMain()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Duration idleTimeout;
        try {
            idleTimeout =
                    IdleTimeout.resolve(idleTimeoutOption, env, Daemon.DEFAULT_IDLE_TIMEOUT);
        } catch (IllegalArgumentException e) {
            spec.commandLine().getErr().println("clarity daemon: " + e.getMessage());
            return 2;
        }
        var baseDir = dir.toAbsolutePath().normalize();
        var socketPath =
                socketOverride != null
                        ? socketOverride.toAbsolutePath().normalize()
                        : DaemonClient.socketFor(baseDir);

        DaemonServer server = DaemonServer.bind(socketPath);
        // Local embeddings are best-effort: without the model the
        // daemon still serves text search.
        Embedder embedder = embedderLoader.get();
        Daemon daemon = Daemon.start(baseDir, idleTimeout, Daemon.DEFAULT_SCAN_INTERVAL, embedder);
        server.serve(daemon);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    daemon.shutdown();
                                    closeQuietly(server);
                                    unlinkQuietly(socketPath);
                                },
                                "clarity-shutdown"));
        installTermHandler(server, daemon);
        try {
            daemon.runLoop();
        } finally {
            closeQuietly(server);
            daemon.shutdown();
            unlinkQuietly(socketPath);
        }
        LOG.atInfo().setMessage("daemon stopped").log();
        return 0;
    }

    /**
     * Turns SIGTERM into a normal stop. Without a handler the JVM exits
     * with status 143. The handler stops new connections, then waits for
     * running queries (the daemon takes its write lock). The run loop then
     * returns and {@link #call} exits with 0.
     */
    private static void installTermHandler(DaemonServer server, Daemon daemon) {
        try {
            sun.misc.Signal.handle(
                    new sun.misc.Signal("TERM"),
                    signal -> {
                        LOG.atInfo().setMessage("SIGTERM received, shutting down").log();
                        closeQuietly(server);
                        daemon.shutdown();
                    });
        } catch (IllegalArgumentException | LinkageError e) {
            LOG.atDebug().setMessage("SIGTERM handler unavailable").setCause(e).log();
        }
    }

    private static void closeQuietly(DaemonServer server) {
        try {
            server.close();
        } catch (IOException e) {
            LOG.atDebug().setMessage("server close failed").setCause(e).log();
        }
    }

    private static void unlinkQuietly(Path socketPath) {
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException e) {
            LOG.atDebug().setMessage("socket unlink failed").setCause(e).log();
        }
    }
}

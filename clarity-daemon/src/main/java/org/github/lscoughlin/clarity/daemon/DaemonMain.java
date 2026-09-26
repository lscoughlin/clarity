package org.github.lscoughlin.clarity.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Entry point: binds the socket, runs the initial index, serves watch
 * passes and client requests until SIGINT or the idle timeout, then
 * shuts down cleanly (writers closed, socket unlinked).
 */
@Command(
        name = "daemon",
        mixinStandardHelpOptions = true,
        description = "Index a local documentation corpus and serve queries over a Unix socket.")
public final class DaemonMain implements Callable<Integer> {
    private static final Logger LOG = LoggerFactory.getLogger(DaemonMain.class);

    @Option(names = "--dir", defaultValue = ".", description = "Project directory to serve.")
    Path dir;

    @Option(
            names = "--socket",
            description = "Daemon socket path (default: <dir>/.clarity/clarity.sock).")
    Path socketOverride;

    public static void main(String[] args) {
        System.exit(new CommandLine(new DaemonMain()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        var baseDir = dir.toAbsolutePath().normalize();
        var socketPath =
                socketOverride != null
                        ? socketOverride.toAbsolutePath().normalize()
                        : DaemonClient.socketFor(baseDir);

        DaemonServer server = DaemonServer.bind(socketPath);
        // Local embeddings are best-effort: without the model the
        // daemon still serves text search.
        Embedder embedder = OnnxEmbedder.tryLoad().orElse(null);
        Daemon daemon =
                Daemon.start(baseDir, Daemon.DEFAULT_IDLE_TIMEOUT, Daemon.DEFAULT_SCAN_INTERVAL,
                        embedder);
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

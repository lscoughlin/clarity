package org.github.lscoughlin.clarity.daemon;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.ControlRequest;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.SearchRequest;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.SearchResponse;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.StatusResponse;
import org.slf4j.Logger;
import tools.jackson.databind.JsonNode;
import org.slf4j.LoggerFactory;

/**
 * Serves one daemon over its Unix socket: one virtual thread per
 * connection, length-prefixed JSON frames, {@code search} /
 * {@code reindex} / {@code health} ops. Bind first, serve after the
 * initial index, so early connections observe fresh results.
 */
public final class DaemonServer implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(DaemonServer.class);

    private final Path socketPath;
    private final ServerSocketChannel server;
    private volatile boolean running = true;
    private Thread acceptThread;

    private DaemonServer(Path socketPath, ServerSocketChannel server) {
        this.socketPath = socketPath;
        this.server = server;
    }

    public static DaemonServer bind(Path socketPath) throws IOException {
        var abs = socketPath.toAbsolutePath().normalize();
        if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "socket already present at " + abs
                            + " (daemon running, or stale file from an unclean exit)");
        }
        Files.createDirectories(abs.getParent());
        var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            server.bind(UnixDomainSocketAddress.of(abs));
        } catch (IOException e) {
            server.close();
            throw e;
        }
        try {
            Files.setPosixFilePermissions(abs, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            LOG.atDebug()
                    .setMessage("owner-only socket permissions unsupported here")
                    .setCause(e)
                    .log();
        }
        return new DaemonServer(abs, server);
    }

    /** Starts the accept loop on a daemon thread; returns immediately. */
    public void serve(Daemon daemon) {
        acceptThread =
                Thread.ofPlatform()
                        .daemon(true)
                        .name("clarity-accept")
                        .start(() -> acceptLoop(daemon));
    }

    @Override
    public void close() throws IOException {
        running = false;
        server.close();
    }

    private void acceptLoop(Daemon daemon) {
        while (running) {
            try {
                var connection = server.accept();
                Thread.ofVirtual().start(() -> handle(daemon, connection));
            } catch (AsynchronousCloseException e) {
                return;
            } catch (IOException e) {
                if (running) {
                    LOG.atWarn().setMessage("accept failed").setCause(e).log();
                }
                return;
            }
        }
    }

    private void handle(Daemon daemon, SocketChannel connection) {
        try (connection;
                InputStream in = Channels.newInputStream(connection);
                OutputStream out = Channels.newOutputStream(connection)) {
            var response = dispatch(daemon, SocketFrames.read(in));
            SocketFrames.write(out, response);
        } catch (Exception e) {
            LOG.atDebug().setMessage("connection failed").setCause(e).log();
        }
    }

    private byte[] dispatch(Daemon daemon, byte[] request) {
        try {
            var node = SocketProtocol.JSON.readTree(request);
            return switch (node.path("op").asText("")) {
                case "search" -> {
                    SearchRequest search =
                            SocketProtocol.JSON.treeToValue(node, SearchRequest.class);
                    SearchBackend.Syntax syntax =
                            "raw".equalsIgnoreCase(search.syntax())
                                    ? SearchBackend.Syntax.RAW
                                    : "vector".equalsIgnoreCase(search.syntax())
                                            ? SearchBackend.Syntax.VECTOR
                                            : SearchBackend.Syntax.TEXT;
                    List<Hit> hits =
                            daemon.search(
                                    search.index(),
                                    search.query(),
                                    search.topN(),
                                    syntax,
                                    search.fullText());
                    yield encode(SearchResponse.ok(hits));
                }
                case "reindex" -> {
                    daemon.requestReindex();
                    daemon.drainReindex();
                    yield encode(StatusResponse.ok(daemon.counts()));
                }
                case "health" -> encode(StatusResponse.ok(Map.of()));
                default -> encode(SearchResponse.error("unknown op"));
            };
        } catch (Exception e) {
            return encodeError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private static byte[] encode(Object response) {
        try {
            return SocketProtocol.JSON.writeValueAsBytes(response);
        } catch (Exception e) {
            return encodeError(e.getMessage());
        }
    }

    private static byte[] encodeError(String message) {
        return ("{\"ok\":false,\"error\":" + quote(message) + "}").getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String message) {
        return "\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}

package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.github.lscoughlin.clarity.daemon.SocketProtocol.SearchResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoStartTest {

    private final List<StubAcceptor> stubAcceptors = new ArrayList<>();

    /** Minimal acceptor: answers every frame with one canned hit, forever. */
    static final class StubAcceptor implements AutoCloseable {
        private final ServerSocketChannel server;
        private final Thread thread;
        private volatile boolean running = true;

        StubAcceptor(Path socketPath) throws IOException {
            Files.deleteIfExists(socketPath);
            Files.createDirectories(socketPath.getParent());
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            thread =
                    Thread.ofPlatform()
                            .daemon(true)
                            .start(
                                    () -> {
                                        while (running) {
                                            try {
                                                SocketChannel conn = server.accept();
                                                Thread.ofVirtual()
                                                        .start(() -> answer(conn));
                                            } catch (IOException e) {
                                                return;
                                            }
                                        }
                                    });
        }

        private void answer(SocketChannel conn) {
            try (conn;
                    InputStream in = Channels.newInputStream(conn);
                    OutputStream out = Channels.newOutputStream(conn)) {
                SocketFrames.read(in);
                Hit hit = new Hit("docs", "doc/guide.md", List.of("Guide"), "hi", 1.0f, 7, false);
                SocketFrames.write(
                        out, SocketProtocol.JSON.writeValueAsBytes(SearchResponse.ok(List.of(hit))));
            } catch (Exception e) {
                // Test stub: ignore.
            }
        }

        @Override
        public void close() throws IOException {
            running = false;
            server.close();
        }
    }

    @Test
    void spawnsWhenNobodyListens(@TempDir Path base) throws IOException {
        Path socket = SocketTestHelper.freshSocket("autostart");
        AtomicInteger spawns = new AtomicInteger();
        DaemonClient.Spawner spawner =
                target -> {
                    spawns.incrementAndGet();
                    stubAcceptors.add(new StubAcceptor(target.socketPath()));
                };
        DaemonClient.Target target = DaemonClient.target(base, socket);

        List<Hit> hits =
                DaemonClient.search(
                        target, spawner, "docs", "q", 5, SearchBackend.Syntax.TEXT);
        assertEquals(1, spawns.get());
        assertEquals("doc/guide.md", hits.get(0).sourcePath());

        // Second call finds the running stub: no new spawn.
        DaemonClient.health(target, spawner);
        assertEquals(1, spawns.get());

        closeStubs();
    }

    @Test
    void staleSocketFileIsReplaced(@TempDir Path base) throws IOException {
        Path socket = SocketTestHelper.freshSocket("stale");
        Files.writeString(socket, "junk from an unclean exit");
        AtomicInteger spawns = new AtomicInteger();
        DaemonClient.Spawner spawner =
                target -> {
                    spawns.incrementAndGet();
                    stubAcceptors.add(new StubAcceptor(target.socketPath()));
                };
        DaemonClient.Target target = DaemonClient.target(base, socket);

        DaemonClient.health(target, spawner);

        assertEquals(1, spawns.get());
        assertTrue(Files.isDirectory(base) && !Files.isRegularFile(socket));
        closeStubs();
    }

    @Test
    void rivalServerMeansNoSpawn(@TempDir Path base) throws IOException {
        Path socket = SocketTestHelper.freshSocket("rival");
        DaemonClient.Target target = DaemonClient.target(base, socket);
        try (StubAcceptor rival = new StubAcceptor(socket)) {
            DaemonClient.health(
                    target,
                    unused -> {
                        throw new AssertionError("must not spawn");
                    });
        }
    }

    private void closeStubs() throws IOException {
        for (StubAcceptor stub : stubAcceptors) {
            stub.close();
        }
        stubAcceptors.clear();
    }
}

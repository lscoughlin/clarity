package org.github.lscoughlin.clarity.daemon;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/** Test helpers: short socket paths (macOS length limits) and no-op spawners. */
final class SocketTestHelper {
    private SocketTestHelper() {}

    private static final java.util.concurrent.atomic.AtomicLong IDS =
            new java.util.concurrent.atomic.AtomicLong();

    static Path socketDir() throws IOException {
        // /tmp keeps paths short: macOS caps socket paths around 104 chars.
        Path dir = Path.of("/tmp/clarity-socktests");
        java.nio.file.Files.createDirectories(dir);
        return dir;
    }

    static Path freshSocket(String tag) throws IOException {
        Path path = socketDir().resolve(tag + "-" + IDS.incrementAndGet() + ".sock");
        java.nio.file.Files.deleteIfExists(path);
        return path;
    }

    static DaemonClient.Spawner noSpawn() {
        return target -> {
            throw new AssertionError("must not spawn for " + target);
        };
    }

    /** Raw frame sender for protocol-abuse tests. */
    static byte[] sendRaw(Path socketPath, byte[] payload) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            try (InputStream in = Channels.newInputStream(channel);
                    OutputStream out = Channels.newOutputStream(channel)) {
                out.write(payload);
                out.flush();
                channel.shutdownOutput();
                return in.readAllBytes();
            }
        }
    }
}

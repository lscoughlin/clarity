package org.github.lscoughlin.clarity.daemon;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Length-prefixed framing: 4-byte big-endian length + payload bytes. */
final class SocketFrames {
    /** Largest accepted frame; anything bigger is a protocol error. */
    static final int MAX_FRAME = 64 << 20;

    private SocketFrames() {}

    static void write(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > MAX_FRAME) {
            throw new IOException("frame too large: " + payload.length);
        }
        var header =
                ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payload.length).array();
        out.write(header);
        out.write(payload);
        out.flush();
    }

    static byte[] read(InputStream in) throws IOException {
        var header = readFully(in, 4);
        var length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).getInt();
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("invalid frame length: " + length);
        }
        return readFully(in, length);
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        var buf = new byte[length];
        var done = 0;
        while (done < length) {
            var n = in.read(buf, done, length - done);
            if (n < 0) {
                throw new EOFException("truncated frame");
            }
            done += n;
        }
        return buf;
    }
}

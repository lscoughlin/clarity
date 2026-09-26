package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class OnnxEmbedderTest {
    @Test
    void nativeLoadErrorFallsBackToTextOnly() {
        // UnsatisfiedLinkError (native-image without the ONNX dylib) is
        // an Error, not an Exception: it must still yield empty.
        var embedder = OnnxEmbedder.tryLoad(
                () -> {
                    throw new UnsatisfiedLinkError("onnxruntime");
                });
        assertTrue(embedder.isEmpty());
    }

    @Test
    void ioFailureFallsBackToTextOnly() {
        var embedder = OnnxEmbedder.tryLoad(
                () -> {
                    throw new IOException("model cache unreadable");
                });
        assertTrue(embedder.isEmpty());
    }
}

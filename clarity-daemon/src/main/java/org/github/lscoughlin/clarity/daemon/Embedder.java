package org.github.lscoughlin.clarity.daemon;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts text to a unit-length embedding vector. Implementations are
 * either local models ({@code OnnxEmbedder}) or deterministic test
 * doubles. All implementations must return L2-normalized vectors so
 * cosine similarity is a dot product.
 */
public interface Embedder {
    /** Stable identifier persisted in index metadata for drift detection. */
    String modelId();

    /** Embedding width; persisted alongside {@link #modelId}. */
    int dimensions();

    /**
     * Embeds {@code text}; the returned array has {@link #dimensions}
     * entries and unit L2 norm.
     */
    float[] embed(String text);

    /**
     * Embeds a batch in order; row {@code i} equals {@code embed} of
     * input {@code i}. The default runs one call per row;
     * implementations may fuse rows into fewer inference runs as
     * long as values stay identical.
     */
    default List<float[]> embedBatch(List<String> texts) {
        var out = new ArrayList<float[]>(texts.size());
        for (var text : texts) {
            out.add(embed(text));
        }
        return out;
    }
}

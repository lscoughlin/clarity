package org.github.lscoughlin.clarity.daemon;

import java.util.List;

/**
 * One search result: the chunk that matched, where it came from, and its
 * score. {@code text} is the full chunk unless {@code truncated} is true, in
 * which case it is a bounded, highlighted snippet around the best-scoring
 * passage — pass {@code full_text: true} on the query to always get the
 * full chunk.
 */
public record Hit(
        String indexName,
        String sourcePath,
        List<String> headingPath,
        String text,
        float score,
        int startLine,
        boolean truncated) {
    /** Heading breadcrumb, e.g. {@code "Usage / Indexing"}. Empty for non-Markdown chunks. */
    public String heading() {
        return String.join(" / ", headingPath);
    }

    /** Display location: {@code path:line}, or bare {@code path} when the line is unknown. */
    public String location() {
        return startLine <= 0 ? sourcePath : sourcePath + ":" + startLine;
    }
}

package org.github.lscoughlin.clarity.parser;

import java.util.List;

/**
 * One indexable unit of a source file: the originating path, the
 * heading breadcrumb for Markdown chunks (empty for YAML and source
 * comments), the text to index, and the 1-based line where the chunk
 * starts (0 when unknown, e.g. data predating line tracking).
 */
public record Chunk(String sourcePath, List<String> headingPath, String text, int startLine) {
    public Chunk {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath is required");
        }
        if (text == null) {
            throw new IllegalArgumentException("text is required");
        }
        if (startLine < 0) {
            throw new IllegalArgumentException("startLine must be >= 0");
        }
        headingPath = headingPath == null ? List.of() : List.copyOf(headingPath);
    }

    /** Heading breadcrumb, e.g. {@code "Usage / Indexing"}. Empty for non-Markdown chunks. */
    public String heading() {
        return String.join(" / ", headingPath);
    }
}

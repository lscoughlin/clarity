package org.github.lscoughlin.clarity.parser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One indexable unit of a source file: the originating path, the
 * heading breadcrumb for Markdown chunks (empty for YAML and source
 * comments), the text to index, the 1-based line where the chunk starts
 * (0 when unknown, e.g. data predating line tracking), and the parsed
 * frontmatter of its source file, if any (empty for files with no
 * frontmatter, or when it isn't a YAML mapping).
 */
public record Chunk(
        String sourcePath,
        List<String> headingPath,
        String text,
        int startLine,
        Map<String, Object> frontmatter) {
    public Chunk(String sourcePath, List<String> headingPath, String text, int startLine) {
        this(sourcePath, headingPath, text, startLine, Map.of());
    }

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
        // LinkedHashMap, not Map.copyOf: frontmatter YAML can have a
        // key with no value (e.g. "related_docs:"), which parses to a
        // null value that Map.copyOf/Map.of reject; key order also
        // matters since agents scan frontmatter top-to-bottom.
        frontmatter =
                frontmatter == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
    }

    /** Heading breadcrumb, e.g. {@code "Usage / Indexing"}. Empty for non-Markdown chunks. */
    public String heading() {
        return String.join(" / ", headingPath);
    }
}

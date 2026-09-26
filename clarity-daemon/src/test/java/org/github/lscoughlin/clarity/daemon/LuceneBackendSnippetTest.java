package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Highlighted-snippet behavior for hits whose chunk exceeds the size
 * threshold: bounded, highlighted by default; full text on request, for
 * vector hits, and for hits matched only via the unstored heading field.
 */
class LuceneBackendSnippetTest {

    private static final String FILLER =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ";

    /** A single-paragraph section comfortably over the snippet threshold. */
    private static String longSection(String term) {
        var sb = new StringBuilder();
        while (sb.length() < 1200) {
            sb.append(FILLER);
        }
        sb.append("The ").append(term).append(" is described in detail right here.");
        while (sb.length() < 2000) {
            sb.append(FILLER);
        }
        return sb.toString();
    }

    private static void writeCorpus(Path base, String longBody) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/short.md"), "# Short\nA brief mention of kumquat here.\n");
        Files.writeString(base.resolve("doc/long.md"), "# Architecture\n" + longBody + "\n");
    }

    private static Hit find(List<Hit> hits, String path) {
        return hits.stream().filter(h -> h.sourcePath().equals(path)).findFirst().orElseThrow();
    }

    @Test
    void shortChunkReturnsFullTextByDefault(@TempDir Path base) throws IOException {
        writeCorpus(base, longSection("kumquat"));
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            Hit hit = find(service.search("docs", "kumquat", 10), "doc/short.md");
            assertFalse(hit.truncated());
            assertEquals("A brief mention of kumquat here.", hit.text());
        }
    }

    @Test
    void longChunkReturnsBoundedHighlightedSnippetByDefault(@TempDir Path base) throws IOException {
        writeCorpus(base, longSection("kumquat"));
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            Hit full = find(service.search("docs", "kumquat", 10, SearchBackend.Syntax.TEXT, true), "doc/long.md");
            assertFalse(full.truncated());
            assertTrue(full.text().length() > 1000, "fixture should exceed the snippet threshold");

            Hit snippet = find(service.search("docs", "kumquat", 10), "doc/long.md");
            assertTrue(snippet.truncated());
            assertTrue(snippet.text().length() < full.text().length());
            assertTrue(snippet.text().contains("**kumquat**"), snippet.text());
            // Bounded-output guarantee: well under the full chunk, with slack for
            // the "..." ellipsis markers capToWindow may add at either edge.
            assertTrue(snippet.text().length() <= 350, snippet.text());
        }
    }

    @Test
    void headingOnlyMatchIgnoresLiteralMarkdownBoldInBody(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        // A literal "**bold**" right at the start of the body must not be
        // mistaken for a query-term highlight when "papaya" (the query) only
        // matches via the heading, not this body at all.
        var body = "This section opens with **emphasis** on formatting. " + longSection("unrelated");
        Files.writeString(base.resolve("doc/long.md"), "# papaya\n" + body + "\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hits = service.search("docs", "papaya", 10);
            assertEquals(1, hits.size());
            assertFalse(hits.get(0).truncated(), hits.get(0).text());
        }
    }

    @Test
    void fullTextTrueReturnsFullChunkRegardlessOfLength(@TempDir Path base) throws IOException {
        writeCorpus(base, longSection("kumquat"));
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            Hit hit = find(service.search("docs", "kumquat", 10, SearchBackend.Syntax.TEXT, true), "doc/long.md");
            assertFalse(hit.truncated());
            assertTrue(hit.text().contains("kumquat"));
            assertTrue(hit.text().length() > 1000);
        }
    }

    @Test
    void rawSyntaxSnippetsLongChunkToo(@TempDir Path base) throws IOException {
        writeCorpus(base, longSection("kumquat"));
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            Hit hit =
                    find(service.search("docs", "kumquat", 10, SearchBackend.Syntax.RAW), "doc/long.md");
            assertTrue(hit.truncated());
            assertTrue(hit.text().contains("**kumquat**"));
        }
    }

    @Test
    void vectorModeAlwaysReturnsFullText(@TempDir Path base) throws IOException {
        var longBody = longSection("kumquat");
        writeCorpus(base, longBody);
        var embedder =
                new VectorSearchTest.FixedEmbedder(
                        "test-1",
                        Map.of(
                                VectorSearchTest.FixedEmbedder.normalize("Short\nA brief mention of kumquat here."),
                                new float[] {1, 0},
                                VectorSearchTest.FixedEmbedder.normalize("Architecture\n" + longBody),
                                new float[] {0, 1},
                                "query", new float[] {0, 1}));
        try (IndexService service = IndexService.open(base, ConfigLoader.load(base), embedder)) {
            service.reindex();
            List<Hit> hits = service.search("docs", "query", 10, SearchBackend.Syntax.VECTOR);
            Hit hit = find(hits, "doc/long.md");
            assertFalse(hit.truncated());
            assertTrue(hit.text().length() > 1000);
        }
    }

    @Test
    void headingOnlyMatchOnLongChunkReturnsFullText(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(
                base.resolve("doc/long.md"), "# kumquat\n" + longSection("unrelated body text") + "\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hits = service.search("docs", "kumquat", 10);
            assertEquals(1, hits.size());
            assertFalse(hits.get(0).truncated());
        }
    }
}

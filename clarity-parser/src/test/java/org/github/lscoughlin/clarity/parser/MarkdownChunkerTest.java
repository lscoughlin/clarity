package org.github.lscoughlin.clarity.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownChunkerTest {

    @Test
    void chunksByHeadingWithLeadingChunk() {
        String doc =
                """
                Intro paragraph.

                # Usage
                How to use.

                ## Indexing
                Deep dive.

                # Empty

                # After
                Tail.
                """;

        List<Chunk> chunks = MarkdownChunker.chunk("doc/guide.md", doc);

        assertEquals(5, chunks.size());
        assertEquals(List.of(), chunks.get(0).headingPath());
        assertEquals("Intro paragraph.", chunks.get(0).text());
        assertEquals(List.of("Usage"), chunks.get(1).headingPath());
        assertEquals("How to use.", chunks.get(1).text());
        assertEquals(List.of("Usage", "Indexing"), chunks.get(2).headingPath());
        assertEquals("Deep dive.", chunks.get(2).text());
        assertEquals(List.of("Empty"), chunks.get(3).headingPath());
        assertEquals("", chunks.get(3).text());
        assertEquals(List.of("After"), chunks.get(4).headingPath());
        assertEquals("Tail.", chunks.get(4).text());
        assertTrue(chunks.stream().allMatch(c -> c.sourcePath().equals("doc/guide.md")));
    }

    @Test
    void siblingHeadingsResetNesting() {
        String doc = "# A\nBody A.\n# B\nBody B.\n";

        List<Chunk> chunks = MarkdownChunker.chunk("s.md", doc);

        assertEquals(2, chunks.size());
        assertEquals("A", chunks.get(0).heading());
        assertEquals("B", chunks.get(1).heading());
    }

    @Test
    void reportsSectionStartLines() {
        String doc = "# A\nBody A.\n\n## B\nBody B.\n";

        List<Chunk> chunks = MarkdownChunker.chunk("s.md", doc);

        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(4, chunks.get(1).startLine());
    }

    @Test
    void frontmatterOffsetsBodyLines() {
        String doc = "---\ntitle: Guide\n---\n# Usage\nHow to use.\n";

        List<Chunk> chunks = MarkdownChunker.chunk("s.md", doc);

        assertEquals(2, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(4, chunks.get(1).startLine());
    }

    @Test
    void frontmatterBecomesItsOwnChunk() {
        String doc = "---\ntitle: Guide\ntags: [a]\n---\n# Usage\nHow to use.\n";

        List<Chunk> chunks = MarkdownChunker.chunk("doc/guide.md", doc);

        assertEquals(2, chunks.size());
        assertEquals(List.of("frontmatter"), chunks.get(0).headingPath());
        assertEquals("title: Guide\ntags: [a]", chunks.get(0).text());
        assertEquals(List.of("Usage"), chunks.get(1).headingPath());
        assertEquals("How to use.", chunks.get(1).text());
    }

    @Test
    void frontmatterOnlyFileYieldsOneChunk() {
        List<Chunk> chunks = MarkdownChunker.chunk("s.md", "---\ntitle: Solo\n---\n");

        assertEquals(1, chunks.size());
        assertEquals("frontmatter", chunks.get(0).heading());
    }

    @Test
    void midDocumentRuleIsNotFrontmatter() {
        String doc = "# A\nBody A.\n\n---\n\nTail.\n";

        List<Chunk> chunks = MarkdownChunker.chunk("s.md", doc);

        assertTrue(chunks.stream().noneMatch(c -> c.heading().equals("frontmatter")));
        assertEquals("Body A.\n\n***\n\nTail.", chunks.get(0).text());
    }

    @Test
    void unclosedLeadingRuleStaysBody() {
        String doc = "---\ntitle: No end\n# A\nBody A.\n";

        List<Chunk> chunks = MarkdownChunker.chunk("s.md", doc);

        assertTrue(chunks.stream().noneMatch(c -> c.heading().equals("frontmatter")));
        assertEquals(2, chunks.size());
        assertEquals("***\n\ntitle: No end", chunks.get(0).text());
        assertEquals("A", chunks.get(1).heading());
    }
}

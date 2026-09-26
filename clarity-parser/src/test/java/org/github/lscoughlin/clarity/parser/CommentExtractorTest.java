package org.github.lscoughlin.clarity.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommentExtractorTest {

    @Test
    void javaCommentsOnlyNeverCode() {
        String java =
                """
                // leading comment
                // second line
                int x = 5; // trailing
                String s = "http://not-a-comment";
                /**
                 * block comment
                 */
                int y = /* inline */ 6;
                """;

        List<Chunk> chunks = CommentExtractor.extract("A.java", "java", java);

        assertEquals(4, chunks.size());
        assertEquals("leading comment\nsecond line", chunks.get(0).text());
        assertEquals("trailing", chunks.get(1).text());
        assertEquals("block comment", chunks.get(2).text());
        assertEquals("inline", chunks.get(3).text());
        String all = chunks.stream().map(Chunk::text).reduce("", (a, b) -> a + "\n" + b);
        assertTrue(!all.contains("int x"));
        assertTrue(!all.contains("http"));
    }

    @Test
    void pascalBracesParensAndStrings() {
        String pascal =
                """
                { brace comment }
                (* paren comment *)
                x := 1; // trailing
                s := '(* not a comment *)';
                """;

        List<Chunk> chunks = CommentExtractor.extract("u.pas", "pascal", pascal);

        assertEquals(3, chunks.size());
        assertEquals("brace comment", chunks.get(0).text());
        assertEquals("paren comment", chunks.get(1).text());
        assertEquals("trailing", chunks.get(2).text());
    }

    @Test
    void goRustSwiftShareCStyle() {
        for (String kind : List.of("go", "rust", "swift")) {
            List<Chunk> chunks =
                    CommentExtractor.extract("f", kind, "fn main() {} // hi\n");
            assertEquals(1, chunks.size(), kind);
            assertEquals("hi", chunks.get(0).text(), kind);
        }
    }

    @Test
    void unknownKindRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CommentExtractor.extract("f", "cobol", "*> hi\n"));
    }

    @Test
    void reportsStartLines() {
        String java =
                """
                // leading comment
                // second line
                int x = 5; // trailing
                /**
                 * block comment
                 */
                int y = /* inline */ 6;
                """;

        List<Chunk> chunks = CommentExtractor.extract("A.java", "java", java);

        assertEquals(4, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(3, chunks.get(1).startLine());
        assertEquals(4, chunks.get(2).startLine());
        assertEquals(7, chunks.get(3).startLine());
    }

    @Test
    void pascalReportsStartLines() {
        String pascal = "{ brace comment }\n(* paren comment *)\nx := 1; // trailing\n";

        List<Chunk> chunks = CommentExtractor.extract("u.pas", "pascal", pascal);

        assertEquals(3, chunks.size());
        assertEquals(1, chunks.get(0).startLine());
        assertEquals(2, chunks.get(1).startLine());
        assertEquals(3, chunks.get(2).startLine());
    }
}

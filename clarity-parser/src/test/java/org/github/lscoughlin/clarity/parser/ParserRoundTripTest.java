package org.github.lscoughlin.clarity.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParserRoundTripTest {

    @Test
    void globYamlAndMarkdownCompose(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(base.resolve("doc/guide.md"), "# Hi\nBody.\n");
        Files.writeString(base.resolve("doc/data.yaml"), "a: 1\n");
        Files.writeString(base.resolve("doc/skip.txt"), "nope\n");

        List<Path> md = GlobResolver.resolve(base, List.of("doc/**/*.md"));
        List<Path> yaml = GlobResolver.resolve(base, List.of("doc/**/*.yaml"));

        assertEquals(List.of(base.resolve("doc/guide.md")), md);
        assertEquals(List.of(base.resolve("doc/data.yaml")), yaml);

        List<Chunk> mdChunks =
                MarkdownChunker.chunk("doc/guide.md", Files.readString(md.get(0)));
        assertEquals(1, mdChunks.size());
        assertEquals("Hi", mdChunks.get(0).heading());

        Chunk yamlChunk = YamlLoader.load("doc/data.yaml", Files.readString(yaml.get(0)));
        assertEquals("a: 1", yamlChunk.text());
        assertTrue(yamlChunk.headingPath().isEmpty());
    }

    @Test
    void absoluteGlobRejected(@TempDir Path base) {
        assertThrows(
                IllegalArgumentException.class,
                () -> GlobResolver.resolve(base, List.of("/etc/**/*.md")));
    }
}

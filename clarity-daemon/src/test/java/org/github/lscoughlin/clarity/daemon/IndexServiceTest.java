package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexServiceTest {

    @Test
    void roundTripIndexesAndSearchesAllKinds(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();

            assertEquals(Map.of("docs", 3), service.counts());

            List<Hit> md = service.search("docs", "reindex", 10);
            assertTrue(md.stream().anyMatch(h -> h.sourcePath().equals("doc/guide.md")));

            List<Hit> yaml = service.search("docs", "retries", 10);
            assertEquals(1, yaml.size());
            assertEquals("doc/data.yaml", yaml.get(0).sourcePath());

            List<Hit> code = service.search("docs", "computes", 10);
            assertEquals(1, code.size());
            assertEquals("src/Main.java", code.get(0).sourcePath());
            assertTrue(code.get(0).text().contains("computes"));
            assertTrue(!code.get(0).text().contains("System.out"));
        }
    }

    @Test
    void reindexIsIdempotentAndPropagatesDeletes(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            service.reindex();
            assertEquals(Map.of("docs", 3), service.counts());

            Files.delete(base.resolve("doc/guide.md"));
            service.reindex();

            assertEquals(Map.of("docs", 2), service.counts());
            assertTrue(service.search("docs", "reindex", 10).isEmpty());
        }
    }

    @Test
    void identicalReindexWritesNothing(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            assertEquals(Map.of("docs", 3L), service.writeCounts());
            service.reindex();
            assertEquals(Map.of("docs", 3L), service.writeCounts());
            assertEquals(Map.of("docs", 3), service.counts());
        }
    }

    @Test
    void changedFileReparsesOnce(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            Files.writeString(
                    base.resolve("doc/guide.md"), "# Guide\nHow to reindex the corpus v2.\n");
            service.reindex();
            assertEquals(Map.of("docs", 4L), service.writeCounts());
            assertEquals(1, service.search("docs", "v2", 10).size());
        }
    }

    @Test
    void unknownIndexAndMissingConfigFailFast(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            assertThrows(IllegalArgumentException.class, () -> service.search("nope", "x", 10));
        }
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(base.resolve("empty")));
    }

    @Test
    void punctuatedQueryReturnsMatches(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/notes.md"), "# Notes\nC++ (notes) here.\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> balanced = service.search("docs", "C++ (notes)", 10);
            assertTrue(balanced.stream().anyMatch(h -> h.sourcePath().equals("doc/notes.md")));
            List<Hit> unbalanced = service.search("docs", "C++ (notes", 10);
            assertTrue(unbalanced.stream().anyMatch(h -> h.sourcePath().equals("doc/notes.md")));
        }
    }

    @Test
    void hitsCarryStartLinesEndToEnd(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.createDirectories(base.resolve("src"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                """
                index:
                  docs:
                    index_path: index/docs
                    markdown:
                      - doc/**/*.md
                    yaml:
                      - doc/**/*.yaml
                    source:
                      kind: [java]
                      include:
                        - src/**/*.java
                """);
        Files.writeString(base.resolve("doc/guide.md"), "# Guide\nHow to reindex the corpus.\n");
        Files.writeString(base.resolve("doc/data.yaml"), "server:\n  retries: 3\n");
        Files.writeString(
                base.resolve("src/Main.java"),
                "// header\npublic class Main {\n  // computes the answer\n}\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> md = service.search("docs", "reindex", 10);
            assertEquals(1, md.size());
            assertEquals("doc/guide.md:1", md.get(0).location());
            List<Hit> yaml = service.search("docs", "retries", 10);
            assertEquals(1, yaml.size());
            assertEquals("doc/data.yaml:1", yaml.get(0).location());
            List<Hit> code = service.search("docs", "computes", 10);
            assertEquals(1, code.size());
            assertEquals("src/Main.java:3", code.get(0).location());
        }
    }

    @Test
    void unknownLineRendersBarePath(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hits = service.search("docs", "reindex", 10);
            assertEquals(1, hits.size());
            assertEquals("doc/guide.md:1", hits.get(0).location());
            Hit unknown =
                    new Hit(
                            hits.get(0).indexName(),
                            hits.get(0).sourcePath(),
                            hits.get(0).headingPath(),
                            hits.get(0).text(),
                            hits.get(0).score(),
                            0,
                            hits.get(0).truncated());
            assertEquals("doc/guide.md", unknown.location());
        }
    }

    @Test
    void rawSyntaxHonorsPhraseQuery(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/a.md"), "# A\nreindex the corpus now.\n");
        Files.writeString(base.resolve("doc/b.md"), "# B\ncorpus reindex now.\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hits =
                    service.search(
                            "docs", "\"reindex the corpus\"", 10, SearchBackend.Syntax.RAW);
            assertEquals(1, hits.size());
            assertEquals("doc/a.md", hits.get(0).sourcePath());
        }
    }

    @Test
    void headingOnlyTermsRetrieve(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/a.md"), "# hello clarity jar world\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hits = service.search("docs", "hello clarity", 10);
            assertEquals(1, hits.size());
            assertEquals("doc/a.md:1", hits.get(0).location());
            assertEquals("hello clarity jar world", hits.get(0).heading());
        }
    }

    @Test
    void headingMatchRanksAboveBodyMatch(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/a.md"), "# mango\nUnrelated body words here.\n");
        Files.writeString(
                base.resolve("doc/b.md"), "# Other topic\nThe mango shipment arrived today.\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hits = service.search("docs", "mango", 10);
            assertEquals(2, hits.size());
            assertEquals("doc/a.md", hits.get(0).sourcePath());
        }
    }

    @Test
    void rawSyntaxAddressesHeadingField(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/a.md"), "# mango\nUnrelated body words here.\n");
        Files.writeString(
                base.resolve("doc/b.md"), "# Other topic\nThe mango shipment arrived today.\n");
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hits =
                    service.search("docs", "heading_text:mango", 10, SearchBackend.Syntax.RAW);
            assertEquals(1, hits.size());
            assertEquals("doc/a.md", hits.get(0).sourcePath());
        }
    }

    private static void writeCorpus(Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.createDirectories(base.resolve("src"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                """
                index:
                  docs:
                    index_path: index/docs
                    markdown:
                      - doc/**/*.md
                    yaml:
                      - doc/**/*.yaml
                    source:
                      kind: [java]
                      include:
                        - src/**/*.java
                """);
        Files.writeString(
                base.resolve("doc/guide.md"), "# Guide\nHow to reindex the corpus.\n");
        Files.writeString(base.resolve("doc/data.yaml"), "server:\n  retries: 3\n");
        Files.writeString(
                base.resolve("src/Main.java"),
                "// computes the answer\npublic class Main {\n  public static void main(String[] a) {\n"
                        + "    System.out.println(42);\n  }\n}\n");
    }
}

package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.clarity.parser.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Vector plumbing with synthetic embeddings: every vector is
 * hand-set, so similarities are constructed and the test is fully
 * offline and deterministic. Paraphrase *quality* of the production
 * model is proven manually (see the vector-search plan's spike note),
 * not asserted here.
 */
class VectorSearchTest {

    /** Test double: exact-match vectors over whitespace-normalized text. */
    static final class FixedEmbedder implements Embedder {
        private final String modelId;
        private final Map<String, float[]> vectors;

        FixedEmbedder(String modelId, Map<String, float[]> vectors) {
            this.modelId = modelId;
            this.vectors = vectors;
        }

        @Override
        public String modelId() {
            return modelId;
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public float[] embed(String text) {
            float[] vector = vectors.get(normalize(text));
            if (vector == null) {
                throw new IllegalArgumentException("no synthetic vector for: " + text);
            }
            return vector.clone();
        }

        static String normalize(String text) {
            return text.trim().replaceAll("\\s+", " ");
        }
    }

    private static final Map<String, float[]> VECTORS =
            Map.of(
                    "Felines About whiskered companions.", new float[] {1, 0},
                    "Canines About barking companions.", new float[] {0, 1},
                    "kitty", new float[] {0.9f, 0.43589f});

    private static void writeCorpus(Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        Files.writeString(base.resolve("doc/cats.md"), "# Felines\nAbout whiskered companions.\n");
        Files.writeString(base.resolve("doc/dogs.md"), "# Canines\nAbout barking companions.\n");
    }

    @Test
    void vectorQueryRetrievesParaphraseWithNoSharedTokens(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            service.reindex();
            // "kitty" shares no tokens with either chunk.
            List<Hit> hits = service.search("docs", "kitty", 10, SearchBackend.Syntax.VECTOR);
            assertEquals(2, hits.size());
            assertEquals("doc/cats.md", hits.get(0).sourcePath());
            assertEquals("doc/dogs.md", hits.get(1).sourcePath());
            // Text search cannot make the same leap.
            assertTrue(service.search("docs", "kitty", 10).isEmpty());
        }
    }

    @Test
    void vectorSearchOnTextOnlyIndexFails(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            assertThrows(
                    IOException.class,
                    () -> service.search("docs", "kitty", 10, SearchBackend.Syntax.VECTOR));
        }
    }

    @Test
    void upgradeFromTextOnlyBackfillsVectors(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            assertEquals(Map.of("docs", 2L), service.writeCounts());
        }
        // Same files, unchanged checksums — the embedder still forces a full
        // pass. (writeCounts is per backend instance, so 2 proves both
        // files were rewritten; a checksum skip would leave 0.)
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            service.reindex();
            assertEquals(Map.of("docs", 2L), service.writeCounts());
            List<Hit> hits = service.search("docs", "kitty", 10, SearchBackend.Syntax.VECTOR);
            assertEquals("doc/cats.md", hits.get(0).sourcePath());
        }
    }

    @Test
    void batchDefaultDelegatesInOrder() {
        var seen = new ArrayList<String>();
        Embedder stub =
                new Embedder() {
                    @Override
                    public String modelId() {
                        return "test-recording-1";
                    }

                    @Override
                    public int dimensions() {
                        return 2;
                    }

                    @Override
                    public float[] embed(String text) {
                        seen.add(text);
                        return new float[] {1, 0};
                    }
                };
        var out = stub.embedBatch(List.of("a", "b", "c"));
        assertEquals(List.of("a", "b", "c"), seen);
        assertEquals(3, out.size());
        assertTrue(out.stream().allMatch(v -> v[0] == 1 && v[1] == 0));
    }

    @Test
    void backendBatchesRowsAtCap(@TempDir Path base) throws IOException {
        var counting = new CountingEmbedder();
        var chunks = new ArrayList<Chunk>();
        for (var i = 1; i <= LuceneBackend.EMBED_BATCH_ROWS + 6; i++) {
            chunks.add(new Chunk("f.md", List.of(), "text " + i, i));
        }
        var indexPath = base.resolve("index");
        Files.createDirectories(indexPath);
        try (var backend = new LuceneBackend("docs", indexPath, counting)) {
            backend.updateDocuments("f.md", "chk", chunks);
        }
        assertEquals(
                List.of(LuceneBackend.EMBED_BATCH_ROWS, 6), counting.batchSizes);
    }

    /** Counts embedBatch rows; vectors are constant. */
    static final class CountingEmbedder implements Embedder {
        final List<Integer> batchSizes = new ArrayList<>();

        @Override
        public String modelId() {
            return "test-counting-1";
        }

        @Override
        public int dimensions() {
            return 2;
        }

        @Override
        public float[] embed(String text) {
            return new float[] {1, 0};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            batchSizes.add(texts.size());
            return Embedder.super.embedBatch(texts);
        }
    }

    @Test
    void modelDriftFailsFast(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            service.reindex();
        }
        IOException drift =
                assertThrows(
                        IOException.class,
                        () ->
                                IndexService.open(
                                        base,
                                        ConfigLoader.load(base),
                                        new FixedEmbedder("test-2", VECTORS)));
        assertTrue(drift.getMessage().contains("drift"));
    }
}

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
    void pathPrefixFiltersVectorCandidatesBeforeKnn(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            service.reindex();
            // Unscoped: both docs match "kitty" via vector search.
            List<Hit> unscoped = service.search("docs", "kitty", 10, SearchBackend.Syntax.VECTOR);
            assertEquals(2, unscoped.size());

            // Scoped to doc/dogs.md: only the in-subtree doc is a KNN
            // candidate, even though doc/cats.md is the closer vector match.
            List<Hit> scoped =
                    service.search(
                            "docs", "kitty", 10, SearchBackend.Syntax.VECTOR, "doc/dogs.md");
            assertEquals(1, scoped.size());
            assertEquals("doc/dogs.md", scoped.get(0).sourcePath());
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
    void hybridSearchUsesVectorWhenTextFindsNothing(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            service.reindex();
            // "kitty" shares no tokens with either chunk, so BM25 alone finds
            // nothing — if hybrid silently ignored the vector leg (e.g. a bug
            // that degrades even when an embedder IS available), this would
            // come back empty instead of the vector-ranked order below.
            List<Hit> hits = service.search("docs", "kitty", 10, SearchBackend.Syntax.HYBRID);
            assertEquals(2, hits.size());
            assertEquals("doc/cats.md", hits.get(0).sourcePath());
            assertEquals("doc/dogs.md", hits.get(1).sourcePath());
        }
    }

    @Test
    void hybridSearchDegradesToTextOnlyWithoutEmbedder(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
            List<Hit> hybrid = service.search("docs", "companions", 10, SearchBackend.Syntax.HYBRID);
            List<Hit> text = service.search("docs", "companions", 10, SearchBackend.Syntax.TEXT);
            assertEquals(
                    text.stream().map(Hit::sourcePath).toList(),
                    hybrid.stream().map(Hit::sourcePath).toList());
        }
    }

    @Test
    void hybridSearchHonorsPathPrefix(@TempDir Path base) throws IOException {
        writeCorpus(base);
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            service.reindex();
            List<Hit> scoped =
                    service.search(
                            "docs", "kitty", 10, SearchBackend.Syntax.HYBRID, "doc/dogs.md");
            assertEquals(1, scoped.size());
            assertEquals("doc/dogs.md", scoped.get(0).sourcePath());
        }
    }

    @Test
    void hybridSearchFusesRanksAcrossBothLegs(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(
                base.resolve(".clarity/config.yaml"),
                "index:\n  docs:\n    index_path: index/docs\n    markdown:\n      - doc/**/*.md\n");
        // BM25 order for "quokka": a (tf=3) > b (tf=1) > d (tf=1, longer doc
        // lowers its BM25 score further via length normalization).
        Files.writeString(base.resolve("doc/a.md"), "# A\nquokka quokka quokka wombat.\n");
        Files.writeString(base.resolve("doc/b.md"), "# B\nquokka echidna.\n");
        Files.writeString(base.resolve("doc/d.md"), "# D\nquokka bilby elephant zebra giraffe.\n");
        var vectors =
                new java.util.HashMap<String, float[]>(
                        Map.of(
                                "A quokka quokka quokka wombat.", new float[] {0.9f, 0.43589f},
                                "B quokka echidna.", new float[] {0f, 1f},
                                "D quokka bilby elephant zebra giraffe.", new float[] {1f, 0f},
                                "quokka", new float[] {1f, 0f}));
        try (IndexService service =
                IndexService.open(
                        base, ConfigLoader.load(base), new FixedEmbedder("test-1", vectors))) {
            service.reindex();

            List<Hit> bm25 = service.search("docs", "quokka", 10, SearchBackend.Syntax.TEXT);
            assertEquals(List.of("doc/a.md", "doc/b.md", "doc/d.md"), bm25.stream().map(Hit::sourcePath).toList());

            List<Hit> vector = service.search("docs", "quokka", 10, SearchBackend.Syntax.VECTOR);
            assertEquals(List.of("doc/d.md", "doc/a.md", "doc/b.md"), vector.stream().map(Hit::sourcePath).toList());

            // Fused order must differ from both legs' own native order: d.md
            // (BM25's worst-ranked doc) jumps over b.md purely on the
            // strength of the vector leg's preference for it.
            List<Hit> hybrid = service.search("docs", "quokka", 10, SearchBackend.Syntax.HYBRID);
            assertEquals(
                    List.of("doc/a.md", "doc/d.md", "doc/b.md"),
                    hybrid.stream().map(Hit::sourcePath).toList());
        }
    }

    @Test
    void hybridSearchDegradesWhenVectorLegFails(@TempDir Path base) throws IOException {
        writeCorpus(base);
        // Index without an embedder first, then reopen with one but skip a
        // reindex pass — every document predates the "embedding" field even
        // though the backend now has an embedder configured. Whether Lucene
        // throws from the KNN sub-query in this state or just returns no
        // candidates is not pinned down here (see
        // hybridSearchDegradesWhenEmbedRaisesAnException for a test that
        // unambiguously exercises the catch); either way HYBRID must still
        // return the BM25-ranked results, not fail the whole query.
        try (IndexService service = IndexService.open(base)) {
            service.reindex();
        }
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            List<Hit> hits = service.search("docs", "companions", 10, SearchBackend.Syntax.HYBRID);
            assertEquals(2, hits.size());
        }
    }

    @Test
    void hybridSearchDegradesWhenEmbedRaisesAnException(@TempDir Path base) throws IOException {
        writeCorpus(base);
        // FixedEmbedder.embed throws IllegalArgumentException for any text
        // it has no synthetic vector for — "companions" isn't a VECTORS key,
        // so embedding the query itself fails, unambiguously exercising the
        // catch around embedder.embed(query) in searchHybrid.
        try (IndexService service =
                IndexService.open(base, ConfigLoader.load(base), new FixedEmbedder("test-1", VECTORS))) {
            service.reindex();
            List<Hit> hits = service.search("docs", "companions", 10, SearchBackend.Syntax.HYBRID);
            assertEquals(2, hits.size());
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

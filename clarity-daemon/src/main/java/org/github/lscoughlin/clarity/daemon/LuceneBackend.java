package org.github.lscoughlin.clarity.daemon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.github.lscoughlin.clarity.parser.Chunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * Lucene implementation of {@link SearchBackend}: one instance owns one
 * on-disk index. Document schema is one document per chunk —
 * {@code path} and {@code heading} stored verbatim, {@code text} analyzed
 * for search and stored for display, and non-empty headings additionally
 * indexed (unstored) as analyzed {@code heading_text} so heading-only
 * terms retrieve. Updates are path-keyed so reindexing
 * is idempotent and deletions propagate. When an {@link Embedder} is
 * present, each chunk also carries a COSINE {@code embedding} vector
 * over its heading plus body text, and the writer's commit metadata
 * records the embedding model id and dimensions for drift detection.
 */
final class LuceneBackend implements SearchBackend {
    private static final Logger LOG = LoggerFactory.getLogger(LuceneBackend.class);

    private static final String META_MODEL = "embedding_model";
    private static final String META_DIMS = "embedding_dims";
    private static final String META_SCHEMA = "schema_version";
    /**
     * Index schema generation: 1 adds the stored {@code line} field, 2
     * adds the analyzed {@code heading_text} field, 3 adds the stored
     * {@code frontmatter} field.
     */
    private static final String SCHEMA_VERSION = "3";

    /** Heading matches rank above equal body matches in TEXT mode. */
    private static final float HEADING_BOOST = 2.0f;

    /** Bounded highlight window per hit, in chars — "a couple hundred" per the sketch this implements. */
    private static final int SNIPPET_WINDOW_CHARS = 300;

    /**
     * Chunks at or below this length return in full even without
     * {@code full_text: true} — snippeting only pays off once the saved
     * context is a multiple of the window. Tuned against a reference corpus:
     * most chunks are well under this, so most hits never invoke the
     * highlighter at all.
     */
    private static final int SNIPPET_THRESHOLD_CHARS = SNIPPET_WINDOW_CHARS * 3;

    /**
     * Highlight sentinels from the Unicode private-use area, swapped for
     * markdown bold ({@code **}) only in the final windowed snippet.
     * Chunk text is markdown, so real {@code **bold**} source text must not
     * be mistaken for a query-term highlight while detecting or centering.
     */
    private static final String HIGHLIGHT_START = "";

    private static final String HIGHLIGHT_END = "";

    /** Standard reciprocal-rank-fusion constant (Cormack et al.); not user-configurable. */
    private static final int RRF_K = 60;

    /** Per-leg candidate depth before fusion: generous over-fetch relative to topN. */
    private static final int RRF_CANDIDATE_MULTIPLIER = 4;

    /** Floor on candidate depth so small topN values still give RRF room to work with. */
    private static final int RRF_MIN_CANDIDATES = 40;

    /** Max embedding rows per inference run; bounds session memory on huge files. */
    static final int EMBED_BATCH_ROWS = 64;

    private final String indexName;
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter writer;
    private final Embedder embedder;
    private final AtomicLong writes = new AtomicLong();

    LuceneBackend(String indexName, Path indexPath) throws IOException {
        this(indexName, indexPath, null);
    }

    LuceneBackend(String indexName, Path indexPath, Embedder embedder) throws IOException {
        this.indexName = indexName;
        this.directory = FSDirectory.open(indexPath);
        this.analyzer = new StandardAnalyzer();
        IndexWriterConfig config =
                new IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(directory, config);
        this.embedder = embedder;
        checkDrift();
    }

    @Override
    public void updateDocuments(String sourcePath, String checksum, List<Chunk> chunks)
            throws IOException {
        writer.deleteDocuments(new Term("path", sourcePath));
        for (var start = 0; start < chunks.size(); start += EMBED_BATCH_ROWS) {
            var batch = chunks.subList(start, Math.min(start + EMBED_BATCH_ROWS, chunks.size()));
            var vectors =
                    embedder == null
                            ? null
                            : embedder.embedBatch(batch.stream().map(LuceneBackend::embeddableText).toList());
            for (var i = 0; i < batch.size(); i++) {
                writer.addDocument(
                        toDocument(
                                sourcePath,
                                checksum,
                                batch.get(i),
                                vectors == null ? null : vectors.get(i)));
            }
        }
        var commitData = new HashMap<String, String>();
        commitData.put(META_SCHEMA, SCHEMA_VERSION);
        if (embedder != null) {
            commitData.put(META_MODEL, embedder.modelId());
            commitData.put(META_DIMS, Integer.toString(embedder.dimensions()));
        }
        writer.setLiveCommitData(commitData.entrySet());
        writer.commit();
        writes.incrementAndGet();
    }

    private Document toDocument(String sourcePath, String checksum, Chunk chunk, float[] vector) {
        var doc = new Document();
        doc.add(new StringField("path", sourcePath, Field.Store.YES));
        doc.add(new StringField("heading", chunk.heading(), Field.Store.YES));
        doc.add(new StringField("checksum", checksum, Field.Store.YES));
        doc.add(new TextField("text", chunk.text(), Field.Store.YES));
        doc.add(new StringField("line", Integer.toString(chunk.startLine()), Field.Store.YES));
        if (!chunk.heading().isEmpty()) {
            doc.add(new TextField("heading_text", chunk.heading(), Field.Store.NO));
        }
        if (!chunk.frontmatter().isEmpty()) {
            try {
                doc.add(new StoredField("frontmatter", SocketProtocol.JSON.writeValueAsString(chunk.frontmatter())));
            } catch (JacksonException e) {
                LOG.atWarn()
                        .setMessage("frontmatter is not serializable, dropping for {}: {}")
                        .addArgument(sourcePath)
                        .addArgument(chunk.heading())
                        .setCause(e)
                        .log();
            }
        }
        if (vector != null) {
            doc.add(new KnnFloatVectorField("embedding", vector, VectorSimilarityFunction.COSINE));
        }
        return doc;
    }

    /** Headings carry retrieval signal, so vectors cover heading plus body. */
    private static String embeddableText(Chunk chunk) {
        return chunk.heading().isEmpty() ? chunk.text() : chunk.heading() + "\n" + chunk.text();
    }

    @Override
    public boolean embeddingStale(Embedder current) {
        if (current == null) {
            return false;
        }
        var meta = commitMetadata();
        return !current.modelId().equals(meta.get(META_MODEL))
                || !Integer.toString(current.dimensions()).equals(meta.get(META_DIMS));
    }

    @Override
    public boolean schemaStale() {
        return !SCHEMA_VERSION.equals(commitMetadata().get(META_SCHEMA));
    }

    private void checkDrift() throws IOException {
        if (embedder == null) {
            return;
        }
        var meta = commitMetadata();
        var stored = meta.get(META_MODEL);
        if (stored != null
                && (!stored.equals(embedder.modelId())
                        || !Integer.toString(embedder.dimensions()).equals(meta.get(META_DIMS)))) {
            throw new IOException(
                    "embedding model drift in '"
                            + indexName
                            + "': index holds "
                            + stored
                            + ", current is "
                            + embedder.modelId()
                            + " — delete the index directory and reindex");
        }
    }

    private Map<String, String> commitMetadata() {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            var userData = reader.getIndexCommit().getUserData();
            return userData != null ? userData : Map.of();
        } catch (IndexNotFoundException e) {
            return Map.of();
        } catch (IOException e) {
            return Map.of();
        }
    }

    /**
     * {@code IndexReader.document(int)} returns stored fields by raw doc ID
     * regardless of deletion status: a term-deleted document keeps its stale
     * stored fields readable until a future merge physically drops it. Every
     * per-leaf scan here must skip non-live doc IDs, or a file edited once
     * would read back its pre-edit checksum/path forever and never converge
     * on the freshly written value.
     */
    @Override
    public Map<String, String> knownChecksums() throws IOException {
        var known = new HashMap<String, String>();
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            for (LeafReaderContext ctx : reader.leaves()) {
                var leaf = ctx.reader();
                var liveDocs = leaf.getLiveDocs();
                var storedFields = leaf.storedFields();
                for (int i = 0; i < leaf.maxDoc(); i++) {
                    if (liveDocs != null && !liveDocs.get(i)) {
                        continue;
                    }
                    var doc = storedFields.document(i);
                    if (doc != null) {
                        known.putIfAbsent(doc.get("path"), doc.get("checksum"));
                    }
                }
            }
        }
        return known;
    }

    @Override
    public long writeCount() {
        return writes.get();
    }

    @Override
    public Set<String> listPaths() throws IOException {
        var paths = new HashSet<String>();
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            for (LeafReaderContext ctx : reader.leaves()) {
                var leaf = ctx.reader();
                var liveDocs = leaf.getLiveDocs();
                var storedFields = leaf.storedFields();
                for (int i = 0; i < leaf.maxDoc(); i++) {
                    if (liveDocs != null && !liveDocs.get(i)) {
                        continue;
                    }
                    var doc = storedFields.document(i);
                    if (doc != null) {
                        paths.add(doc.get("path"));
                    }
                }
            }
        }
        return paths;
    }

    @Override
    public int count() throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            return reader.numDocs();
        }
    }

    @Override
    public List<Hit> search(String query, int topN) throws IOException {
        return search(query, topN, SearchBackend.Syntax.TEXT);
    }

    @Override
    public List<Hit> search(String query, int topN, SearchBackend.Syntax syntax) throws IOException {
        return search(query, topN, syntax, null);
    }

    @Override
    public List<Hit> search(String query, int topN, SearchBackend.Syntax syntax, String pathPrefix)
            throws IOException {
        return search(query, topN, syntax, pathPrefix, false);
    }

    @Override
    public List<Hit> search(
            String query, int topN, SearchBackend.Syntax syntax, String pathPrefix, boolean fullText)
            throws IOException {
        Query filter = PathPrefix.toQuery(pathPrefix);
        if (syntax == SearchBackend.Syntax.VECTOR) {
            return searchVector(query, topN, filter);
        }
        if (syntax == SearchBackend.Syntax.HYBRID) {
            return searchHybrid(query, topN, filter, fullText);
        }
        return runQuery(buildTextQuery(query, syntax, filter), topN, fullText);
    }

    /** Escaped/boosted BM25 query for TEXT, raw Lucene syntax for RAW, filtered by {@code filter}. */
    private Query buildTextQuery(String query, SearchBackend.Syntax syntax, Query filter) {
        var effective = syntax == SearchBackend.Syntax.RAW ? query : QueryParser.escape(query);
        Query parsed;
        try {
            parsed =
                    syntax == SearchBackend.Syntax.RAW
                            ? new QueryParser("text", analyzer).parse(effective)
                            : new MultiFieldQueryParser(
                                            new String[] {"text", "heading_text"},
                                            analyzer,
                                            Map.of("text", 1.0f, "heading_text", HEADING_BOOST))
                                    .parse(effective);
        } catch (ParseException e) {
            throw new IllegalArgumentException("invalid query: " + query, e);
        }
        return filter == null
                ? parsed
                : new BooleanQuery.Builder()
                        .add(parsed, BooleanClause.Occur.MUST)
                        .add(filter, BooleanClause.Occur.FILTER)
                        .build();
    }

    /**
     * Fuses BM25 text ranking and cosine vector ranking via reciprocal rank
     * fusion (RRF) over Lucene's internal doc ids, computed within one
     * shared reader snapshot so both legs' ids are comparable. Silently
     * degrades to text-only ranking when no embedder is configured, or the
     * vector leg fails for any reason.
     */
    private List<Hit> searchHybrid(String query, int topN, Query filter, boolean fullText)
            throws IOException {
        Query textQuery = buildTextQuery(query, SearchBackend.Syntax.TEXT, filter);
        int candidates = Math.max(topN * RRF_CANDIDATE_MULTIPLIER, RRF_MIN_CANDIDATES);
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            var textTop = searcher.search(textQuery, candidates);
            var vectorDocs = new ScoreDoc[0];
            if (embedder != null) {
                try {
                    vectorDocs =
                            searcher.search(
                                            new KnnFloatVectorQuery(
                                                    "embedding", embedder.embed(query), candidates, filter),
                                            candidates)
                                    .scoreDocs;
                } catch (IOException | RuntimeException e) {
                    LOG.atDebug()
                            .setMessage("hybrid search: vector leg unavailable for '{}', degrading to text-only")
                            .addArgument(indexName)
                            .setCause(e)
                            .log();
                }
            }
            var fused = fuseRanks(textTop.scoreDocs, vectorDocs, topN);
            return toHits(searcher, fused, textQuery, fullText);
        }
    }

    private static TopDocs fuseRanks(ScoreDoc[] textDocs, ScoreDoc[] vectorDocs, int topN) {
        var scores = new LinkedHashMap<Integer, Double>();
        addRrfScores(scores, textDocs);
        addRrfScores(scores, vectorDocs);
        var ranked =
                scores.entrySet().stream()
                        .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                        .limit(topN)
                        .toList();
        var scoreDocs = new ScoreDoc[ranked.size()];
        for (int i = 0; i < ranked.size(); i++) {
            scoreDocs[i] = new ScoreDoc(ranked.get(i).getKey(), ranked.get(i).getValue().floatValue());
        }
        return new TopDocs(new TotalHits(scoreDocs.length, TotalHits.Relation.EQUAL_TO), scoreDocs);
    }

    private static void addRrfScores(Map<Integer, Double> scores, ScoreDoc[] docs) {
        for (int rank = 0; rank < docs.length; rank++) {
            scores.merge(docs[rank].doc, 1.0 / (RRF_K + rank + 1), Double::sum);
        }
    }

    /**
     * kNN lookup over the {@code embedding} field. Scores derive from
     * cosine similarity — not comparable to BM25 text scores. Indexes
     * written without an embedder simply return no hits. {@code filter},
     * when non-null, pre-filters the candidate set before the k-nearest
     * computation runs — not a post-filter over an unfiltered top-k.
     */
    private List<Hit> searchVector(String query, int topN, Query filter) throws IOException {
        if (embedder == null) {
            throw new IOException("vector search unavailable: index '" + indexName + "' has no embedder");
        }
        // Vector hits have no term match to highlight around: always full text.
        return runQuery(
                new KnnFloatVectorQuery("embedding", embedder.embed(query), topN, filter), topN, true);
    }

    private List<Hit> runQuery(Query parsed, int topN, boolean fullText) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            var top = searcher.search(parsed, topN);
            return toHits(searcher, top, parsed, fullText);
        }
    }

    /**
     * Converts already-executed {@code top} into {@link Hit}s against the
     * given {@code searcher}, snippeting around {@code highlightQuery}'s
     * matches unless {@code fullText}. {@code highlightQuery} need not be
     * the exact query that produced {@code top} (a fused hybrid result set
     * highlights around its BM25 leg only) — docs with no term match under
     * it simply get no snippet and fall back to the full chunk.
     */
    private List<Hit> toHits(IndexSearcher searcher, TopDocs top, Query highlightQuery, boolean fullText)
            throws IOException {
        var hits = new ArrayList<Hit>();
        var docs = new Document[top.scoreDocs.length];
        var texts = new String[top.scoreDocs.length];
        for (int i = 0; i < top.scoreDocs.length; i++) {
            docs[i] = searcher.storedFields().document(top.scoreDocs[i].doc);
            texts[i] = docs[i].get("text");
        }
        String[] snippets = null;
        if (!fullText && needsSnippet(texts)) {
            // Private-use-area sentinels, not "**": chunk text is markdown, and
            // real "**bold**" source would otherwise be indistinguishable from
            // an actual query-term highlight below.
            var highlighter =
                    UnifiedHighlighter.builder(searcher, analyzer)
                            .withFormatter(
                                    new DefaultPassageFormatter(
                                            HIGHLIGHT_START, HIGHLIGHT_END, " ... ", false))
                            .build();
            snippets = highlighter.highlight("text", highlightQuery, top, 1);
        }
        for (int i = 0; i < top.scoreDocs.length; i++) {
            var fullChunkText = texts[i];
            // No highlight sentinel means the highlighter found nothing to mark
            // in "text" (e.g. the query matched only via unstored heading_text,
            // or a fused hybrid hit that the BM25 leg never actually matched) —
            // an unrelated truncated excerpt helps no one, so fall back to the
            // full chunk rather than call it a snippet.
            var useSnippet =
                    snippets != null
                            && fullChunkText != null
                            && fullChunkText.length() > SNIPPET_THRESHOLD_CHARS
                            && snippets[i] != null
                            && snippets[i].contains(HIGHLIGHT_START);
            hits.add(
                    new Hit(
                            indexName,
                            docs[i].get("path"),
                            splitHeading(docs[i].get("heading")),
                            useSnippet ? capToWindow(snippets[i]) : fullChunkText,
                            top.scoreDocs[i].score,
                            storedLine(docs[i]),
                            useSnippet,
                            frontmatterOf(docs[i])));
        }
        return hits;
    }

    private static boolean needsSnippet(String[] texts) {
        for (var text : texts) {
            if (text != null && text.length() > SNIPPET_THRESHOLD_CHARS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hard char cap independent of the highlighter's own passage sizing —
     * the bounded-output guarantee lives here. Centers on the first
     * highlight sentinel so the matched term stays in the window, then
     * swaps the sentinels for the markdown-bold marker actually returned.
     */
    private static String capToWindow(String snippet) {
        String windowed;
        if (snippet.length() <= SNIPPET_WINDOW_CHARS) {
            windowed = snippet;
        } else {
            var mark = snippet.indexOf(HIGHLIGHT_START);
            var center = mark >= 0 ? mark : snippet.length() / 2;
            var half = SNIPPET_WINDOW_CHARS / 2;
            var start = Math.max(0, center - half);
            var end = Math.min(snippet.length(), start + SNIPPET_WINDOW_CHARS);
            var prefix = start > 0 ? "... " : "";
            var suffix = end < snippet.length() ? " ..." : "";
            windowed = prefix + snippet.substring(start, end) + suffix;
        }
        return windowed.replace(HIGHLIGHT_START, "**").replace(HIGHLIGHT_END, "**");
    }

    @Override
    public void close() throws IOException {
        try {
            writer.close();
        } finally {
            analyzer.close();
            directory.close();
        }
    }

    private static List<String> splitHeading(String heading) {
        if (heading == null || heading.isEmpty()) {
            return List.of();
        }
        return List.of(heading.split(" / "));
    }

    /** Stored line, defaulting to unknown for pre-line indexes. */
    private static int storedLine(Document doc) {
        var line = doc.get("line");
        if (line == null) {
            return 0;
        }
        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Stored frontmatter, defaulting to empty for chunks/files without any. */
    private static Map<String, Object> frontmatterOf(Document doc) {
        var json = doc.get("frontmatter");
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return SocketProtocol.JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            LOG.atWarn()
                    .setMessage("stored frontmatter is not valid JSON, dropping")
                    .setCause(e)
                    .log();
            return Map.of();
        }
    }
}

package org.github.lscoughlin.clarity.daemon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.github.lscoughlin.clarity.parser.Chunk;

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
    private static final String META_MODEL = "embedding_model";
    private static final String META_DIMS = "embedding_dims";
    private static final String META_SCHEMA = "schema_version";
    /**
     * Index schema generation: 1 adds the stored {@code line} field, 2
     * adds the analyzed {@code heading_text} field.
     */
    private static final String SCHEMA_VERSION = "2";

    /** Heading matches rank above equal body matches in TEXT mode. */
    private static final float HEADING_BOOST = 2.0f;

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

    @Override
    public Map<String, String> knownChecksums() throws IOException {
        var known = new HashMap<String, String>();
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            for (int i = 0; i < reader.maxDoc(); i++) {
                var doc = reader.storedFields().document(i);
                if (doc != null) {
                    known.putIfAbsent(doc.get("path"), doc.get("checksum"));
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
            for (int i = 0; i < reader.maxDoc(); i++) {
                var doc = reader.storedFields().document(i);
                if (doc != null) {
                    paths.add(doc.get("path"));
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
        if (syntax == SearchBackend.Syntax.VECTOR) {
            return searchVector(query, topN);
        }
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
        return runQuery(parsed, topN);
    }

    /**
     * kNN lookup over the {@code embedding} field. Scores derive from
     * cosine similarity — not comparable to BM25 text scores. Indexes
     * written without an embedder simply return no hits.
     */
    private List<Hit> searchVector(String query, int topN) throws IOException {
        if (embedder == null) {
            throw new IOException("vector search unavailable: index '" + indexName + "' has no embedder");
        }
        return runQuery(
                new KnnFloatVectorQuery("embedding", embedder.embed(query), topN), topN);
    }

    private List<Hit> runQuery(Query parsed, int topN) throws IOException {
        var hits = new ArrayList<Hit>();
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            var searcher = new IndexSearcher(reader);
            var top = searcher.search(parsed, topN);
            for (ScoreDoc scoreDoc : top.scoreDocs) {
                var doc = searcher.storedFields().document(scoreDoc.doc);
                hits.add(
                        new Hit(
                                indexName,
                                doc.get("path"),
                                splitHeading(doc.get("heading")),
                                doc.get("text"),
                                scoreDoc.score,
                                storedLine(doc)));
            }
        }
        return hits;
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
}

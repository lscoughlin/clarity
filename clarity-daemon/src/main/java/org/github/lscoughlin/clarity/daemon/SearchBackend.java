package org.github.lscoughlin.clarity.daemon;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.github.lscoughlin.clarity.parser.Chunk;

/**
 * The narrow index/query port. The first implementation is Lucene; a future
 * vector backend implements this same interface. All paths are
 * project-relative display strings (e.g. {@code doc/guide.md}).
 */
public interface SearchBackend extends Closeable {

    /** Default maximum hits when the caller does not specify a limit. */
    int DEFAULT_TOP_N = 5;

    /**
     * Query interpretation: plain text (escaped), raw Lucene syntax, or
     * semantic vector search (the query is embedded and matched by
     * cosine similarity; requires an index written with an embedder).
     * Vector scores derive from cosine similarity and are not
     * comparable to BM25 text scores.
     */
    enum Syntax {
        TEXT,
        RAW,
        VECTOR
    }

    /**
     * Replaces every document for {@code sourcePath} with the given chunks
     * carrying {@code checksum}; an empty chunk list deletes them.
     */
    void updateDocuments(String sourcePath, String checksum, List<Chunk> chunks) throws IOException;

    /** Known path-to-checksum map, read from the stored checksum fields. */
    Map<String, String> knownChecksums() throws IOException;

    /** Number of document updates performed; operational visibility and tests. */
    long writeCount() throws IOException;

    /** All source paths currently present in the index. */
    Set<String> listPaths() throws IOException;

    /** Number of documents currently in the index. */
    int count() throws IOException;

    /** Top-{@code topN} matches, possibly empty; plain-text query. */
    List<Hit> search(String query, int topN) throws IOException;

    /** Top-{@code topN} matches with explicit query interpretation. */
    default List<Hit> search(String query, int topN, Syntax syntax) throws IOException {
        return search(query, topN);
    }

    /**
     * Top-{@code topN} matches as above; {@code fullText} true returns each
     * hit's complete chunk, false (the default) returns a bounded,
     * highlighted snippet for chunks above the backend's size threshold.
     * Backends that don't implement snippeting simply ignore it.
     */
    default List<Hit> search(String query, int topN, Syntax syntax, boolean fullText) throws IOException {
        return search(query, topN, syntax);
    }

    /**
     * True when this index's stored vectors (if any) were written by a
     * different embedding model than {@code embedder}, or predate
     * vectors entirely — the caller should re-embed everything (pass
     * no known checksums). False when {@code embedder} is null.
     */
    default boolean embeddingStale(Embedder embedder) {
        return false;
    }

    /**
     * True when this index predates the current stored-field schema
     * (commit {@code schema_version} marker absent or older) — the
     * caller should rewrite everything (pass no known checksums) so
     * new stored fields backfill.
     */
    default boolean schemaStale() {
        return false;
    }
}

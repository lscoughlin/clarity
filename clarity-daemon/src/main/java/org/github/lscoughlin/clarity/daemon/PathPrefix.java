package org.github.lscoughlin.clarity.daemon;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;

/**
 * Translates a query-time {@code path_prefix} argument into a Lucene
 * {@link Query} over the stored {@code path} term field. A prefix matches
 * that path exactly or anything nested under it as a directory —
 * {@code doc/deploy} matches both {@code doc/deploy} and
 * {@code doc/deploy/setup.md}, but not {@code doc/deployment.md}.
 */
final class PathPrefix {
    private PathPrefix() {}

    /** Null/blank {@code prefix} means "no filter" and returns null. */
    static Query toQuery(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return null;
        }
        if (prefix.startsWith("/")) {
            throw new IllegalArgumentException("path_prefix must be relative: " + prefix);
        }
        // Stored paths never carry a trailing separator, so a caller-supplied
        // one (e.g. "doc/deploy/") must be dropped or both clauses miss.
        String normalized = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term("path", normalized)), BooleanClause.Occur.SHOULD)
                .add(new PrefixQuery(new Term("path", normalized + "/")), BooleanClause.Occur.SHOULD)
                .build();
    }
}

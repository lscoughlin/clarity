package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathPrefixTest {

    @Test
    void nullOrBlankMeansNoFilter() {
        assertNull(PathPrefix.toQuery(null));
        assertNull(PathPrefix.toQuery(""));
        assertNull(PathPrefix.toQuery("   "));
    }

    @Test
    void leadingSlashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PathPrefix.toQuery("/doc"));
    }

    @Test
    void matchesExactPathAndSubtreeButNotFalsePrefix(@TempDir Path base) throws IOException {
        try (FSDirectory dir = FSDirectory.open(base);
                IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig())) {
            addPath(writer, "doc/deploy");
            addPath(writer, "doc/deploy/setup.md");
            addPath(writer, "doc/deployment.md");
            addPath(writer, "doc/control/setup.md");
            writer.commit();

            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                var searcher = new IndexSearcher(reader);
                var query = PathPrefix.toQuery("doc/deploy");
                var hits = searcher.search(query, 10);
                assertEquals(2, hits.scoreDocs.length);
                for (var scoreDoc : hits.scoreDocs) {
                    var path = searcher.storedFields().document(scoreDoc.doc).get("path");
                    assertEquals(true, path.equals("doc/deploy") || path.equals("doc/deploy/setup.md"));
                }

                // A caller-supplied trailing slash must match the same set,
                // not silently miss both SHOULD clauses.
                var trailingSlashHits = searcher.search(PathPrefix.toQuery("doc/deploy/"), 10);
                assertEquals(2, trailingSlashHits.scoreDocs.length);
            }
        }
    }

    private static void addPath(IndexWriter writer, String path) throws IOException {
        var doc = new Document();
        doc.add(new StringField("path", path, org.apache.lucene.document.Field.Store.YES));
        writer.addDocument(doc);
    }
}

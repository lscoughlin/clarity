package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.NoMergePolicy;
import org.github.lscoughlin.clarity.parser.Chunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code IndexReader.storedFields().document(int)} returns stored fields by
 * raw doc ID regardless of deletion status: a term-deleted document keeps
 * its stale stored fields readable until a future segment merge physically
 * drops it. {@link LuceneBackend#knownChecksums()} and {@link
 * LuceneBackend#listPaths()} must skip non-live doc IDs, or a file edited
 * once reads back its pre-edit checksum forever and every later reindex
 * reparses and re-embeds it again.
 *
 * <p>Reproducing this needs the edited file's pre-edit chunks to sit in a
 * segment that also holds another file's still-live chunks — otherwise the
 * segment goes 100% deleted and {@code IndexWriter} drops it outright
 * (verified separately: with each file committed in isolation, as {@link
 * LuceneBackend#updateDocuments} always does, an edited file's lone-doc
 * segment vanishes on the spot and the bug never surfaces). In a long-lived
 * index that "also holds another file's chunks" state is the norm — months
 * of background merges consolidate unrelated files into shared segments —
 * so this test recreates it directly: index two files, {@code forceMerge}
 * them into one shared segment, pin the merge policy so nothing folds that
 * segment away afterward, then edit one of the two files.
 */
class LuceneBackendChecksumTest {

    private static Chunk chunk(String path, String text) {
        return new Chunk(path, List.of(), text, 1);
    }

    private static IndexWriter writerOf(LuceneBackend backend) throws ReflectiveOperationException {
        Field field = LuceneBackend.class.getDeclaredField("writer");
        field.setAccessible(true);
        return (IndexWriter) field.get(backend);
    }

    @Test
    void knownChecksumsReflectsLatestEditNotStaleSharedSegmentGeneration(@TempDir Path base)
            throws Exception {
        Path indexPath = base.resolve("index");
        try (LuceneBackend backend = new LuceneBackend("docs", indexPath)) {
            backend.updateDocuments("doc/a.md", "checksum-v1", List.of(chunk("doc/a.md", "v1 text")));
            backend.updateDocuments(
                    "doc/b.md", "checksum-stable", List.of(chunk("doc/b.md", "stable text")));

            IndexWriter writer = writerOf(backend);
            writer.forceMerge(1);
            writer.commit();
            writer.getConfig().setMergePolicy(NoMergePolicy.INSTANCE);

            backend.updateDocuments("doc/a.md", "checksum-v2", List.of(chunk("doc/a.md", "v2 text")));

            assertEquals(
                    Map.of("doc/a.md", "checksum-v2", "doc/b.md", "checksum-stable"),
                    backend.knownChecksums());
            assertEquals(Set.of("doc/a.md", "doc/b.md"), backend.listPaths());
        }
    }
}

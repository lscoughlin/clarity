package org.github.lscoughlin.clarity.daemon;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.github.lscoughlin.clarity.parser.ClarityConfig;
import org.github.lscoughlin.clarity.parser.GlobResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns one {@link SearchBackend} per named index and drives the
 * config-globs to parser to chunks to index pipeline. Reindexing is
 * idempotent: path-keyed updates refresh changed files, and documents
 * whose source file no longer matches any include glob are removed.
 */
public final class IndexService implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(IndexService.class);

    /** Source file extension to {@code source.kind} comment syntax. */
    private static final Map<String, String> EXTENSION_KINDS =
            Map.of("java", "java", "pas", "pascal", "pp", "pascal", "go", "go", "rs", "rust",
                    "swift", "swift");

    private final Path baseDir;
    private final ClarityConfig config;
    private final Embedder embedder;
    private final Map<String, SearchBackend> backends = new HashMap<>();

    private IndexService(
            Path baseDir,
            ClarityConfig config,
            Embedder embedder,
            Map<String, SearchBackend> backends) {
        this.baseDir = baseDir;
        this.config = config;
        this.embedder = embedder;
        this.backends.putAll(backends);
    }

    public static IndexService open(Path baseDir) throws IOException {
        return open(baseDir, ConfigLoader.load(baseDir));
    }

    static IndexService open(Path baseDir, ClarityConfig config) throws IOException {
        return open(baseDir, config, null);
    }

    /**
     * Opens with an embedder for vector search; a null embedder keeps
     * text-only behavior. A non-null embedder on an index that
     * predates vectors triggers a full re-embed on the next pass.
     */
    static IndexService open(Path baseDir, ClarityConfig config, Embedder embedder)
            throws IOException {
        var backends = new HashMap<String, SearchBackend>();
        try {
            for (Map.Entry<String, ClarityConfig.IndexConfig> entry : config.index().entrySet()) {
                var indexPath = Path.of(entry.getValue().indexPath());
                if (!indexPath.isAbsolute()) {
                    indexPath = baseDir.resolve(indexPath);
                }
                Files.createDirectories(indexPath);
                backends.put(
                        entry.getKey(), new LuceneBackend(entry.getKey(), indexPath, embedder));
            }
        } catch (IOException | RuntimeException e) {
            closeAll(backends);
            throw e;
        }
        return new IndexService(baseDir, config, embedder, backends);
    }

    /** Reindexes every named entry; safe to call repeatedly. */
    public Map<String, ReindexStats> reindex() throws IOException {
        var stats = new LinkedHashMap<String, ReindexStats>();
        for (Map.Entry<String, ClarityConfig.IndexConfig> entry : config.index().entrySet()) {
            stats.put(entry.getKey(), reindexOne(entry.getKey(), entry.getValue()));
        }
        return stats;
    }

    /** Reindexes one named entry; throws if the name is not configured. */
    public ReindexStats reindexIndex(String name) throws IOException {
        ClarityConfig.IndexConfig index = config.index().get(name);
        if (index == null) {
            throw new IllegalArgumentException("unknown index: " + name);
        }
        return reindexOne(name, index);
    }

    public List<Hit> search(String indexName, String query, int topN) throws IOException {
        return search(indexName, query, topN, SearchBackend.Syntax.TEXT);
    }

    public List<Hit> search(String indexName, String query, int topN, SearchBackend.Syntax syntax)
            throws IOException {
        return search(indexName, query, topN, syntax, null);
    }

    public List<Hit> search(
            String indexName, String query, int topN, SearchBackend.Syntax syntax, String pathPrefix)
            throws IOException {
        return search(indexName, query, topN, syntax, pathPrefix, false);
    }

    public List<Hit> search(
            String indexName,
            String query,
            int topN,
            SearchBackend.Syntax syntax,
            String pathPrefix,
            boolean fullText)
            throws IOException {
        SearchBackend backend = backends.get(indexName);
        if (backend == null) {
            throw new IllegalArgumentException("unknown index: " + indexName);
        }
        return backend.search(query, topN, syntax, pathPrefix, fullText);
    }

    /** Document count per named index, for logging and tests. */
    public Map<String, Integer> counts() throws IOException {
        var counts = new HashMap<String, Integer>();
        for (var entry : backends.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().count());
        }
        return counts;
    }

    /** Document updates performed per named index; proves skip behavior. */
    public Map<String, Long> writeCounts() throws IOException {
        var counts = new HashMap<String, Long>();
        for (var entry : backends.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().writeCount());
        }
        return counts;
    }

    ClarityConfig config() {
        return config;
    }

    @Override
    public void close() throws IOException {
        closeAll(backends);
    }

    private ReindexStats reindexOne(String name, ClarityConfig.IndexConfig index)
            throws IOException {
        SearchBackend backend = backends.get(name);
        // Pre-vector indexes skip nothing: every file needs embedding.
        // Pre-schema indexes likewise rewrite fully so new stored fields backfill.
        boolean fullRewrite = backend.embeddingStale(embedder) || backend.schemaStale();
        Map<String, String> known = backend.knownChecksums();
        var jobs = collectJobs(index);
        var parsed = FileParser.parseAll(jobs, fullRewrite ? Map.of() : known);

        var seen = new HashSet<String>();
        var added = 0;
        var changed = 0;
        var skipped = 0;
        for (FileParser.ParsedFile file : parsed) {
            seen.add(file.relativePath());
            if (file.skipped()) {
                skipped++;
                continue;
            }
            backend.updateDocuments(file.relativePath(), file.checksum(), file.chunks());
            if (known.containsKey(file.relativePath())) {
                changed++;
            } else {
                added++;
            }
        }

        var removed = 0;
        for (var stale : backend.listPaths()) {
            if (!seen.contains(stale)) {
                backend.updateDocuments(stale, "", List.of());
                removed++;
            }
        }
        LOG.atInfo()
                .setMessage("reindexed '{}': {} live sources ({} unchanged), {} removed")
                .addArgument(name)
                .addArgument(seen.size())
                .addArgument(skipped)
                .addArgument(removed)
                .log();
        return new ReindexStats(added, changed, removed, backend.count());
    }

    private List<FileParser.FileJob> collectJobs(ClarityConfig.IndexConfig index) {
        var jobs = new ArrayList<FileParser.FileJob>();
        for (var file : GlobResolver.resolve(baseDir, index.markdown())) {
            jobs.add(new FileParser.FileJob(file, relative(file), FileParser.Category.MARKDOWN, ""));
        }
        for (var file : GlobResolver.resolve(baseDir, index.yaml())) {
            jobs.add(new FileParser.FileJob(file, relative(file), FileParser.Category.YAML, ""));
        }
        ClarityConfig.SourceConfig source = index.source();
        if (source != null) {
            var kinds = Set.copyOf(source.kind());
            for (var file : GlobResolver.resolve(baseDir, source.include())) {
                var kind = EXTENSION_KINDS.get(extension(file));
                if (kind == null || !kinds.contains(kind)) {
                    continue;
                }
                jobs.add(
                        new FileParser.FileJob(
                                file, relative(file), FileParser.Category.SOURCE, kind));
            }
        }
        return jobs;
    }

    private String relative(Path file) {
        return baseDir.relativize(file).toString();
    }

    private static String extension(Path file) {
        var name = file.getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static void closeAll(Map<String, SearchBackend> backends) {
        var failures = new ArrayList<IOException>();
        for (var backend : backends.values()) {
            try {
                backend.close();
            } catch (IOException e) {
                failures.add(e);
            }
        }
        if (!failures.isEmpty()) {
            IOException first = failures.get(0);
            failures.stream().skip(1).forEach(first::addSuppressed);
            throw new UncheckedIOException(first);
        }
    }
}

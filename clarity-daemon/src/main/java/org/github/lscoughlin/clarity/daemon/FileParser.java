package org.github.lscoughlin.clarity.daemon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.github.lscoughlin.clarity.parser.Chunk;
import org.github.lscoughlin.clarity.parser.CommentExtractor;
import org.github.lscoughlin.clarity.parser.MarkdownChunker;
import org.github.lscoughlin.clarity.parser.YamlLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads, hashes, and parses source files. Each file is independent, so a
 * reindex pass forks one virtual thread per file; results come back in
 * sorted-path order for deterministic writes. Files whose SHA-256 matches
 * the known map are skipped without parsing.
 *
 * <p>Nothing mutable is shared between tasks: each parse call builds
 * its own commonmark instances, and the YAML/comment paths are pure
 * string functions. The determinism test pins this down.
 */
final class FileParser {
    private static final Logger LOG = LoggerFactory.getLogger(FileParser.class);

    /** One file to handle, with its parse category resolved. */
    record FileJob(Path file, String relativePath, Category category, String kind) {}

    enum Category {
        MARKDOWN,
        YAML,
        SOURCE
    }

    /** One file's outcome: parsed chunks, or a skip when unchanged. */
    record ParsedFile(String relativePath, String checksum, List<Chunk> chunks, boolean skipped) {}

    private FileParser() {}

    static List<ParsedFile> parseAll(List<FileJob> jobs, Map<String, String> known)
            throws IOException {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<ParsedFile>>(jobs.size());
        try {
            for (var job : jobs) {
                var knownChecksum = known.get(job.relativePath());
                futures.add(executor.submit(() -> parseOne(job, knownChecksum)));
            }
            var results = new ArrayList<ParsedFile>(futures.size());
            for (var future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    throw toIOException(cause != null ? cause : e);
                }
            }
            results.sort(Comparator.comparing(ParsedFile::relativePath));
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while parsing", e);
        } finally {
            executor.shutdownNow();
        }
    }

    private static ParsedFile parseOne(FileJob job, String knownChecksum) throws IOException {
        var bytes = Files.readAllBytes(job.file());
        var checksum = sha256(bytes);
        if (checksum.equals(knownChecksum)) {
            return new ParsedFile(job.relativePath(), checksum, List.of(), true);
        }
        var content = new String(bytes, StandardCharsets.UTF_8);
        List<Chunk> chunks =
                switch (job.category()) {
                    case MARKDOWN -> MarkdownChunker.chunk(job.relativePath(), content);
                    case YAML -> List.of(YamlLoader.load(job.relativePath(), content));
                    case SOURCE ->
                        CommentExtractor.extract(job.relativePath(), job.kind(), content);
                };
        LOG.atDebug()
                .setMessage("parsed {} ({} chunks)")
                .addArgument(job.relativePath())
                .addArgument(chunks.size())
                .log();
        return new ParsedFile(job.relativePath(), checksum, chunks, false);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK must provide SHA-256", e);
        }
    }

    private static IOException toIOException(Throwable t) {
        if (t instanceof IOException io) {
            return io;
        }
        return new IOException("file parsing failed", t);
    }
}

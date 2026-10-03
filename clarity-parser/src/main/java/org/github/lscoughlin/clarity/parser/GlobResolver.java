package org.github.lscoughlin.clarity.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Resolves Ant-style include globs (where {@code **} crosses directory
 * boundaries) against a base directory. Only relative patterns are
 * accepted; absolute patterns are rejected so an index can never escape
 * its project root by configuration.
 */
public final class GlobResolver {
    private GlobResolver() {}

    /**
     * Directory names never worth descending into while resolving includes:
     * VCS metadata, dependency trees, and build output. A pattern crossing
     * directories with {@code **} would otherwise walk these too — often
     * enormous (a large {@code node_modules}, a repo's whole {@code .git}
     * history), and {@code .git} in particular can churn underneath a
     * concurrent worktree operation and vanish mid-walk.
     */
    private static final Set<String> PRUNED_DIR_NAMES =
            Set.of(".git", "node_modules", "target", "build", ".cache");

    public static List<Path> resolve(Path baseDir, List<String> patterns) {
        var matchers = patterns.stream()
                .flatMap(pattern -> toMatchers(pattern).stream())
                .toList();
        var hits = new ArrayList<Path>();
        for (var root : walkRoots(baseDir, patterns)) {
            walk(root, baseDir, matchers, hits);
        }
        return hits.stream().distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private static void walk(Path root, Path baseDir, List<PathMatcher> matchers, List<Path> hits) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walkFileTree(
                    root,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            var name = dir.getFileName();
                            if (!dir.equals(root)
                                    && name != null
                                    && PRUNED_DIR_NAMES.contains(name.toString())) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile()) {
                                var relative = baseDir.relativize(file);
                                if (matchers.stream().anyMatch(m -> m.matches(relative))) {
                                    hits.add(file);
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            // Observed in practice: a build/worktree tree churning
                            // concurrently under a pruned-adjacent directory can
                            // delete a file between listing and stat. Skip it
                            // rather than aborting the whole reindex pass.
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk " + root, e);
        }
    }

    /**
     * One walk root per pattern's static (non-glob) leading path segments,
     * so e.g. {@code doc/**}{@code /*.md} only ever walks under {@code doc/}
     * instead of the whole project — and, combined with {@link
     * #PRUNED_DIR_NAMES}, never even looks at sibling directories like
     * {@code .git} or {@code node_modules}. Roots that are sub-paths of
     * another root are dropped so overlapping patterns don't walk the same
     * subtree twice. A pattern with no static prefix (e.g. {@code *.md})
     * falls back to walking {@code baseDir} itself.
     */
    private static List<Path> walkRoots(Path baseDir, List<String> patterns) {
        var roots =
                patterns.stream()
                        .map(pattern -> staticPrefix(baseDir, pattern))
                        .distinct()
                        .sorted(Comparator.comparingInt(Path::getNameCount))
                        .toList();
        var result = new ArrayList<Path>();
        for (var root : roots) {
            if (result.stream().noneMatch(root::startsWith)) {
                result.add(root);
            }
        }
        return result;
    }

    private static Path staticPrefix(Path baseDir, String pattern) {
        var prefix = baseDir;
        for (var segment : pattern.split("/")) {
            if (segment.isEmpty() || isGlobSegment(segment)) {
                break;
            }
            prefix = prefix.resolve(segment);
        }
        return prefix;
    }

    private static boolean isGlobSegment(String segment) {
        return segment.indexOf('*') >= 0
                || segment.indexOf('?') >= 0
                || segment.indexOf('[') >= 0
                || segment.indexOf('{') >= 0;
    }

    /*
     * Compiles a pattern plus, when it contains a doublestar-slash segment,
     * a collapsed variant with that segment removed. NIO doublestar requires
     * at least one path segment, while Ant-style doublestar (the documented
     * contract) matches zero or more, so a doc-tree markdown glob must also
     * match files directly inside doc.
     */
    private static List<PathMatcher> toMatchers(String pattern) {
        var path = Path.of(pattern);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("include globs must be relative: " + pattern);
        }
        var variants = new ArrayList<>(List.of(pattern));
        if (pattern.contains("**/")) {
            variants.add(pattern.replace("**/", ""));
        }
        return variants.stream()
                .distinct()
                .map(v -> FileSystems.getDefault().getPathMatcher("glob:" + v))
                .toList();
    }
}

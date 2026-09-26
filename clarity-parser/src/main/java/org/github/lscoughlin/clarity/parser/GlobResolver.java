package org.github.lscoughlin.clarity.parser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Resolves Ant-style include globs (where {@code **} crosses directory
 * boundaries) against a base directory. Only relative patterns are
 * accepted; absolute patterns are rejected so an index can never escape
 * its project root by configuration.
 */
public final class GlobResolver {
    private GlobResolver() {}

    public static List<Path> resolve(Path baseDir, List<String> patterns) {
        var matchers = patterns.stream()
                .flatMap(pattern -> toMatchers(pattern).stream())
                .toList();
        var hits = new ArrayList<Path>();
        try (Stream<Path> walk = Files.walk(baseDir)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                var relative = baseDir.relativize(file);
                if (matchers.stream().anyMatch(m -> m.matches(relative))) {
                    hits.add(file);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk " + baseDir, e);
        }
        hits.sort(Comparator.naturalOrder());
        return hits;
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

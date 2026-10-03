package org.github.lscoughlin.clarity.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GlobResolverTest {

    @Test
    void resolvesNestedMarkdownUnderStaticPrefix(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("doc/deploy"));
        Files.writeString(base.resolve("doc/top.md"), "top\n");
        Files.writeString(base.resolve("doc/deploy/nested.md"), "nested\n");
        Files.writeString(base.resolve("doc/skip.txt"), "nope\n");

        List<Path> hits = GlobResolver.resolve(base, List.of("doc/**/*.md"));

        assertEquals(
                List.of(base.resolve("doc/deploy/nested.md"), base.resolve("doc/top.md")), hits);
    }

    @Test
    void neverDescendsIntoNodeModulesOrGit(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("doc/sub/node_modules/pkg"));
        Files.writeString(base.resolve("doc/real.md"), "real\n");
        // A file that syntactically matches "doc/**/*.md" but sits inside a
        // node_modules tree — the same shape as the production incident this
        // guards against, where a build tool's node_modules under an
        // otherwise-indexed directory blew up an unpruned walk.
        Files.writeString(base.resolve("doc/sub/node_modules/pkg/decoy.md"), "decoy\n");

        List<Path> hits = GlobResolver.resolve(base, List.of("doc/**/*.md"));

        assertEquals(List.of(base.resolve("doc/real.md")), hits);
    }

    @Test
    void neverWalksOutsideEachPatternsStaticPrefix(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("doc"));
        Files.createDirectories(base.resolve("node_modules/pkg"));
        Files.writeString(base.resolve("doc/guide.md"), "guide\n");
        // Sits directly under a top-level sibling of "doc", not under any
        // pruned-directory name — only reachable at all if the walk root
        // were still the whole project instead of "doc/" itself.
        Files.writeString(base.resolve("node_modules/pkg/sibling.md"), "sibling\n");

        List<Path> hits = GlobResolver.resolve(base, List.of("doc/**/*.md"));

        assertEquals(List.of(base.resolve("doc/guide.md")), hits);
    }

    @Test
    void overlappingPrefixesDoNotDuplicateHits(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("doc/base/api-doc"));
        Files.writeString(base.resolve("doc/base/api-doc/spec.md"), "spec\n");
        Files.writeString(base.resolve("doc/other.md"), "other\n");

        List<Path> hits =
                GlobResolver.resolve(base, List.of("doc/**/*.md", "doc/base/api-doc/**/*.md"));

        assertEquals(
                List.of(base.resolve("doc/base/api-doc/spec.md"), base.resolve("doc/other.md")),
                hits);
    }

    @Test
    void missingStaticPrefixDirectoryYieldsNoHits(@TempDir Path base) {
        List<Path> hits = GlobResolver.resolve(base, List.of("doc/**/*.md"));
        assertTrue(hits.isEmpty());
    }

    @Test
    void patternWithoutStaticPrefixFallsBackToWholeTree(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("sub"));
        Files.writeString(base.resolve("root.md"), "root\n");
        Files.writeString(base.resolve("sub/nested.md"), "nested\n");

        List<Path> hits = GlobResolver.resolve(base, List.of("**/*.md"));

        assertEquals(List.of(base.resolve("root.md"), base.resolve("sub/nested.md")), hits);
    }

    @Test
    void absoluteGlobRejected(@TempDir Path base) {
        assertThrows(
                IllegalArgumentException.class,
                () -> GlobResolver.resolve(base, List.of("/etc/**/*.md")));
    }

    @Test
    void noPatternsYieldsNoHits(@TempDir Path base) throws IOException {
        Files.createDirectories(base.resolve("doc"));
        Files.writeString(base.resolve("doc/guide.md"), "guide\n");

        assertFalse(GlobResolver.resolve(base, List.of()).stream().findAny().isPresent());
    }
}

package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.github.lscoughlin.clarity.daemon.FileParser.Category;
import org.github.lscoughlin.clarity.daemon.FileParser.FileJob;
import org.github.lscoughlin.clarity.daemon.FileParser.ParsedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileParserTest {

    @Test
    void parallelParseIsDeterministic(@TempDir Path base) throws IOException {
        for (int i = 0; i < 20; i++) {
            Files.writeString(
                    base.resolve("d%02d.md".formatted(i)),
                    "# H%d\nBody %d with uniqueword%d.\n".formatted(i, i, i));
            Files.writeString(base.resolve("f%02d.yaml".formatted(i)), "key%d: value%d\n".formatted(i, i));
            Files.writeString(
                    base.resolve("C%02d.java".formatted(i)),
                    "// comment number %d\nclass C%d {}\n".formatted(i, i));
        }
        List<FileJob> jobs = collectJobs(base);

        List<ParsedFile> first = FileParser.parseAll(jobs, Map.of());
        assertEquals(60, first.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(first, FileParser.parseAll(jobs, Map.of()), "rep " + i);
        }

        List<String> rels = first.stream().map(ParsedFile::relativePath).toList();
        assertEquals(rels.stream().sorted().toList(), rels);

        ParsedFile md =
                first.stream()
                        .filter(p -> p.relativePath().equals("d00.md"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(1, md.chunks().size());
        assertEquals(List.of("H0"), md.chunks().get(0).headingPath());
        assertEquals(md.checksum(), FileParser.sha256(Files.readAllBytes(base.resolve("d00.md"))));
        assertTrue(first.stream().noneMatch(ParsedFile::skipped));
    }

    @Test
    void knownChecksumsSkipParsing(@TempDir Path base) throws IOException {
        Path md = base.resolve("guide.md");
        Files.writeString(md, "# Guide\nBody.\n");
        List<FileJob> jobs =
                List.of(new FileJob(md, "guide.md", Category.MARKDOWN, ""));

        List<ParsedFile> first = FileParser.parseAll(jobs, Map.of());
        assertEquals(1, first.get(0).chunks().size());

        Map<String, String> known = Map.of("guide.md", first.get(0).checksum());
        List<ParsedFile> second = FileParser.parseAll(jobs, known);
        assertTrue(second.get(0).skipped());
        assertTrue(second.get(0).chunks().isEmpty());
    }

    private static List<FileJob> collectJobs(Path base) throws IOException {
        List<FileJob> jobs = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = base.relativize(file).toString();
                String name = file.getFileName().toString();
                if (name.endsWith(".md")) {
                    jobs.add(new FileJob(file, rel, Category.MARKDOWN, ""));
                } else if (name.endsWith(".yaml")) {
                    jobs.add(new FileJob(file, rel, Category.YAML, ""));
                } else {
                    jobs.add(new FileJob(file, rel, Category.SOURCE, "java"));
                }
            }
        }
        return jobs;
    }
}

package org.github.lscoughlin.clarity.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Splits a Markdown document into one {@link Chunk} per heading section,
 * per the project contract. A leading YAML frontmatter block becomes its
 * own {@code frontmatter} chunk and is excluded from the body. Text
 * before the first heading becomes a single leading chunk with an empty
 * heading path. A heading with no body still produces a chunk so the
 * section title itself stays searchable. When the frontmatter block
 * parses as a YAML mapping, every chunk from the file — not just the
 * {@code frontmatter} chunk itself — carries it as structured metadata.
 */
public final class MarkdownChunker {
    private static final Logger LOG = LoggerFactory.getLogger(MarkdownChunker.class);
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private MarkdownChunker() {}

    public static List<Chunk> chunk(String sourcePath, String content) {
        var chunks = new ArrayList<Chunk>();
        var bodyText = content;
        var lineOffset = 0;
        Frontmatter frontmatter = splitFrontmatter(content);
        Map<String, Object> parsedFrontmatter = Map.of();
        if (frontmatter != null) {
            if (!frontmatter.text().isEmpty()) {
                parsedFrontmatter = parseFrontmatter(sourcePath, frontmatter.text());
                chunks.add(
                        new Chunk(
                                sourcePath,
                                List.of("frontmatter"),
                                frontmatter.text(),
                                1,
                                parsedFrontmatter));
            }
            bodyText = frontmatter.rest();
            lineOffset = frontmatter.consumedLines();
        }

        Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
        TextContentRenderer renderer = TextContentRenderer.builder().build();
        Node document = parser.parse(bodyText);

        var stack = new ArrayList<HeadingRef>();
        var body = new StringBuilder();
        var seenHeading = false;
        var sectionStart = 1 + lineOffset;

        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Heading heading) {
                flush(chunks, sourcePath, stack, body, seenHeading, sectionStart, parsedFrontmatter);
                body.setLength(0);
                var title = renderer.render(heading).strip();
                while (!stack.isEmpty() && stack.getLast().level() >= heading.getLevel()) {
                    stack.removeLast();
                }
                stack.add(new HeadingRef(heading.getLevel(), title));
                sectionStart = headingLine(heading, lineOffset);
                seenHeading = true;
            } else {
                var text = renderer.render(child).strip();
                if (!text.isEmpty()) {
                    if (body.length() > 0) {
                        body.append("\n\n");
                    }
                    body.append(text);
                }
            }
        }
        flush(chunks, sourcePath, stack, body, seenHeading, sectionStart, parsedFrontmatter);
        return chunks;
    }

    /**
     * Parses a frontmatter block as YAML; degrades to an empty map (never
     * throws) on malformed YAML or a non-mapping root, so unparsable
     * frontmatter never breaks chunking.
     */
    private static Map<String, Object> parseFrontmatter(String sourcePath, String text) {
        try {
            return YAML.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            LOG.atDebug()
                    .setMessage("frontmatter is not a YAML mapping, ignoring: {}")
                    .addArgument(sourcePath)
                    .setCause(e)
                    .log();
            return Map.of();
        }
    }

    /** 1-based line where {@code heading} starts in the original file. */
    private static int headingLine(Heading heading, int lineOffset) {
        var spans = heading.getSourceSpans();
        if (spans == null || spans.isEmpty()) {
            return 1 + lineOffset;
        }
        return spans.get(0).getLineIndex() + 1 + lineOffset;
    }

    private static void flush(
            List<Chunk> chunks,
            String sourcePath,
            List<HeadingRef> stack,
            StringBuilder body,
            boolean seenHeading,
            int startLine,
            Map<String, Object> frontmatter) {
        // Skip a blank preamble and blank text before the first heading;
        // every heading section is emitted even when its body is empty.
        if (body.length() == 0 && (!seenHeading || stack.isEmpty())) {
            return;
        }
        var headings = stack.stream().map(HeadingRef::title).toList();
        chunks.add(new Chunk(sourcePath, headings, body.toString(), startLine, frontmatter));
    }

    private record HeadingRef(int level, String title) {}

    /**
     * A leading {@code ---} block with its inner text, remaining body,
     * and consumed line count (for chunk line numbers). Only a block
     * starting on the very first line with a closing {@code ---} line
     * qualifies; anything else (unclosed, mid-document) is body text.
     */
    record Frontmatter(String text, String rest, int consumedLines) {}

    static Frontmatter splitFrontmatter(String content) {
        var lines = content.lines().toList();
        if (lines.isEmpty() || !lines.get(0).strip().equals("---")) {
            return null;
        }
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals("---")) {
                var text = String.join("\n", lines.subList(1, i)).strip();
                var rest = String.join("\n", lines.subList(i + 1, lines.size()));
                return new Frontmatter(text, rest, i + 1);
            }
        }
        return null;
    }
}

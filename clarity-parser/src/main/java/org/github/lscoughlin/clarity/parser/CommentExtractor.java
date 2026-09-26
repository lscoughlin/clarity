package org.github.lscoughlin.clarity.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Extracts comments — and only comments — from source files, per the
 * project contract: code tokens must never enter the index. Each maximal
 * run of adjacent line comments and each block comment becomes one
 * {@link Chunk} with an empty heading path.
 *
 * <p>Two comment families are supported, selected by the
 * {@code source.kind} config value: C-style (line {@code //} and block
 * slash-star ... star-slash) for {@code java}, {@code go}, {@code rust} and
 * {@code swift}, and Pascal-style ({@code //}, {@code { }},
 * {@code (* *)}) for {@code pascal}. String and character literals are
 * skipped so comment markers inside them are not misread.
 *
 * <p>Each chunk records the 1-based line where its comment (or comment
 * run) starts, so search hits navigate to the right place.
 */
public final class CommentExtractor {
    private static final Set<String> C_STYLE = Set.of("java", "go", "rust", "swift");

    private CommentExtractor() {}

    public static List<Chunk> extract(String sourcePath, String kind, String content) {
        var normalized = kind.toLowerCase();
        var lineStarts = lineStarts(content);
        List<Comment> comments;
        if (C_STYLE.contains(normalized)) {
            comments = extractCStyle(content, lineStarts);
        } else if (normalized.equals("pascal")) {
            comments = extractPascal(content, lineStarts);
        } else {
            throw new IllegalArgumentException("unsupported source kind: " + kind);
        }
        return comments.stream()
                .map(c -> new Chunk(sourcePath, List.of(), c.text(), c.startLine()))
                .toList();
    }

    /** One comment with its 1-based start line. */
    private record Comment(String text, int startLine) {}

    /** Offsets where each 1-based line starts; line 1 starts at offset 0. */
    private static List<Integer> lineStarts(String content) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        for (int k = 0; k < content.length(); k++) {
            if (content.charAt(k) == '\n') {
                starts.add(k + 1);
            }
        }
        return starts;
    }

    /** 1-based line containing the char at offset {@code i}. */
    private static int lineAt(List<Integer> starts, int i) {
        var pos = Collections.binarySearch(starts, i);
        return (pos >= 0 ? pos : -pos - 2) + 1;
    }

    // ---- C-style: // line comments, /* */ block comments ----

    private static List<Comment> extractCStyle(String content, List<Integer> lineStarts) {
        var comments = new ArrayList<Comment>();
        var lineRun = new LineRun();
        var i = 0;
        var n = content.length();
        while (i < n) {
            var c = content.charAt(i);
            if (c == '/' && i + 1 < n && content.charAt(i + 1) == '/') {
                var end = content.indexOf('\n', i + 2);
                if (end < 0) {
                    end = n;
                }
                appendLine(lineRun, lineAt(lineStarts, i), content.substring(i + 2, end).strip());
                i = end;
            } else if (c == '/' && i + 1 < n && content.charAt(i + 1) == '*') {
                var end = content.indexOf("*/", i + 2);
                var raw = end < 0 ? content.substring(i + 2) : content.substring(i + 2, end);
                flushLineRun(comments, lineRun);
                var cleaned = cleanBlockLines(raw);
                if (!cleaned.isEmpty()) {
                    comments.add(new Comment(cleaned, lineAt(lineStarts, i)));
                }
                i = end < 0 ? n : end + 2;
            } else if (c == '"' || c == '\'') {
                flushLineRun(comments, lineRun);
                i = skipString(content, i);
            } else if (c == '\n' || !Character.isWhitespace(c)) {
                // A blank line ends a line-comment run; other code ends it too.
                if (c == '\n' && lineRun.length() > 0 && endsWithBlankLine(content, i)) {
                    flushLineRun(comments, lineRun);
                } else if (!Character.isWhitespace(c)) {
                    flushLineRun(comments, lineRun);
                }
                i++;
            } else {
                i++;
            }
        }
        flushLineRun(comments, lineRun);
        return comments;
    }

    // ---- Pascal: // line comments, { } and (* *) block comments ----

    private static List<Comment> extractPascal(String content, List<Integer> lineStarts) {
        var comments = new ArrayList<Comment>();
        var lineRun = new LineRun();
        var i = 0;
        var n = content.length();
        while (i < n) {
            var c = content.charAt(i);
            if (c == '/' && i + 1 < n && content.charAt(i + 1) == '/') {
                var end = content.indexOf('\n', i + 2);
                if (end < 0) {
                    end = n;
                }
                appendLine(lineRun, lineAt(lineStarts, i), content.substring(i + 2, end).strip());
                i = end;
            } else if (c == '{') {
                var end = content.indexOf('}', i + 1);
                var raw = end < 0 ? content.substring(i + 1) : content.substring(i + 1, end);
                flushLineRun(comments, lineRun);
                // A lone {$...} compiler directive still counts as a comment.
                var cleaned = cleanBlockLines(raw);
                if (!cleaned.isEmpty()) {
                    comments.add(new Comment(cleaned, lineAt(lineStarts, i)));
                }
                i = end < 0 ? n : end + 1;
            } else if (c == '(' && i + 1 < n && content.charAt(i + 1) == '*') {
                var end = content.indexOf("*)", i + 2);
                var raw = end < 0 ? content.substring(i + 2) : content.substring(i + 2, end);
                flushLineRun(comments, lineRun);
                var cleaned = cleanBlockLines(raw);
                if (!cleaned.isEmpty()) {
                    comments.add(new Comment(cleaned, lineAt(lineStarts, i)));
                }
                i = end < 0 ? n : end + 2;
            } else if (c == '\'') {
                flushLineRun(comments, lineRun);
                i = skipPascalString(content, i);
            } else {
                if (!Character.isWhitespace(c)) {
                    flushLineRun(comments, lineRun);
                }
                i++;
            }
        }
        flushLineRun(comments, lineRun);
        return comments;
    }

    // ---- shared helpers ----

    /** An in-progress run of adjacent line comments and its start line. */
    private static final class LineRun {
        final StringBuilder text = new StringBuilder();
        int startLine;

        int length() {
            return text.length();
        }
    }

    private static void appendLine(LineRun lineRun, int line, String lineText) {
        if (lineText.isEmpty()) {
            return;
        }
        if (lineRun.text.length() > 0) {
            lineRun.text.append('\n');
        } else {
            lineRun.startLine = line;
        }
        lineRun.text.append(lineText);
    }

    private static void flushLineRun(List<Comment> comments, LineRun lineRun) {
        if (lineRun.length() > 0) {
            comments.add(new Comment(lineRun.text.toString(), lineRun.startLine));
            lineRun.text.setLength(0);
            lineRun.startLine = 0;
        }
    }

    /** Strips per-line leading {@code *} (Javadoc-style) and drops blank lines. */
    private static String cleanBlockLines(String raw) {
        var lines = raw.split("\n");
        var out = new StringBuilder();
        for (var line : lines) {
            var trimmed = line.strip();
            if (trimmed.startsWith("*")) {
                trimmed = trimmed.substring(1).strip();
            }
            if (!trimmed.isEmpty()) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(trimmed);
            }
        }
        return out.toString();
    }

    /** Skips a C-style string/char literal starting at the quote at {@code i}. */
    private static int skipString(String content, int i) {
        var quote = content.charAt(i);
        var j = i + 1;
        while (j < content.length()) {
            var c = content.charAt(j);
            if (c == '\\') {
                j += 2;
            } else if (c == quote) {
                return j + 1;
            } else if (c == '\n' && quote == '\'') {
                return j;
            } else {
                j++;
            }
        }
        return j;
    }

    /** Skips a Pascal string literal (single quotes, '' escape) starting at {@code i}. */
    private static int skipPascalString(String content, int i) {
        var j = i + 1;
        while (j < content.length()) {
            var c = content.charAt(j);
            if (c == '\'') {
                if (j + 1 < content.length() && content.charAt(j + 1) == '\'') {
                    j += 2;
                } else {
                    return j + 1;
                }
            } else {
                j++;
            }
        }
        return j;
    }

    private static boolean endsWithBlankLine(String content, int newlineAt) {
        var j = newlineAt + 1;
        while (j < content.length() && (content.charAt(j) == ' ' || content.charAt(j) == '\t')) {
            j++;
        }
        return j < content.length() && content.charAt(j) == '\n';
    }
}

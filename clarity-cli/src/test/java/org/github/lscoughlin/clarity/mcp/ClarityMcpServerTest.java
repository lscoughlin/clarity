package org.github.lscoughlin.clarity.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.clarity.daemon.Hit;
import org.github.lscoughlin.clarity.daemon.ReindexStats;
import org.github.lscoughlin.clarity.daemon.SearchBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClarityMcpServerTest {

    private static final Hit HIT =
            new Hit(
                    "docs",
                    "doc/guide.md",
                    List.of("Usage", "Indexing"),
                    "How to reindex.",
                    1.0f,
                    12,
                    false,
                    Map.of());

    private static final Hit TRUNCATED_HIT =
            new Hit(
                    "docs",
                    "doc/architecture.md",
                    List.of("Design"),
                    "**reindex** happens in the background ...",
                    1.0f,
                    40,
                    true,
                    Map.of());

    @Test
    void searchToolReturnsIndexedChunks() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, pathPrefix, fullText) -> {
                    assertEquals("docs", index);
                    assertEquals("reindex", query);
                    assertEquals(SearchBackend.DEFAULT_TOP_N, topN);
                    assertEquals(SearchBackend.Syntax.HYBRID, syntax);
                    assertEquals(null, pathPrefix);
                    assertFalse(fullText);
                    return List.of(HIT);
                });

        assertEquals("search", spec.tool().name());

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest(
                                "search", Map.of("index", "docs", "query", "reindex")));

        assertFalse(result.isError());
        assertEquals(1, result.content().size());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("doc/guide.md:12")
                && text.text().contains("[Usage / Indexing]")
                && text.text().contains("How to reindex."));
    }

    @Test
    void frontmatterSurfacesAsStructuredContentAndAnExtraTextBlock() {
        Hit hitWithFrontmatter =
                new Hit(
                        "docs",
                        "doc/guide.md",
                        List.of("Usage"),
                        "How to reindex.",
                        1.0f,
                        12,
                        false,
                        Map.of("scope", "billing"));
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool(
                        (index, query, topN, syntax, pathPrefix, fullText) ->
                                List.of(hitWithFrontmatter, HIT));

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest(
                                "search", Map.of("index", "docs", "query", "reindex")));

        assertFalse(result.isError());
        // One text block per hit, plus one extra for the hit that has frontmatter.
        assertEquals(3, result.content().size());
        assertTrue(result.content().get(1) instanceof McpSchema.TextContent text
                && text.text().equals("frontmatter: {\"scope\":\"billing\"}"));
        assertTrue(
                result.content().stream()
                        .noneMatch(
                                c ->
                                        c instanceof McpSchema.TextContent text
                                                && text.text().startsWith("frontmatter:")
                                                && !text.text().contains("scope")));

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.structuredContent();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structuredHits = (List<Map<String, Object>>) structured.get("hits");
        assertEquals(2, structuredHits.size());
        assertEquals(Map.of("scope", "billing"), structuredHits.get(0).get("frontmatter"));
        assertEquals(Map.of(), structuredHits.get(1).get("frontmatter"));
    }

    @Test
    void rawSyntaxPassesThrough() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, pathPrefix, fullText) -> {
                    assertEquals(SearchBackend.Syntax.RAW, syntax);
                    return List.of();
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "search",
                                        Map.of(
                                                "index", "docs",
                                                "query", "\"exact phrase\"",
                                                "syntax", "raw")));

        assertFalse(result.isError());
    }

    @Test
    void explicitTextSyntaxSelectsTextMode() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, pathPrefix, fullText) -> {
                    assertEquals(SearchBackend.Syntax.TEXT, syntax);
                    return List.of();
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "search",
                                        Map.of("index", "docs", "query", "reindex", "syntax", "text")));

        assertFalse(result.isError());
    }

    @Test
    void syntaxParsingIsCaseInsensitive() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, pathPrefix, fullText) -> {
                    assertEquals(SearchBackend.Syntax.VECTOR, syntax);
                    return List.of();
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "search",
                                        Map.of("index", "docs", "query", "reindex", "syntax", "VeCtOr")));

        assertFalse(result.isError());
    }

    @Test
    void fullTextArgumentPassesThrough() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, pathPrefix, fullText) -> {
                    assertTrue(fullText);
                    return List.of();
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "search",
                                        Map.of("index", "docs", "query", "reindex", "full_text", true)));

        assertFalse(result.isError());
    }

    @Test
    void truncatedHitNotesFullTextIsAvailable() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool(
                        (index, query, topN, syntax, pathPrefix, fullText) -> List.of(TRUNCATED_HIT));

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest(
                                "search", Map.of("index", "docs", "query", "reindex")));

        assertFalse(result.isError());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("full_text:true"));
    }

    @Test
    void searchToolReportsMissingArguments() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool(
                        (index, query, topN, syntax, pathPrefix, fullText) -> List.of());

        McpSchema.CallToolResult result =
                spec.callHandler().apply(null, new McpSchema.CallToolRequest("search", Map.of()));

        assertTrue(result.isError());
    }

    @Test
    void searchToolReportsNoMatches() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool(
                        (index, query, topN, syntax, pathPrefix, fullText) -> List.of());

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "search", Map.of("index", "docs", "query", "zzz")));

        assertFalse(result.isError());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("no matches"));
    }

    @Test
    void pathPrefixPassesThrough() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, pathPrefix, fullText) -> {
                    assertEquals("doc/deploy", pathPrefix);
                    return List.of();
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "search",
                                        Map.of(
                                                "index", "docs",
                                                "query", "reindex",
                                                "path_prefix", "doc/deploy")));

        assertFalse(result.isError());
    }

    @Test
    void blankPathPrefixIsTreatedAsAbsent() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, pathPrefix, fullText) -> {
                    assertEquals(null, pathPrefix);
                    return List.of();
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "search",
                                        Map.of(
                                                "index", "docs",
                                                "query", "reindex",
                                                "path_prefix", "  ")));

        assertFalse(result.isError());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchToolSchemaAdvertisesOptionalPathPrefix() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool(
                        (index, query, topN, syntax, pathPrefix, fullText) -> List.of());

        Map<String, Object> schema = spec.tool().inputSchema();
        var properties = (Map<String, Object>) schema.get("properties");
        var required = (List<String>) schema.get("required");
        assertTrue(properties.containsKey("path_prefix"));
        assertFalse(required.contains("path_prefix"));
    }

    @Test
    void listIndexesToolReturnsCounts() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildListIndexesTool(() -> Map.of("docs", 3));

        assertEquals("list_indexes", spec.tool().name());

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest("list_indexes", Map.of()));

        assertFalse(result.isError());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("docs: 3 docs"));
    }

    @Test
    void listIndexesToolReportsEmptyCorpus() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildListIndexesTool(Map::of);

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest("list_indexes", Map.of()));

        assertFalse(result.isError());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("no indexes"));
    }

    @Test
    void reindexToolDefaultsToAllIndexes() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildReindexTool(indexOrNull -> {
                    assertEquals(null, indexOrNull);
                    return Map.of("docs", new ReindexStats(2, 1, 0, 3));
                });

        assertEquals("reindex", spec.tool().name());

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest("reindex", Map.of()));

        assertFalse(result.isError());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("docs: +2 added, 1 changed, 0 removed (3 docs)"));
    }

    @Test
    void reindexToolPassesExplicitIndex() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildReindexTool(indexOrNull -> {
                    assertEquals("docs", indexOrNull);
                    return Map.of("docs", new ReindexStats(0, 0, 0, 1));
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "reindex", Map.of("index", "docs")));

        assertFalse(result.isError());
    }

    @Test
    void reindexToolReportsUnknownIndex() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildReindexTool(indexOrNull -> {
                    throw new IllegalArgumentException("unknown index: nope");
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(
                                null,
                                new McpSchema.CallToolRequest(
                                        "reindex", Map.of("index", "nope")));

        assertTrue(result.isError());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("unknown index: nope"));
    }

    @Test
    void healthToolReturnsCounts() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildHealthTool(() -> Map.of("docs", 5));

        assertEquals("health", spec.tool().name());

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest("health", Map.of()));

        assertFalse(result.isError());
        assertTrue(result.content().get(0) instanceof McpSchema.TextContent text
                && text.text().contains("docs: 5 docs"));
    }

    @Test
    void healthToolReportsDaemonFailure() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildHealthTool(() -> {
                    throw new java.io.IOException("daemon unreachable");
                });

        McpSchema.CallToolResult result =
                spec.callHandler()
                        .apply(null, new McpSchema.CallToolRequest("health", Map.of()));

        assertTrue(result.isError());
    }

    @Test
    void serverStaysUpUntilInterrupted(@TempDir Path base) throws Exception {
        var server = new ClarityMcpServer();
        server.dir = base;
        var worker = new Thread(() -> {
            try {
                assertEquals(0, server.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        worker.start();
        Thread.sleep(3_000);
        assertTrue(worker.isAlive(), "server must keep serving until interrupted");
        worker.interrupt();
        worker.join(10_000);
        assertFalse(worker.isAlive());
    }
}

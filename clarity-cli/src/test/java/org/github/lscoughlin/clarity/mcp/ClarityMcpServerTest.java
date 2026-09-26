package org.github.lscoughlin.clarity.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.github.lscoughlin.clarity.daemon.Hit;
import org.github.lscoughlin.clarity.daemon.SearchBackend;
import org.junit.jupiter.api.Test;

class ClarityMcpServerTest {

    private static final Hit HIT =
            new Hit(
                    "docs",
                    "doc/guide.md",
                    List.of("Usage", "Indexing"),
                    "How to reindex.",
                    1.0f,
                    12,
                    false);

    private static final Hit TRUNCATED_HIT =
            new Hit(
                    "docs",
                    "doc/architecture.md",
                    List.of("Design"),
                    "**reindex** happens in the background ...",
                    1.0f,
                    40,
                    true);

    @Test
    void searchToolReturnsIndexedChunks() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, fullText) -> {
                    assertEquals("docs", index);
                    assertEquals("reindex", query);
                    assertEquals(SearchBackend.DEFAULT_TOP_N, topN);
                    assertEquals(SearchBackend.Syntax.TEXT, syntax);
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
    void rawSyntaxPassesThrough() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, fullText) -> {
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
    void fullTextArgumentPassesThrough() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, fullText) -> {
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
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, fullText) -> List.of(TRUNCATED_HIT));

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
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, fullText) -> List.of());

        McpSchema.CallToolResult result =
                spec.callHandler().apply(null, new McpSchema.CallToolRequest("search", Map.of()));

        assertTrue(result.isError());
    }

    @Test
    void searchToolReportsNoMatches() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax, fullText) -> List.of());

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
}

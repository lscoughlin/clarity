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
                    12);

    @Test
    void searchToolReturnsIndexedChunks() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax) -> {
                    assertEquals("docs", index);
                    assertEquals("reindex", query);
                    assertEquals(SearchBackend.DEFAULT_TOP_N, topN);
                    assertEquals(SearchBackend.Syntax.TEXT, syntax);
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
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax) -> {
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
    void searchToolReportsMissingArguments() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax) -> List.of());

        McpSchema.CallToolResult result =
                spec.callHandler().apply(null, new McpSchema.CallToolRequest("search", Map.of()));

        assertTrue(result.isError());
    }

    @Test
    void searchToolReportsNoMatches() {
        McpServerFeatures.SyncToolSpecification spec =
                ClarityMcpServer.buildSearchTool((index, query, topN, syntax) -> List.of());

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

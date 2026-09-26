package org.github.lscoughlin.clarity.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import org.github.lscoughlin.clarity.daemon.DaemonClient;
import org.github.lscoughlin.clarity.daemon.Hit;
import org.github.lscoughlin.clarity.daemon.ReindexStats;
import org.github.lscoughlin.clarity.daemon.SearchBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP adapter: protocol handling only. The {@code search}, {@code
 * list_indexes}, {@code reindex}, and {@code health} tools delegate to the
 * daemon over its client and serve over stdio, which is how MCP clients
 * launch a local server.
 */
@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = "Serve the local documentation corpus to MCP clients over stdio.")
public final class ClarityMcpServer implements Callable<Integer> {
    private static final Logger LOG = LoggerFactory.getLogger(ClarityMcpServer.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Option(names = "--dir", defaultValue = ".", description = "Project directory to serve.")
    Path dir;

    @Option(
            names = "--socket",
            description = "Daemon socket path (default: <dir>/.clarity/clarity.sock).")
    Path socketOverride;

    @Option(
            names = "--lock",
            description = "Daemon lock file path (default: <dir>/.clarity/daemon.lock).")
    Path lockOverride;

    /** Query seam, so the tool wiring is testable without a transport. */
    public interface SearchFunction {
        List<Hit> search(
                String indexName,
                String query,
                int topN,
                SearchBackend.Syntax syntax,
                String pathPrefix,
                boolean fullText)
                throws Exception;
    }

    /** Index-discovery seam. */
    public interface ListIndexesFunction {
        Map<String, Integer> listIndexes() throws Exception;
    }

    /** Reindex seam; {@code indexOrNull} selects one index, or all when null. */
    public interface ReindexFunction {
        Map<String, ReindexStats> reindex(String indexOrNull) throws Exception;
    }

    /** Health seam. */
    public interface HealthFunction {
        Map<String, Integer> health() throws Exception;
    }

    public static McpServerFeatures.SyncToolSpecification buildSearchTool(SearchFunction search) {
        McpSchema.Tool tool =
                McpSchema.Tool.builder()
                        .name("search")
                        .description("Search the local documentation corpus.")
                        .inputSchema(
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "index",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                "Index name from .clarity/config.yaml."),
                                                        "query",
                                                        Map.of(
                                                                "type", "string",
                                                                "description", "Query text."),
                                                        "top_n",
                                                        Map.of(
                                                                "type", "integer",
                                                                "description",
                                                                "Maximum hits to return."),
                                                        "syntax",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                "Query interpretation: hybrid (default; fuses text and vector ranking, degrading to text-only without an embedded index), text (plain words), raw (Lucene syntax), or vector (semantic search)."),
                                                        "path_prefix",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                "Restrict results to this path or its subtree, e.g. doc/deploy."),
                                                        "full_text",
                                                        Map.of(
                                                                "type", "boolean",
                                                                "description",
                                                                "Return each hit's full chunk instead of a snippet (default false).")),
                                        "required", List.of("index", "query")))
                        .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(
                        (exchange, request) -> {
                            var args = request.arguments();
                            Object index = args == null ? null : args.get("index");
                            Object query = args == null ? null : args.get("query");
                            if (!(index instanceof String) || !(query instanceof String)) {
                                return McpSchema.CallToolResult.builder()
                                        .isError(true)
                                        .addTextContent("required arguments: index (string), query (string)")
                                        .build();
                            }
                            Object topN = args.get("top_n");
                            var limit =
                                    topN instanceof Number number
                                            ? number.intValue()
                                            : SearchBackend.DEFAULT_TOP_N;
                            Object syntaxArg = args.get("syntax");
                            SearchBackend.Syntax syntax =
                                    SearchBackend.Syntax.parse(syntaxArg instanceof String s ? s : null);
                            Object pathPrefixArg = args.get("path_prefix");
                            String pathPrefix =
                                    pathPrefixArg instanceof String s && !s.isBlank() ? s : null;
                            boolean fullText = Boolean.TRUE.equals(args.get("full_text"));
                            List<Hit> hits;
                            try {
                                hits =
                                        search.search(
                                                (String) index,
                                                (String) query,
                                                limit,
                                                syntax,
                                                pathPrefix,
                                                fullText);
                            } catch (Exception e) {
                                LOG.atWarn().setMessage("search failed").setCause(e).log();
                                return McpSchema.CallToolResult.builder()
                                        .isError(true)
                                        .addTextContent("search failed: " + e.getMessage())
                                        .build();
                            }
                            McpSchema.CallToolResult.Builder result =
                                    McpSchema.CallToolResult.builder();
                            if (hits.isEmpty()) {
                                result.addTextContent("no matches");
                            }
                            var structuredHits = new ArrayList<Map<String, Object>>();
                            for (var hit : hits) {
                                result.addTextContent(
                                        hit.location()
                                                + " ["
                                                + hit.heading()
                                                + "]\n"
                                                + hit.text()
                                                + (hit.truncated()
                                                        ? "\n[snippet — pass full_text:true for the complete section]"
                                                        : ""));
                                // Structured companion to the text block above, not a
                                // replacement: many MCP clients don't surface
                                // structuredContent, so frontmatter also gets its own
                                // always-visible text block here.
                                if (!hit.frontmatter().isEmpty()) {
                                    result.addTextContent(
                                            "frontmatter: " + JSON.writeValueAsString(hit.frontmatter()));
                                }
                                var structuredHit = new LinkedHashMap<String, Object>();
                                structuredHit.put("location", hit.location());
                                structuredHit.put("heading", hit.heading());
                                structuredHit.put("score", hit.score());
                                structuredHit.put("text", hit.text());
                                structuredHit.put("truncated", hit.truncated());
                                structuredHit.put("frontmatter", hit.frontmatter());
                                structuredHits.add(structuredHit);
                            }
                            result.structuredContent(Map.of("hits", structuredHits));
                            return result.build();
                        })
                .build();
    }

    public static McpServerFeatures.SyncToolSpecification buildListIndexesTool(
            ListIndexesFunction listIndexes) {
        McpSchema.Tool tool =
                McpSchema.Tool.builder()
                        .name("list_indexes")
                        .description("List configured indexes and their document counts.")
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(
                        (exchange, request) -> {
                            Map<String, Integer> counts;
                            try {
                                counts = listIndexes.listIndexes();
                            } catch (Exception e) {
                                LOG.atWarn().setMessage("list_indexes failed").setCause(e).log();
                                return McpSchema.CallToolResult.builder()
                                        .isError(true)
                                        .addTextContent("list_indexes failed: " + e.getMessage())
                                        .build();
                            }
                            McpSchema.CallToolResult.Builder result =
                                    McpSchema.CallToolResult.builder();
                            if (counts.isEmpty()) {
                                result.addTextContent("no indexes configured");
                            }
                            for (var entry : new TreeMap<>(counts).entrySet()) {
                                result.addTextContent(
                                        entry.getKey() + ": " + entry.getValue() + " docs");
                            }
                            return result.build();
                        })
                .build();
    }

    public static McpServerFeatures.SyncToolSpecification buildReindexTool(
            ReindexFunction reindex) {
        McpSchema.Tool tool =
                McpSchema.Tool.builder()
                        .name("reindex")
                        .description("Reindex one configured index, or all indexes if none given.")
                        .inputSchema(
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "index",
                                                        Map.of(
                                                                "type", "string",
                                                                "description",
                                                                "Index name from .clarity/config.yaml. Omit to reindex all.")),
                                        "required", List.of()))
                        .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(
                        (exchange, request) -> {
                            var args = request.arguments();
                            Object index = args == null ? null : args.get("index");
                            String indexOrNull = index instanceof String s ? s : null;
                            Map<String, ReindexStats> stats;
                            try {
                                stats = reindex.reindex(indexOrNull);
                            } catch (Exception e) {
                                LOG.atWarn().setMessage("reindex failed").setCause(e).log();
                                return McpSchema.CallToolResult.builder()
                                        .isError(true)
                                        .addTextContent("reindex failed: " + e.getMessage())
                                        .build();
                            }
                            McpSchema.CallToolResult.Builder result =
                                    McpSchema.CallToolResult.builder();
                            if (stats.isEmpty()) {
                                result.addTextContent("no indexes configured");
                            }
                            for (var entry : new TreeMap<>(stats).entrySet()) {
                                ReindexStats s = entry.getValue();
                                result.addTextContent(
                                        entry.getKey()
                                                + ": +"
                                                + s.added()
                                                + " added, "
                                                + s.changed()
                                                + " changed, "
                                                + s.removed()
                                                + " removed ("
                                                + s.docs()
                                                + " docs)");
                            }
                            return result.build();
                        })
                .build();
    }

    public static McpServerFeatures.SyncToolSpecification buildHealthTool(HealthFunction health) {
        McpSchema.Tool tool =
                McpSchema.Tool.builder()
                        .name("health")
                        .description("Report daemon health as per-index document counts.")
                        .inputSchema(Map.of("type", "object", "properties", Map.of()))
                        .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler(
                        (exchange, request) -> {
                            Map<String, Integer> counts;
                            try {
                                counts = health.health();
                            } catch (Exception e) {
                                LOG.atWarn().setMessage("health failed").setCause(e).log();
                                return McpSchema.CallToolResult.builder()
                                        .isError(true)
                                        .addTextContent("health failed: " + e.getMessage())
                                        .build();
                            }
                            McpSchema.CallToolResult.Builder result =
                                    McpSchema.CallToolResult.builder();
                            if (counts.isEmpty()) {
                                result.addTextContent("no indexes configured");
                            }
                            for (var entry : new TreeMap<>(counts).entrySet()) {
                                result.addTextContent(
                                        entry.getKey() + ": " + entry.getValue() + " docs");
                            }
                            return result.build();
                        })
                .build();
    }

    @Override
    public Integer call() throws Exception {
        DaemonClient.Target target =
                DaemonClient.target(dir.toAbsolutePath().normalize(), socketOverride, lockOverride);
        SearchFunction search =
                (index, query, topN, syntax, pathPrefix, fullText) ->
                        DaemonClient.search(target, index, query, topN, syntax, pathPrefix, fullText);
        ListIndexesFunction listIndexes = () -> DaemonClient.listIndexes(target);
        ReindexFunction reindex = (indexOrNull) -> DaemonClient.reindexWithStats(target, indexOrNull);
        HealthFunction health = () -> DaemonClient.health(target);
        var transport = new StdioServerTransportProvider(new JacksonMcpJsonMapper(JSON));
        McpServer.sync(transport)
                .serverInfo("clarity", "1.0-SNAPSHOT")
                .tools(
                        buildSearchTool(search),
                        buildListIndexesTool(listIndexes),
                        buildReindexTool(reindex),
                        buildHealthTool(health))
                .build();
        LOG.atInfo()
                .setMessage("clarity MCP server running on stdio for {}")
                .addArgument(target.baseDir())
                .log();
        // The SDK transport runs on daemon threads: without this the
        // process exits before serving any session. Block until
        // interrupted; MCP clients kill the server on disconnect.
        // (stdin belongs to the transport — never read it here.)
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }
}

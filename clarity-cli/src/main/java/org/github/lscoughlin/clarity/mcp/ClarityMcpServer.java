package org.github.lscoughlin.clarity.mcp;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.github.lscoughlin.clarity.daemon.DaemonClient;
import org.github.lscoughlin.clarity.daemon.Hit;
import org.github.lscoughlin.clarity.daemon.SearchBackend;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP adapter: protocol handling only. The single {@code search} tool
 * delegates to the daemon's query path against the Lucene backend and
 * serves over stdio, which is how MCP clients launch a local server.
 */
@Command(
        name = "mcp",
        mixinStandardHelpOptions = true,
        description = "Serve the local documentation corpus to MCP clients over stdio.")
public final class ClarityMcpServer implements Callable<Integer> {
    private static final Logger LOG = LoggerFactory.getLogger(ClarityMcpServer.class);

    @Option(names = "--dir", defaultValue = ".", description = "Project directory to serve.")
    Path dir;

    @Option(
            names = "--socket",
            description = "Daemon socket path (default: <dir>/.clarity/clarity.sock).")
    Path socketOverride;

    /** Query seam, so the tool wiring is testable without a transport. */
    public interface SearchFunction {
        List<Hit> search(
                String indexName, String query, int topN, SearchBackend.Syntax syntax, boolean fullText)
                throws Exception;
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
                                                                "Query interpretation: text (plain words, default), raw (Lucene syntax), or vector (semantic search)."),
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
                            SearchBackend.Syntax syntax =
                                    "raw".equals(args.get("syntax"))
                                            ? SearchBackend.Syntax.RAW
                                            : "vector".equals(args.get("syntax"))
                                                    ? SearchBackend.Syntax.VECTOR
                                                    : SearchBackend.Syntax.TEXT;
                            boolean fullText = Boolean.TRUE.equals(args.get("full_text"));
                            List<Hit> hits;
                            try {
                                hits =
                                        search.search(
                                                (String) index, (String) query, limit, syntax, fullText);
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
                            }
                            return result.build();
                        })
                .build();
    }

    @Override
    public Integer call() throws Exception {
        DaemonClient.Target target =
                DaemonClient.target(dir.toAbsolutePath().normalize(), socketOverride);
        SearchFunction search =
                (index, query, topN, syntax, fullText) ->
                        DaemonClient.search(target, index, query, topN, syntax, fullText);
        var transport =
                new StdioServerTransportProvider(
                        new JacksonMcpJsonMapper(JsonMapper.builder().build()));
        McpServer.sync(transport)
                .serverInfo("clarity", "1.0-SNAPSHOT")
                .tools(buildSearchTool(search))
                .build();
        LOG.atInfo()
                .setMessage("clarity MCP server running on stdio for {}")
                .addArgument(target.baseDir())
                .log();
        return 0;
    }
}

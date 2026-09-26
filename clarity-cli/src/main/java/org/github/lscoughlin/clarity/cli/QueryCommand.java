package org.github.lscoughlin.clarity.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.github.lscoughlin.clarity.daemon.DaemonClient;
import org.github.lscoughlin.clarity.daemon.Hit;
import org.github.lscoughlin.clarity.daemon.SearchBackend;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Searches an index via the daemon. */
@Command(name = "query", description = "Search an index.", mixinStandardHelpOptions = true)
public class QueryCommand implements Callable<Integer> {
    @Option(
            names = {"-d", "--dir"},
            defaultValue = ".",
            description = "Project directory containing .clarity/config.yaml.")
    Path baseDir;

    @Option(
            names = {"--socket"},
            description = "Daemon socket path (default: <dir>/.clarity/clarity.sock).")
    Path socket;

    @Spec
    CommandSpec spec;

    @Parameters(index = "0", description = "Index name from .clarity/config.yaml.")
    String indexName;

    @Parameters(index = "1..*", description = "Query text.")
    List<String> terms;

    @Option(
            names = {"-n", "--top-n"},
            defaultValue = "" + SearchBackend.DEFAULT_TOP_N,
            description = "Maximum hits to print.")
    int topN;

    @Option(
            names = {"--query-syntax"},
            defaultValue = "text",
            description =
                    "Query interpretation: text (plain words), raw (Lucene syntax),"
                            + " or vector (semantic search, needs an embedded index).")
    String syntax = "text";

    @Option(
            names = {"--full-text"},
            description = "Return each hit's full chunk instead of a snippet.")
    boolean fullText;

    @Override
    public Integer call() {
        SearchBackend.Syntax mode =
                "raw".equalsIgnoreCase(syntax)
                        ? SearchBackend.Syntax.RAW
                        : "vector".equalsIgnoreCase(syntax)
                                ? SearchBackend.Syntax.VECTOR
                                : SearchBackend.Syntax.TEXT;
        try {
            DaemonClient.Target target =
                    DaemonClient.target(baseDir.toAbsolutePath().normalize(), socket);
            DaemonClient.ensureRunning(target);
            List<Hit> hits =
                    DaemonClient.search(
                            target, indexName, String.join(" ", terms), topN, mode, fullText);
            var out = spec.commandLine().getOut();
            for (var hit : hits) {
                out.printf(
                        "%s [%s] (%.3f)%s%n%s%n%n",
                        hit.location(),
                        hit.heading(),
                        hit.score(),
                        hit.truncated() ? " [snippet — pass --full-text for the full section]" : "",
                        hit.text());
            }
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("query failed: " + e.getMessage());
            return 1;
        }
    }
}

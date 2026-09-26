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

    @Option(
            names = {"--lock"},
            description = "Daemon lock file path (default: <dir>/.clarity/daemon.lock).")
    Path lock;

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
            defaultValue = "hybrid",
            description =
                    "Query interpretation: hybrid (default; fuses text and vector ranking,"
                            + " degrading to text-only without an embedded index), text (plain words),"
                            + " raw (Lucene syntax), or vector (semantic search, needs an embedded index).")
    String syntax = "hybrid";

    @Option(
            names = {"--path-prefix"},
            description = "Restrict results to this path or its subtree, e.g. doc/deploy.")
    String pathPrefix;

    @Override
    public Integer call() {
        SearchBackend.Syntax mode = SearchBackend.Syntax.parse(syntax);
        try {
            DaemonClient.Target target =
                    DaemonClient.target(baseDir.toAbsolutePath().normalize(), socket, lock);
            DaemonClient.ensureRunning(target);
            List<Hit> hits =
                    DaemonClient.search(
                            target, indexName, String.join(" ", terms), topN, mode, pathPrefix);
            var out = spec.commandLine().getOut();
            for (var hit : hits) {
                out.printf(
                        "%s [%s] (%.5f)%n%s%n%n",
                        hit.location(), hit.heading(), hit.score(), hit.text());
            }
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("query failed: " + e.getMessage());
            return 1;
        }
    }
}

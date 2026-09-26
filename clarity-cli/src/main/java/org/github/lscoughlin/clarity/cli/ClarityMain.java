package org.github.lscoughlin.clarity.cli;

import org.github.lscoughlin.clarity.daemon.DaemonMain;
import org.github.lscoughlin.clarity.mcp.ClarityMcpServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * {@code clarity} command line: thin argument parsing over the daemon.
 * Indexing, search, MCP serving, and the daemon itself are
 * top-level subcommand classes; all behavior lives behind them. Each
 * subcommand declares its own {@code --dir} / {@code --socket}
 * (picocli rejects an inherited option redeclared below, so they are
 * not shared from here).
 */
@Command(
        name = "clarity",
        mixinStandardHelpOptions = true,
        version = "clarity 1.0-SNAPSHOT",
        description = "Index and search a local documentation corpus.",
        subcommands = {
            IndexCommand.class,
            QueryCommand.class,
            ClarityMcpServer.class,
            DaemonMain.class
        })
public class ClarityMain implements Runnable {
    @Spec
    CommandSpec spec;

    public static void main(String[] args) {
        System.exit(new CommandLine(new ClarityMain()).execute(args));
    }

    @Override
    public void run() {
        spec.commandLine().usage(spec.commandLine().getOut());
    }
}

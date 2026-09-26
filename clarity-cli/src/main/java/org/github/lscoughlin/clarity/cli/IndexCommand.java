package org.github.lscoughlin.clarity.cli;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.github.lscoughlin.clarity.daemon.DaemonClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/** Reindexes the corpus via the daemon. */
@Command(name = "index", description = "Reindex the corpus.", mixinStandardHelpOptions = true)
public class IndexCommand implements Callable<Integer> {
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

    @Override
    public Integer call() {
        try {
            DaemonClient.Target target = target();
            DaemonClient.ensureRunning(target);
            spec.commandLine()
                    .getOut()
                    .println("index counts: " + DaemonClient.reindex(target));
            return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("index failed: " + e.getMessage());
            return 1;
        }
    }

    private DaemonClient.Target target() {
        return DaemonClient.target(baseDir.toAbsolutePath().normalize(), socket);
    }
}

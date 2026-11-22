package org.github.lscoughlin.clarity.daemon;

import picocli.CommandLine;

/**
 * Test-only main for a real daemon process. It skips the embedding model
 * load, so the test needs no download. Everything else is the real daemon.
 */
public final class DaemonMainTestLauncher {
    private DaemonMainTestLauncher() {}

    public static void main(String[] args) {
        var main = new DaemonMain();
        main.embedderLoader = () -> null;
        System.exit(new CommandLine(main).execute(args));
    }
}

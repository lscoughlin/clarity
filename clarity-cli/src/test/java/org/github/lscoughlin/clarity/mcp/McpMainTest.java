package org.github.lscoughlin.clarity.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.github.lscoughlin.clarity.cli.ClarityMain;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class McpMainTest {

    @Test
    void mcpHelpExitsZero() {
        assertEquals(0, new CommandLine(new ClarityMain()).execute("mcp", "--help"));
    }

    @Test
    void daemonHelpExitsZero() {
        assertEquals(0, new CommandLine(new ClarityMain()).execute("daemon", "--help"));
    }

    @Test
    void unknownSubcommandFails() {
        assertEquals(2, new CommandLine(new ClarityMain()).execute("--bogus"));
    }

    @Test
    void missingValueFails() {
        assertEquals(2, new CommandLine(new ClarityMain()).execute("mcp", "--dir"));
    }
}

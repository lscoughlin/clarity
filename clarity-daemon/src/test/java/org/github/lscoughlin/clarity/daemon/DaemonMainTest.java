package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class DaemonMainTest {

    @Test
    void helpExitsZero() {
        assertEquals(0, new CommandLine(new DaemonMain()).execute("--help"));
    }

    @Test
    void unknownFlagFails() {
        assertEquals(2, new CommandLine(new DaemonMain()).execute("--bogus"));
    }

    @Test
    void missingValueFails() {
        assertEquals(2, new CommandLine(new DaemonMain()).execute("--dir"));
    }
}

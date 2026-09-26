package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonClientTest {
    private Supplier<Boolean> savedProbe;

    @BeforeEach
    void saveProbe() {
        savedProbe = DaemonClient.vectorModuleProbe;
        DaemonClient.resetVectorModuleCache();
    }

    @AfterEach
    void restoreProbe() {
        DaemonClient.vectorModuleProbe = savedProbe;
        DaemonClient.resetVectorModuleCache();
    }

    @Test
    void probePositiveAddsFlagAfterJava(@TempDir Path base) throws Exception {
        DaemonClient.vectorModuleProbe = () -> true;
        var command = DaemonClient.daemonCommand(DaemonClient.target(base, null));
        assertEquals("--add-modules", command.get(1));
        assertEquals(DaemonClient.VECTOR_MODULE, command.get(2));
    }

    @Test
    void probeNegativeOmitsFlag(@TempDir Path base) throws Exception {
        DaemonClient.vectorModuleProbe = () -> false;
        var command = DaemonClient.daemonCommand(DaemonClient.target(base, null));
        assertFalse(command.contains("--add-modules"));
    }

    @Test
    void probeFailureStillLaunchesWithoutFlag(@TempDir Path base) throws Exception {
        DaemonClient.vectorModuleProbe =
                () -> {
                    throw new IllegalStateException("probe down");
                };
        var command = DaemonClient.daemonCommand(DaemonClient.target(base, null));
        assertFalse(command.contains("--add-modules"));
    }
}

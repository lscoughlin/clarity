package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    private static void writeConfig(Path base, String body) throws IOException {
        Files.createDirectories(base.resolve(".clarity"));
        Files.writeString(base.resolve(".clarity/config.yaml"), "index: {}\n" + body);
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

    @Test
    void defaultLockLivesBesideSocket(@TempDir Path base) {
        var target = DaemonClient.target(base, null);

        assertEquals(base.resolve(".clarity/daemon.lock"), target.lockPath());
    }

    @Test
    void flagLockOverrideWins(@TempDir Path base) {
        var target = DaemonClient.target(base, null, Path.of("/tmp/x/daemon.lock"));

        assertEquals(Path.of("/tmp/x/daemon.lock"), target.lockPath());
    }

    @Test
    void configLockOverrideApplies(@TempDir Path base) throws Exception {
        writeConfig(base, "daemon:\n  lock_file: /tmp/cfg/daemon.lock\n");

        var target = DaemonClient.target(base, null);

        assertEquals(Path.of("/tmp/cfg/daemon.lock"), target.lockPath());
    }

    @Test
    void flagBeatsConfig(@TempDir Path base) throws Exception {
        writeConfig(base, "daemon:\n  lock_file: /tmp/cfg/daemon.lock\n");

        var target = DaemonClient.target(base, null, Path.of("/tmp/flag/daemon.lock"));

        assertEquals(Path.of("/tmp/flag/daemon.lock"), target.lockPath());
    }

    @Test
    void relativeConfigLockResolvesAgainstBase(@TempDir Path base) throws Exception {
        writeConfig(base, "daemon:\n  lock_file: run/daemon.lock\n");

        var target = DaemonClient.target(base, null);

        assertEquals(base.resolve("run/daemon.lock"), target.lockPath());
    }

    @Test
    void noNativeMarkerLeavesJvmLaunch(@TempDir Path base) throws Exception {
        System.clearProperty("org.graalvm.nativeimage.imagecode");
        assertNull(DaemonClient.nativeImagePath());
        var command = DaemonClient.daemonCommand(DaemonClient.target(base, null));
        assertFalse(command.contains("daemon"));
    }

    @Test
    void nativeMarkerRespawnsSelfAsDaemon(@TempDir Path base) throws Exception {
        System.setProperty("org.graalvm.nativeimage.imagecode", "runtime");
        try {
            var target = DaemonClient.target(base, null);
            var command = DaemonClient.daemonCommand(target);
            var self =
                    ProcessHandle.current()
                            .info()
                            .command()
                            .orElseThrow(AssertionError::new);
            assertEquals(
                    List.of(
                            self,
                            "daemon",
                            "--dir",
                            target.baseDir().toString(),
                            "--socket",
                            target.socketPath().toString()),
                    command);
            assertTrue(
                    command.stream().noneMatch(arg -> arg.equals("--add-modules")
                            || arg.equals("-jar")
                            || arg.equals("-cp")));
        } finally {
            System.clearProperty("org.graalvm.nativeimage.imagecode");
        }
    }
}

package org.github.lscoughlin.clarity.daemon;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.github.lscoughlin.clarity.parser.ClarityConfig;

/** Loads {@code .clarity/config.yaml} relative to a base directory. */
public final class ConfigLoader {
    public static final String CONFIG_RELATIVE = ".clarity/config.yaml";

    private ConfigLoader() {}

    public static ClarityConfig load(Path baseDir) {
        var configFile = baseDir.resolve(CONFIG_RELATIVE);
        try {
            return ClarityConfig.parse(Files.readString(configFile));
        } catch (NoSuchFileException e) {
            throw new IllegalStateException(
                    "no clarity config at " + configFile + " (run from a directory containing "
                            + CONFIG_RELATIVE + ")",
                    e);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + configFile, e);
        }
    }
}

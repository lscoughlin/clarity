package org.github.lscoughlin.clarity.parser;

import java.util.List;

/**
 * Loads a YAML file as a single whole-file {@link Chunk}, per the project
 * contract. The raw file text is preserved (validation of the YAML
 * structure itself is a later concern, not the skeleton's).
 */
public final class YamlLoader {
    private YamlLoader() {}

    public static Chunk load(String sourcePath, String content) {
        return new Chunk(sourcePath, List.of(), content.strip(), 1);
    }
}

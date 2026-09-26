package org.github.lscoughlin.clarity.parser;

import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Mirror of {@code .clarity/config.yaml}. Field names are camelCase here;
 * SnakeYAML-style {@code snake_case} keys map via the
 * {@code SNAKE_CASE} naming strategy on the loader, so
 * {@code index_path} binds to {@link IndexConfig#indexPath}.
 */
public record ClarityConfig(Map<String, IndexConfig> index, DaemonConfig daemon) {
    public ClarityConfig {
        index = index == null ? Map.of() : Map.copyOf(index);
        daemon = daemon == null ? new DaemonConfig(null) : daemon;
    }

    /** Parses {@code .clarity/config.yaml} content; unknown keys fail fast. */
    public static ClarityConfig parse(String yaml) {
        YAMLMapper mapper = YAMLMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        try {
            return mapper.readValue(yaml, ClarityConfig.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("invalid clarity config", e);
        }
    }

    public record IndexConfig(
            String indexPath, List<String> markdown, List<String> yaml, SourceConfig source) {
        public IndexConfig {
            markdown = markdown == null ? List.of() : List.copyOf(markdown);
            yaml = yaml == null ? List.of() : List.copyOf(yaml);
        }
    }

    /** Daemon runtime paths; all keys optional (absent → defaults). */
    public record DaemonConfig(String lockFile) {}

    public record SourceConfig(List<String> kind, List<String> include) {
        public SourceConfig {
            kind = kind == null ? List.of() : List.copyOf(kind);
            include = include == null ? List.of() : List.copyOf(include);
        }
    }
}

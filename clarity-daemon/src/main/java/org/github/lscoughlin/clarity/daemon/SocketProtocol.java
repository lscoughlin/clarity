package org.github.lscoughlin.clarity.daemon;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Socket message shapes. Requests carry an {@code op} discriminator
 * ({@code search}, {@code reindex}, {@code health}); responses carry
 * {@code ok} plus either a payload or an {@code error} string.
 */
final class SocketProtocol {
    /** Shared JSON codec; unknown fields ignored for forward compatibility. */
    static final ObjectMapper JSON =
            JsonMapper.builder()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    private SocketProtocol() {}

    record SearchRequest(String op, String index, String query, int topN, String syntax, boolean fullText) {
        SearchRequest(String index, String query, int topN, String syntax) {
            this(index, query, topN, syntax, false);
        }

        SearchRequest(String index, String query, int topN, String syntax, boolean fullText) {
            this("search", index, query, topN, syntax, fullText);
        }
    }

    record ControlRequest(String op) {}

    record SearchResponse(boolean ok, List<Hit> hits, String error) {
        static SearchResponse ok(List<Hit> hits) {
            return new SearchResponse(true, hits, null);
        }

        static SearchResponse error(String message) {
            return new SearchResponse(false, List.of(), message);
        }
    }

    record StatusResponse(boolean ok, Map<String, Integer> counts, String error) {
        static StatusResponse ok(Map<String, Integer> counts) {
            return new StatusResponse(true, counts, null);
        }

        static StatusResponse error(String message) {
            return new StatusResponse(false, Map.of(), message);
        }
    }

    /** Socket, lock, and log paths derived from the project base dir. */
    static final class Paths {
        private Paths() {}

        static Path socketFor(Path baseDir) {
            return baseDir.resolve(".clarity/clarity.sock");
        }

        static Path lockFor(Path baseDir) {
            return baseDir.resolve(".clarity/daemon.lock");
        }

        static Path logFor(Path baseDir) {
            return baseDir.resolve(".clarity/daemon.log");
        }
    }
}

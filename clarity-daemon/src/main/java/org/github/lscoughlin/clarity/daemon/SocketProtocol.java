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

    record SearchRequest(
            String op,
            String index,
            String query,
            int topN,
            String syntax,
            String pathPrefix,
            boolean fullText) {
        SearchRequest(String index, String query, int topN, String syntax, String pathPrefix) {
            this(index, query, topN, syntax, pathPrefix, false);
        }

        SearchRequest(
                String index, String query, int topN, String syntax, String pathPrefix, boolean fullText) {
            this("search", index, query, topN, syntax, pathPrefix, fullText);
        }
    }

    record ControlRequest(String op) {}

    record ReindexRequest(String op, String index) {
        ReindexRequest(String index) {
            this("reindex", index);
        }
    }

    record SearchResponse(boolean ok, List<Hit> hits, String error) {
        static SearchResponse ok(List<Hit> hits) {
            return new SearchResponse(true, hits, null);
        }

        static SearchResponse error(String message) {
            return new SearchResponse(false, List.of(), message);
        }
    }

    record StatusResponse(
            boolean ok, Map<String, Integer> counts, Map<String, ReindexStats> stats, String error) {
        /** Missing fields deserialize to null (unknown-fields tolerance doesn't cover this). */
        StatusResponse {
            counts = counts == null ? Map.of() : counts;
            stats = stats == null ? Map.of() : stats;
        }

        static StatusResponse ok(Map<String, Integer> counts) {
            return new StatusResponse(true, counts, Map.of(), null);
        }

        static StatusResponse ok(Map<String, Integer> counts, Map<String, ReindexStats> stats) {
            return new StatusResponse(true, counts, stats, null);
        }

        static StatusResponse error(String message) {
            return new StatusResponse(false, Map.of(), Map.of(), message);
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

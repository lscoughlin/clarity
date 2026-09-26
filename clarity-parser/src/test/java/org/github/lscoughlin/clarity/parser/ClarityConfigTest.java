package org.github.lscoughlin.clarity.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClarityConfigTest {

    @Test
    void parsesDocumentedShape() {
        String yaml =
                """
                index:
                  my_docs:
                    index_path: /tmp/clarity/my_docs
                    markdown:
                      - doc/**/*.md
                    yaml:
                      - doc/**/*.yaml
                    source:
                      kind: [java, pascal, go, rust, swift]
                      include:
                        - src/main/java/**/*.java
                """;

        ClarityConfig config = ClarityConfig.parse(yaml);

        ClarityConfig.IndexConfig docs = config.index().get("my_docs");
        assertEquals("/tmp/clarity/my_docs", docs.indexPath());
        assertEquals(List.of("doc/**/*.md"), docs.markdown());
        assertEquals(List.of("doc/**/*.yaml"), docs.yaml());
        assertEquals(List.of("java", "pascal", "go", "rust", "swift"), docs.source().kind());
        assertEquals(List.of("src/main/java/**/*.java"), docs.source().include());
    }

    @Test
    void missingListsDefaultToEmpty() {
        ClarityConfig config = ClarityConfig.parse("index:\n  tiny:\n    index_path: /tmp/x\n");

        ClarityConfig.IndexConfig tiny = config.index().get("tiny");
        assertTrue(tiny.markdown().isEmpty());
        assertTrue(tiny.yaml().isEmpty());
    }

    @Test
    void unknownKeysFailFast() {
        assertThrows(
                RuntimeException.class,
                () -> ClarityConfig.parse("index:\n  tiny:\n    index_path: /tmp/x\n    bogus_key: 1\n"));
    }

    @Test
    void daemonSectionParses() {
        ClarityConfig config =
                ClarityConfig.parse("index: {}\ndaemon:\n  lock_file: /tmp/x/daemon.lock\n");

        assertEquals("/tmp/x/daemon.lock", config.daemon().lockFile());
    }

    @Test
    void absentDaemonSectionDefaults() {
        ClarityConfig config = ClarityConfig.parse("index: {}\n");

        assertNotNull(config.daemon());
        assertNull(config.daemon().lockFile());
    }
}

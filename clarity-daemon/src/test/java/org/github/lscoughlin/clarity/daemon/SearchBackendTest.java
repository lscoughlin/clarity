package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SearchBackendTest {

    @Test
    void parseMapsRecognizedValuesCaseInsensitively() {
        assertEquals(SearchBackend.Syntax.TEXT, SearchBackend.Syntax.parse("text"));
        assertEquals(SearchBackend.Syntax.TEXT, SearchBackend.Syntax.parse("TEXT"));
        assertEquals(SearchBackend.Syntax.RAW, SearchBackend.Syntax.parse("raw"));
        assertEquals(SearchBackend.Syntax.RAW, SearchBackend.Syntax.parse("Raw"));
        assertEquals(SearchBackend.Syntax.VECTOR, SearchBackend.Syntax.parse("vector"));
        assertEquals(SearchBackend.Syntax.VECTOR, SearchBackend.Syntax.parse("VECTOR"));
        assertEquals(SearchBackend.Syntax.HYBRID, SearchBackend.Syntax.parse("hybrid"));
        assertEquals(SearchBackend.Syntax.HYBRID, SearchBackend.Syntax.parse("HyBrId"));
    }

    @Test
    void parseDefaultsToHybridForUnrecognizedNullOrBlank() {
        assertEquals(SearchBackend.Syntax.HYBRID, SearchBackend.Syntax.parse("nope"));
        assertEquals(SearchBackend.Syntax.HYBRID, SearchBackend.Syntax.parse(null));
        assertEquals(SearchBackend.Syntax.HYBRID, SearchBackend.Syntax.parse(""));
        assertEquals(SearchBackend.Syntax.HYBRID, SearchBackend.Syntax.parse("  "));
    }
}

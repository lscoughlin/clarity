package org.github.lscoughlin.clarity.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdleTimeoutTest {
    private static final Duration FALLBACK = Duration.ofMinutes(10);

    @Test
    void plainNumberMeansSeconds() {
        assertEquals(Duration.ofSeconds(45), IdleTimeout.parse("45"));
    }

    @Test
    void suffixesSelectUnit() {
        assertEquals(Duration.ofSeconds(30), IdleTimeout.parse("30s"));
        assertEquals(Duration.ofMinutes(30), IdleTimeout.parse("30m"));
        assertEquals(Duration.ofHours(2), IdleTimeout.parse("2h"));
    }

    @Test
    void zeroMeansDisabled() {
        assertEquals(Duration.ZERO, IdleTimeout.parse("0"));
        assertEquals(Duration.ZERO, IdleTimeout.parse("0m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "-5m", "abc", "10x", "m", "1.5h", "1 0", "", "  "})
    void badValuesAreRejected(String text) {
        var e = assertThrows(IllegalArgumentException.class, () -> IdleTimeout.parse(text));
        assertTrue(e.getMessage().contains("idle timeout"), e.getMessage());
    }

    @Test
    void nullIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IdleTimeout.parse(null));
    }

    @Test
    void overflowIsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> IdleTimeout.parse("99999999999999999999h"));
        assertThrows(
                IllegalArgumentException.class, () -> IdleTimeout.parse("9223372036854775807h"));
    }

    @Test
    void optionBeatsEnvironment() {
        UnaryOperator<String> env = Map.of(IdleTimeout.ENV_VAR, "5m")::get;
        assertEquals(Duration.ofSeconds(7), IdleTimeout.resolve("7", env, FALLBACK));
    }

    @Test
    void optionBeatsInvalidEnvironment() {
        UnaryOperator<String> env = Map.of(IdleTimeout.ENV_VAR, "junk")::get;
        assertEquals(Duration.ZERO, IdleTimeout.resolve("0", env, FALLBACK));
    }

    @Test
    void environmentBeatsDefault() {
        UnaryOperator<String> env = Map.of(IdleTimeout.ENV_VAR, "1h")::get;
        assertEquals(Duration.ofHours(1), IdleTimeout.resolve(null, env, FALLBACK));
    }

    @Test
    void defaultWhenNothingSet() {
        assertEquals(FALLBACK, IdleTimeout.resolve(null, name -> null, FALLBACK));
    }

    @Test
    void badEnvironmentNamesTheVariable() {
        UnaryOperator<String> env = Map.of(IdleTimeout.ENV_VAR, "junk")::get;
        var e =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> IdleTimeout.resolve(null, env, FALLBACK));
        assertTrue(e.getMessage().contains(IdleTimeout.ENV_VAR), e.getMessage());
    }

    @Test
    void formatRoundTrips() {
        assertEquals("10m", IdleTimeout.format(Duration.ofMinutes(10)));
        assertEquals("2h", IdleTimeout.format(Duration.ofHours(2)));
        assertEquals("90s", IdleTimeout.format(Duration.ofSeconds(90)));
        assertEquals("0s", IdleTimeout.format(Duration.ZERO));
    }
}

package org.github.lscoughlin.clarity.daemon;

import java.time.Duration;
import java.util.function.UnaryOperator;

/**
 * Parses and resolves the daemon idle timeout.
 *
 * <p>A value is a whole number with an optional unit suffix: {@code s}
 * (seconds, the default), {@code m} (minutes) or {@code h} (hours).
 * The value {@code 0} means the daemon never stops when idle.
 */
final class IdleTimeout {
    static final String ENV_VAR = "CLARITY_DAEMON_IDLE_TIMEOUT";

    private IdleTimeout() {}

    /** Parses one value. Throws {@link IllegalArgumentException} with a clear message. */
    static Duration parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "idle timeout is empty; use a number with an optional s, m or h suffix");
        }
        var value = text.strip();
        var unit = value.charAt(value.length() - 1);
        var digits = value;
        long multiplier = 1;
        switch (unit) {
            case 's' -> digits = value.substring(0, value.length() - 1);
            case 'm' -> {
                digits = value.substring(0, value.length() - 1);
                multiplier = 60;
            }
            case 'h' -> {
                digits = value.substring(0, value.length() - 1);
                multiplier = 3600;
            }
            default -> {}
        }
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException(
                    "invalid idle timeout '"
                            + text
                            + "'; use a non-negative whole number with an optional s, m or h"
                            + " suffix (for example 30m)");
        }
        try {
            return Duration.ofSeconds(Math.multiplyExact(Long.parseLong(digits), multiplier));
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("idle timeout '" + text + "' is too large");
        }
    }

    /**
     * Picks the timeout: the option wins over the environment variable,
     * and the variable wins over the default. An unset or empty source is skipped.
     */
    static Duration resolve(String option, UnaryOperator<String> env, Duration fallback) {
        if (option != null) {
            return parse(option);
        }
        var fromEnv = env.apply(ENV_VAR);
        if (fromEnv != null) {
            try {
                return parse(fromEnv);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(ENV_VAR + ": " + e.getMessage());
            }
        }
        return fallback;
    }

    /** Human-readable form for logs, for example {@code 10m} or {@code 90s}. */
    static String format(Duration timeout) {
        if (timeout.toMillis() % 1000 != 0) {
            return timeout.toMillis() + "ms";
        }
        var seconds = timeout.toSeconds();
        if (seconds != 0 && seconds % 3600 == 0) {
            return seconds / 3600 + "h";
        }
        if (seconds != 0 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }
}

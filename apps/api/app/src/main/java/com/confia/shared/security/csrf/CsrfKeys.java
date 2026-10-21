package com.confia.shared.security.csrf;

import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.springframework.core.env.Environment;

/**
 * The HMAC keys of the CSRF token (session-endpoints-cookie-and-csrf design.md, decision 3): the
 * current key, which issues and verifies, and an optional previous key, which only verifies so that
 * rotating the current one does not turn every live session's next refresh into a {@code 403}.
 *
 * <p>The values are read with {@link Environment#getProperty(String)}, never bound with
 * {@code @ConfigurationProperties} or {@code @Value}: Spring Boot's binding failure analyzer prints
 * the rejected value, and a key must never reach a log (CLAUDE.md, regla 11). Every failure is an
 * {@link IllegalStateException} that names the property, repeats no part of the value and chains no
 * cause, because the message of a decoder can carry a fragment of its input.
 */
public final class CsrfKeys {

    /** {@code confia.security.csrf.key}: the current key, standard Base64 of at least 32 bytes. */
    public static final String CURRENT_PROPERTY = "confia.security.csrf.key";

    /** {@code confia.security.csrf.previous-key}: the optional key that only verifies. */
    public static final String PREVIOUS_PROPERTY = "confia.security.csrf.previous-key";

    static final int MIN_KEY_BYTES = 32;

    private final byte[] current;
    private final byte[] previous;

    private CsrfKeys(byte[] current, byte[] previous) {
        this.current = current;
        this.previous = previous;
    }

    /**
     * Builds the keys from the process environment.
     *
     * @throws IllegalStateException naming the property when the current key is missing, is not
     *     standard Base64 or decodes to fewer than 32 bytes, when the previous key is set and has
     *     one of those faults, or when the previous key equals the current one
     */
    public static CsrfKeys load(Environment environment) {
        String currentText = optional(environment, CURRENT_PROPERTY);
        if (currentText == null) {
            throw failure(CURRENT_PROPERTY, "is required and was not set");
        }
        byte[] current = decode(currentText, CURRENT_PROPERTY);
        String previousText = optional(environment, PREVIOUS_PROPERTY);
        byte[] previous = previousText == null ? null : decode(previousText, PREVIOUS_PROPERTY);
        if (previous != null && Arrays.equals(previous, current)) {
            throw failure(PREVIOUS_PROPERTY, "must differ from " + CURRENT_PROPERTY);
        }
        return new CsrfKeys(current, previous);
    }

    /**
     * Keys already decoded, for a caller that holds raw bytes (the tests of the filter). The same
     * rules as {@link #load}: at least 32 bytes each, and a previous key different from the current.
     *
     * @param previous the previous key, or {@code null} for none
     */
    public static CsrfKeys of(byte[] current, byte[] previous) {
        if (current.length < MIN_KEY_BYTES || previous != null && previous.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("a CSRF key must have at least " + MIN_KEY_BYTES
                    + " bytes");
        }
        if (previous != null && Arrays.equals(previous, current)) {
            throw new IllegalArgumentException("the previous CSRF key must differ from the current");
        }
        return new CsrfKeys(current.clone(), previous == null ? null : previous.clone());
    }

    /** A copy of the current key. */
    byte[] current() {
        return current.clone();
    }

    /** A copy of the previous key, or empty when none is configured. */
    Optional<byte[]> previous() {
        return Optional.ofNullable(previous).map(byte[]::clone);
    }

    /** The value, or {@code null} when the property is absent or blank. */
    private static String optional(Environment environment, String property) {
        String value = environment.getProperty(property);
        return value == null || value.isBlank() ? null : value;
    }

    private static byte[] decode(String text, String property) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            // Not chained: see the class comment.
            throw failure(property, "is not valid standard Base64");
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw failure(property, "must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
        return bytes;
    }

    private static IllegalStateException failure(String property, String problem) {
        return new IllegalStateException("the property " + property + " " + problem);
    }
}

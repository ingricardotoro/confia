package com.confia.shared.security.token;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Reads the claims of a token whose signature has verified, one declared claim at a time and by its
 * JSON type (session-tokens-and-web-layer design.md, decision 4; follow-up S2 of the codec review). It
 * never asks the tree to convert: a number is read only from a number node and a string only from a
 * string node, so {@code "exp":"1760263800"}, {@code "sub":7} and {@code "iat":1.5} are as invalid as
 * any other wrong type, where {@code asLong()} and {@code asString()} would have accepted them.
 *
 * <p>Every breach is a {@link TokenRejection#CLAIMS_INVALID} without data. {@link #requireWindow} is
 * the one place that can say {@link TokenRejection#EXPIRED}, and it is called last, so that an expired
 * token is reported as expired only when everything else about it is intact.
 */
final class ClaimReader {

    /** The latest date the time rules accept, 9999-12-31T23:59:59Z, far from any overflow. */
    private static final long LAST_EPOCH_SECOND = 253_402_300_799L;

    /** How far ahead of the verifier's clock an issue time may be. */
    private static final Duration ISSUE_TIME_TOLERANCE = Duration.ofSeconds(60);

    private final JsonNode claims;

    /**
     * @param claims the payload object
     * @param declared the exact set of claim names the token kind has; any other, or a missing one,
     *     is invalid
     */
    ClaimReader(JsonNode claims, Set<String> declared) {
        if (!claims.isObject() || !Set.copyOf(claims.propertyNames()).equals(declared)) {
            throw invalid();
        }
        this.claims = claims;
    }

    static TokenRejectedException invalid() {
        return new TokenRejectedException(TokenRejection.CLAIMS_INVALID);
    }

    /** The claim as a string, only when it is a JSON string. */
    String text(String name) {
        JsonNode node = claims.get(name);
        if (node == null || !node.isString()) {
            throw invalid();
        }
        return node.asString();
    }

    void requireText(String name, String expected) {
        if (!expected.equals(text(name))) {
            throw invalid();
        }
    }

    /** The claim as a UUID, only when it is a string in the canonical lower-case form. */
    UUID uuid(String name) {
        String text = text(name);
        UUID uuid;
        try {
            uuid = UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!uuid.toString().equals(text)) {
            throw invalid();
        }
        return uuid;
    }

    /** The claim as whole seconds since the epoch, only when it is an integral JSON number in range. */
    long epochSecond(String name) {
        JsonNode node = claims.get(name);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw invalid();
        }
        long value = node.longValue();
        if (value < 0 || value > LAST_EPOCH_SECOND) {
            throw invalid();
        }
        return value;
    }

    /** The claim as a list of strings, only when it is a JSON array whose elements are all strings. */
    List<String> texts(String name) {
        JsonNode node = claims.get(name);
        if (node == null || !node.isArray()) {
            throw invalid();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (!element.isString()) {
                throw invalid();
            }
            values.add(element.asString());
        }
        return values;
    }

    /**
     * The time rules, with the clock of the verifier and no tolerance on {@code exp}: the lifetime is
     * exactly {@code lifetime}, the issue time is at most 60 seconds ahead of {@code now}, and the
     * token is expired from the instant {@code exp} itself.
     */
    static void requireWindow(long issuedAt, long expiresAt, Duration lifetime, Instant now) {
        if (expiresAt - issuedAt != lifetime.getSeconds()) {
            throw invalid();
        }
        if (Instant.ofEpochSecond(issuedAt).isAfter(now.plus(ISSUE_TIME_TOLERANCE))) {
            throw invalid();
        }
        if (!now.isBefore(Instant.ofEpochSecond(expiresAt))) {
            throw new TokenRejectedException(TokenRejection.EXPIRED);
        }
    }
}

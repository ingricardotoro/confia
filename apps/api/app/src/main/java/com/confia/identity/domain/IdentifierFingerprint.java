package com.confia.identity.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The keyed HMAC-SHA-256 hash of a normalized {@link LoginIdentifier}, hex-encoded to exactly 64
 * characters — {@code identity_login_backoff.identifier_hash}'s own primary key column (design.md,
 * decision 3). Never the raw identifier itself: that is precisely what keeps that table from
 * becoming a store of attacker-controlled strings of arbitrary length and an enumeration oracle
 * for anyone who reads it.
 *
 * <p>The constructor mirrors that column's own
 * {@code CHECK (identifier_hash ~ '^[0-9a-f]{64}$')}, so a value the application ever computed
 * wrong fails here, at construction, rather than at the database.
 *
 * <p>Not a secret in the sense {@code docs/03-seguridad.md} §4.1/§4.6 name — it is itself the
 * enumeration-safe representation of an identifier, not a password, a hash or a pepper — so this
 * record keeps its generated {@code toString()} (design.md, decision 10, point 1 only names
 * {@link PlainPassword}, {@link StoredPasswordHash} and the Argon2id pepper).
 */
public record IdentifierFingerprint(String value) {

    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    public IdentifierFingerprint {
        Objects.requireNonNull(value, "value");
        if (!HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "an identifier fingerprint must be exactly 64 lowercase hex characters, was "
                            + value.length() + " characters");
        }
    }
}

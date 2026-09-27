package com.confia.identity.domain;

import java.util.Objects;

/**
 * The Argon2id PHC-formatted hash stored in {@code identity_staff_account.password_hash}
 * (design.md, decision 6: {@code $argon2id$v=19$m=...,t=...,p=...$<salt>$<tag>}). This value IS
 * "el hash Argon2id resultante" specs/identity/spec.md's redaction requirement names explicitly,
 * so {@code toString()} is deliberately redacted — this is a plain final class with an explicit
 * override, never a record, whose generated {@code toString()} would print it verbatim
 * (design.md, decision 10, point 1; CLAUDE.md regla 11).
 *
 * <p>The constructor mirrors {@code identity_staff_account}'s own
 * {@code CHECK (password_hash LIKE '$argon2id$%')} (design.md, decision 4), so a malformed value
 * fails in the application before it ever reaches a statement, the same reasoning
 * {@link LoginIdentifier} already applies to its own column's {@code CHECK}.
 */
public final class StoredPasswordHash {

    private static final String PHC_PREFIX = "$argon2id$";

    private final String value;

    public StoredPasswordHash(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.startsWith(PHC_PREFIX)) {
            throw new IllegalArgumentException(
                    "a stored password hash must start with " + PHC_PREFIX);
        }
        this.value = value;
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StoredPasswordHash that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Redacted deliberately: {@code value} is the Argon2id hash itself. */
    @Override
    public String toString() {
        return "StoredPasswordHash[REDACTED]";
    }
}

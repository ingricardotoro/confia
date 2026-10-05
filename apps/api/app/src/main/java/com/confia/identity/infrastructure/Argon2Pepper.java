package com.confia.identity.infrastructure;

import java.util.Base64;
import java.util.Objects;

/**
 * The Argon2id pepper, applied as Argon2id's {@code secret} parameter (docs/03-seguridad.md §4.1;
 * design.md, decision 6). {@code toString()} is deliberately redacted (CLAUDE.md, regla 11;
 * specs/identity/spec.md, "Ningún secreto de este módulo es observable..."), by explicit override
 * rather than by relying on {@code byte[]}'s own default {@code toString()} (design.md, decision
 * 10, point 1).
 *
 * <p><b>How the Base64 value reaches this constructor is deliberately not this class's concern.</b>
 * {@code design.md} decision 6 defers the real wiring — an environment variable, a mounted secret,
 * or a real secrets manager — to change 11: "aquí llega por constructor, como
 * {@code TransactionRunner} y {@code JooqInstitutionRepository} reciben lo suyo". This class only
 * ever validates that whatever arrives decodes to exactly {@value #LENGTH_BYTES} bytes, and fails
 * loudly at construction if it does not — never later, on first use.
 *
 * <p>The administrative process now supplies it: {@code IdentityConfiguration} reads the property
 * {@code confia.identity.argon2-pepper} (environment variable {@code
 * CONFIA_IDENTITY_ARGON2PEPPER}) at startup (web-edge-foundations design.md, decision 3).
 */
public final class Argon2Pepper {

    public static final int LENGTH_BYTES = Argon2Profile.PEPPER_LENGTH_BYTES;

    private final byte[] value;

    private Argon2Pepper(byte[] value) {
        this.value = value;
    }

    /**
     * @throws NullPointerException if {@code base64} is {@code null}
     * @throws IllegalArgumentException if {@code base64} is blank, is not valid Base64, or does not
     *     decode to exactly {@value #LENGTH_BYTES} bytes
     */
    public static Argon2Pepper fromBase64(String base64) {
        Objects.requireNonNull(base64, "base64");
        if (base64.isBlank()) {
            throw new IllegalArgumentException("the Argon2id pepper must not be blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the Argon2id pepper is not valid Base64", e);
        }
        if (decoded.length != LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "the Argon2id pepper must decode to exactly " + LENGTH_BYTES + " bytes, was "
                            + decoded.length + " bytes");
        }
        return new Argon2Pepper(decoded);
    }

    /** Returns a defensive copy: callers must never be able to mutate the pepper this instance holds. */
    public byte[] value() {
        return value.clone();
    }

    /** Redacted deliberately: this value is the Argon2id pepper itself. */
    @Override
    public String toString() {
        return "Argon2Pepper[REDACTED]";
    }
}

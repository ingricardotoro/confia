package com.confia.identity.domain;

import java.text.Normalizer;
import java.util.Objects;

/**
 * A password presented in clear text, held only as long as the current hash-or-verify call needs
 * it. {@code toString()} is deliberately redacted (CLAUDE.md, regla 11; specs/identity/spec.md,
 * "Ningún secreto de este módulo es observable en registros, excepciones ni pruebas"): this is
 * exactly the "contraseña en claro" that requirement names, so this is a plain final class with an
 * explicit override, never a record — whose generated {@code toString()} would print it verbatim
 * (design.md, decision 10, point 1).
 *
 * <p><b>NFKC-normalized before hashing</b> (design.md, decision 11, "La normalización de la
 * contraseña"): without it, an account created with a password typed in one Unicode compatibility
 * form could stop authenticating the moment a client sends the same characters in another,
 * canonically-equivalent form — the same reasoning {@link LoginIdentifier} already applies to the
 * login identifier itself.
 *
 * <p><b>The 1024-character bound is a technical guard, not a password-policy rule</b> — §4.2's
 * password policy is out of this change's scope entirely. Without some bound, a password of
 * several megabytes is a denial-of-service attack aimed squarely at Argon2id's own memory-hard
 * cost, the same "technical guard, not a fiscal rule" distinction the RTN's own column comment
 * already draws for an unrelated column.
 */
public final class PlainPassword {

    private static final int MAX_LENGTH = 1024;

    private final String value;

    private PlainPassword(String value) {
        this.value = value;
    }

    /**
     * NFKC-normalizes {@code raw} and returns the resulting {@link PlainPassword}.
     *
     * @throws NullPointerException if {@code raw} is {@code null} — a programming error, not a
     *     business condition (ADR-0019, point 6)
     * @throws IllegalArgumentException if {@code raw} is empty, or if the normalized value exceeds
     *     {@value #MAX_LENGTH} characters
     */
    public static PlainPassword of(String raw) {
        Objects.requireNonNull(raw, "raw");
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("a password must not be empty");
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        if (normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a password must not exceed " + MAX_LENGTH + " characters, was "
                            + normalized.length() + " characters");
        }
        return new PlainPassword(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlainPassword that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** Redacted deliberately: {@code value} is the clear-text password itself. */
    @Override
    public String toString() {
        return "PlainPassword[REDACTED]";
    }
}

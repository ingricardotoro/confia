package com.confia.identity.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * The normalized form of the login identifier (an email address, today) an attempt presents,
 * before it is looked up, hashed into an {@code IdentifierFingerprint} (PR C2), or written to
 * {@code identity_staff_account.email} (design.md, decision 11).
 *
 * <p><b>Normalization, applied exactly once, at this boundary.</b> Trim leading and trailing
 * whitespace, Unicode normalization form NFKC, then lowercase with {@link Locale#ROOT}. Everything
 * downstream — the fingerprint, the repository lookup, the stored column — works with the value
 * already normalized; nothing normalizes it a second time. Treating the identifier as
 * case-insensitive is a declared decision, not an oversight: RFC 5321 allows a case-sensitive
 * local part, and in practice no real provider exploits that (design.md, decision 11).
 *
 * <p><b>Length, a technical guard, not a business rule.</b> {@code identity_staff_account}'s own
 * {@code CHECK (char_length(email) BETWEEN 3 AND 320)} (design.md, decision 4) is mirrored here so
 * a malformed value fails in the application before it ever reaches a statement, with the same
 * bound the column enforces — 320 being RFC 5321's own maximum reassembled address length.
 *
 * <p><b>{@code CHECK (email !~ '[A-Z]')}, and not the collation-dependent {@code lower()}.</b>
 * {@link String#toLowerCase(Locale)} with {@link Locale#ROOT} does not agree with every database
 * collation for all of Unicode — the Turkish {@code İ} (U+0130) is the classic counterexample — so
 * the column's {@code CHECK} is a plain ASCII-range regular expression instead, independent of any
 * collation (design.md, decision 4, point 1). This class does not itself special-case any script:
 * the sonda that confirms NFKC-then-{@code toLowerCase(Locale.ROOT)} never reintroduces an ASCII
 * uppercase letter — across {@code İ}, {@code ß}, Greek and CJK samples — is a unit test of this
 * class, recorded in {@code apply-progress.md} (sonda S8, design.md §10).
 */
public record LoginIdentifier(String value) {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 320;

    public LoginIdentifier {
        Objects.requireNonNull(value, "value");
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "a normalized login identifier must be between " + MIN_LENGTH + " and "
                            + MAX_LENGTH + " characters long, was " + value.length());
        }
    }

    /**
     * Normalizes {@code raw} — trim, NFKC, lowercase with {@link Locale#ROOT} — and returns the
     * resulting {@link LoginIdentifier}. The only entry point that performs normalization; every
     * other constructor call in this codebase is expected to already hold a normalized value.
     *
     * @throws NullPointerException if {@code raw} is {@code null} — a programming error, not a
     *     business condition (ADR-0019, point 6)
     * @throws IllegalArgumentException if the normalized value falls outside the length a stored
     *     identifier may have
     */
    public static LoginIdentifier of(String raw) {
        Objects.requireNonNull(raw, "raw");
        String normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return new LoginIdentifier(normalized);
    }
}

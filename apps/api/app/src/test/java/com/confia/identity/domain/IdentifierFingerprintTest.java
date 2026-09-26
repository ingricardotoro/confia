package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link IdentifierFingerprint} construction guards: the keyed hash written to
 * {@code identity_login_backoff.identifier_hash} must match that column's own
 * {@code CHECK (identifier_hash ~ '^[0-9a-f]{64}$')} (design.md, decision 3), or a value the
 * application computed wrong would fail at the database instead of at construction time.
 */
class IdentifierFingerprintTest {

    private static final String VALID_64_HEX =
            "a".repeat(64);

    @Test
    void exposesTheValue() {
        IdentifierFingerprint fingerprint = new IdentifierFingerprint(VALID_64_HEX);

        assertThat(fingerprint.value()).isEqualTo(VALID_64_HEX);
    }

    @Test
    void rejectsANullValue() {
        assertThatThrownBy(() -> new IdentifierFingerprint(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsAValueShorterThan64Characters() {
        assertThatThrownBy(() -> new IdentifierFingerprint("a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAValueLongerThan64Characters() {
        assertThatThrownBy(() -> new IdentifierFingerprint("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnUppercaseHexCharacter() {
        assertThatThrownBy(() -> new IdentifierFingerprint("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonHexCharacter() {
        assertThatThrownBy(() -> new IdentifierFingerprint("g".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

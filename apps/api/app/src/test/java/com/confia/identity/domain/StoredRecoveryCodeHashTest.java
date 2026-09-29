package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link StoredRecoveryCodeHash} (column-encryption-and-mfa-totp design.md decision 9; CLAUDE.md,
 * regla 11), its own test from the start rather than one exercised only incidentally later —
 * {@code PlainTotpSecretTest}'s own Javadoc reports what happens when a secret-carrying class
 * relies on that instead.
 */
class StoredRecoveryCodeHashTest {

    private static final String STORED_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    @Test
    void constructorAcceptsAWellFormedArgon2idPhcString() {
        StoredRecoveryCodeHash hash = new StoredRecoveryCodeHash(STORED_HASH);

        assertThat(hash.value()).isEqualTo(STORED_HASH);
    }

    @Test
    void constructorRejectsAValueNotStartingWithTheArgon2idPrefix() {
        assertThatThrownBy(() -> new StoredRecoveryCodeHash("$bcrypt$2b$10$notargon2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$argon2id$");
        assertThatThrownBy(() -> new StoredRecoveryCodeHash(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * The redaction, collected as text rather than merely constructed (specs/identity/spec.md,
     * "eso se verifica por inspección del texto producido, nunca por confianza en el diseño").
     */
    @Test
    void toStringIsRedactedAndCarriesNoRenderingOfTheHash() {
        StoredRecoveryCodeHash hash = new StoredRecoveryCodeHash(STORED_HASH);

        String rendered = hash.toString();

        assertThat(rendered).isEqualTo("StoredRecoveryCodeHash[REDACTED]");
        assertThat(rendered)
                .as("the stored Argon2id hash must never survive in the text this object prints "
                        + "into a log")
                .doesNotContain(STORED_HASH);
    }
}

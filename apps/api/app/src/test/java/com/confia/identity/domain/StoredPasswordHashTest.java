package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link StoredPasswordHash} construction guards and redaction. This value IS "el hash Argon2id
 * resultante" specs/identity/spec.md's redaction requirement names explicitly, so its {@code
 * toString()} must never contain it (design.md, decision 10, points 1; CLAUDE.md regla 11).
 */
class StoredPasswordHashTest {

    private static final String VALID_PHC =
            "$argon2id$v=19$m=19456,t=3,p=1$c29tZXNhbHQ$dGhpc2lzYWZha2V0YWc";

    @Test
    void exposesTheStoredValue() {
        StoredPasswordHash hash = new StoredPasswordHash(VALID_PHC);

        assertThat(hash.value()).isEqualTo(VALID_PHC);
    }

    @Test
    void rejectsANullValue() {
        assertThatThrownBy(() -> new StoredPasswordHash(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsAValueThatIsNotAnArgon2idPhcString() {
        assertThatThrownBy(() -> new StoredPasswordHash("not-a-hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoEqualHashesAreEqualAndShareTheSameHashCode() {
        assertThat(new StoredPasswordHash(VALID_PHC)).isEqualTo(new StoredPasswordHash(VALID_PHC));
        assertThat(new StoredPasswordHash(VALID_PHC).hashCode())
                .isEqualTo(new StoredPasswordHash(VALID_PHC).hashCode());
    }

    @Test
    void toStringNeverContainsTheStoredHash() {
        StoredPasswordHash hash = new StoredPasswordHash(VALID_PHC);

        assertThat(hash.toString()).doesNotContain(VALID_PHC);
    }
}

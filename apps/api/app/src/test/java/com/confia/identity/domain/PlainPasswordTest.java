package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link PlainPassword} construction guards and redaction (design.md, decision 10, points 1-2;
 * specs/identity/spec.md, "Ningún secreto de este módulo es observable en registros, excepciones ni
 * pruebas").
 */
class PlainPasswordTest {

    @Test
    void exposesTheNormalizedValue() {
        PlainPassword password = PlainPassword.of("Segura#2026");

        assertThat(password.value()).isEqualTo("Segura#2026");
    }

    @Test
    void rejectsAnEmptyPassword() {
        assertThatThrownBy(() -> PlainPassword.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANullPassword() {
        assertThatThrownBy(() -> PlainPassword.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        String maxLength = "a".repeat(1024);

        PlainPassword password = PlainPassword.of(maxLength);

        assertThat(password.value()).hasSize(1024);
    }

    @Test
    void rejectsAPasswordLongerThanTheTechnicalGuardBound() {
        String tooLong = "a".repeat(1025);

        assertThatThrownBy(() -> PlainPassword.of(tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void appliesNfkcNormalizationBeforeAnythingElse() {
        // U+FF11 (FULLWIDTH DIGIT ONE) NFKC-normalizes to U+0031 ('1') — the standard example of a
        // compatibility-block character every NFKC implementation must fold.
        PlainPassword fromCompatibilityForm = PlainPassword.of("clave１segura");
        PlainPassword fromCanonicalForm = PlainPassword.of("clave1segura");

        assertThat(fromCompatibilityForm).isEqualTo(fromCanonicalForm);
    }

    @Test
    void twoEqualPasswordsAreEqualAndShareTheSameHashCode() {
        assertThat(PlainPassword.of("Segura#2026")).isEqualTo(PlainPassword.of("Segura#2026"));
        assertThat(PlainPassword.of("Segura#2026").hashCode())
                .isEqualTo(PlainPassword.of("Segura#2026").hashCode());
    }

    @Test
    void twoDifferentPasswordsAreNotEqual() {
        assertThat(PlainPassword.of("Segura#2026")).isNotEqualTo(PlainPassword.of("Otra#clave"));
    }

    @Test
    void isNeverEqualToNullOrAnUnrelatedType() {
        PlainPassword password = PlainPassword.of("Segura#2026");

        assertThat(password).isNotEqualTo(null);
        assertThat(password).isNotEqualTo("Segura#2026");
    }

    @Test
    void toStringNeverContainsTheClearTextPassword() {
        PlainPassword password = PlainPassword.of("Segura#2026");

        assertThat(password.toString()).doesNotContain("Segura#2026");
    }
}

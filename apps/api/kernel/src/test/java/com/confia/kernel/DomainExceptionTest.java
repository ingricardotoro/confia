package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contract test for {@link DomainException} (ADR-0019). Exercised through {@link TestException}, a
 * package-private concrete subclass that exists only for this test.
 */
class DomainExceptionTest {

    @Test
    void isAbstract() {
        assertThat(Modifier.isAbstract(DomainException.class.getModifiers())).isTrue();
    }

    @Test
    void extendsRuntimeExceptionSoItNeverForcesATryCatchOrAThrowsClause() {
        assertThat(RuntimeException.class).isAssignableFrom(DomainException.class);
    }

    @Test
    void codeReturnsTheCodeReceivedByTheConstructor() {
        TestException exception = new TestException("currency-mismatch", "technical message");

        assertThat(exception.code()).isEqualTo("currency-mismatch");
    }

    @Test
    void codeAcceptsASubjectConditionShapeWithMultipleHyphens() {
        TestException exception = new TestException("money-amount-out-of-range", "technical message");

        assertThat(exception.code()).isEqualTo("money-amount-out-of-range");
    }

    @Test
    void rejectsANullCode() {
        assertThatThrownBy(() -> new TestException(null, "technical message"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "Currency-Mismatch", // uppercase
        "-currency-mismatch", // leading hyphen
        "currency-mismatch-", // trailing hyphen
        "currency_mismatch", // underscore instead of hyphen
        "currency mismatch", // space
        "currency--mismatch", // empty segment
    })
    void rejectsAMalformedCode(String malformedCode) {
        assertThatThrownBy(() -> new TestException(malformedCode, "technical message"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsACodeLongerThanSixtyFourCharacters() {
        String tooLong = "a".repeat(65);

        assertThatThrownBy(() -> new TestException(tooLong, "technical message"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsACodeExactlySixtyFourCharactersLong() {
        String exactlySixtyFour = "a" + "a".repeat(63);

        TestException exception = new TestException(exactlySixtyFour, "technical message");

        assertThat(exception.code()).hasSize(64);
    }

    /** Package-private concrete subclass used only to exercise the abstract base. */
    private static final class TestException extends DomainException {
        TestException(String code, String message) {
            super(code, message);
        }
    }
}

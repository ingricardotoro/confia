package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.DomainException;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemCodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ProblemCodeException} (session-endpoints-cookie-and-csrf design.md decision 18): the one
 * exception a controller throws to end a request with a given code of the catalog. It is a domain
 * exception, so the single translator that already answers those answers it, and it carries nothing
 * but the code: no message a client could read, no stack trace to log.
 */
class ProblemCodeExceptionTest {

    @ParameterizedTest
    @EnumSource(ProblemCode.class)
    void carriesTheCodeOfTheCatalogAndNothingElse(ProblemCode code) {
        ProblemCodeException exception = new ProblemCodeException(code);

        assertThat(exception).isInstanceOf(DomainException.class);
        assertThat(exception.code()).isEqualTo(code.code());
        assertThat(exception.problem()).isEqualTo(code);
        assertThat(exception.getMessage()).isEqualTo("the request ended with " + code.code());
    }

    @Test
    void hasNoStackTraceBecauseItIsControlFlowOfTheEdge() {
        assertThat(new ProblemCodeException(ProblemCode.AUTHENTICATION_FAILED).getStackTrace())
                .isEmpty();
    }

    @Test
    void rejectsANullCode() {
        assertThatThrownBy(() -> new ProblemCodeException(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("code");
    }

    @Test
    void canCarryTheViolationsTheTranslatorListsInErrors() {
        ProblemCodeException exception = new ProblemCodeException(ProblemCode.INVALID_RESPONSIBILITY_SPLIT,
                java.util.List.of(new com.confia.shared.web.problem.FieldViolation("links", "sum-not-100")));

        assertThat(exception.problem()).isEqualTo(ProblemCode.INVALID_RESPONSIBILITY_SPLIT);
        assertThat(exception.code()).isEqualTo("invalid-responsibility-split");
        assertThat(exception.violations()).extracting("field", "reason").containsExactly(
                org.assertj.core.groups.Tuple.tuple("links", "sum-not-100"));
        assertThat(exception.getMessage()).doesNotContain("sum-not-100");
        assertThat(new ProblemCodeException(ProblemCode.FORBIDDEN).violations()).isEmpty();
    }

    @Test
    void rejectsNullViolations() {
        assertThatThrownBy(() -> new ProblemCodeException(ProblemCode.FORBIDDEN, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("violations");
    }
}

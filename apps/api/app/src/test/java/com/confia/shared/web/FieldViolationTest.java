package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.problem.FieldViolation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A violation is a value the client reads, so its two members are closed to whatever is not a
 * plain path and a plain constraint name (web-edge-foundations design.md, decision 9).
 */
class FieldViolationTest {

    @ParameterizedTest
    @ValueSource(strings = {"Not Blank", "NOT_BLANK!", "<script>", "-lead", "trail-", "a--b", ""})
    void aReasonThatIsNotKebabCaseIsJustInvalid(String constraint) {
        assertThat(new FieldViolation("f", constraint).reason()).isEqualTo("invalid");
    }

    @Test
    void aReasonLongerThanSixtyFourCharactersIsInvalidAndOneOfExactlySixtyFourIsKept() {
        assertThat(new FieldViolation("f", "a".repeat(64)).reason()).isEqualTo("a".repeat(64));
        assertThat(new FieldViolation("f", "a".repeat(65)).reason()).isEqualTo("invalid");
        assertThat(new FieldViolation("f", null).reason()).isEqualTo("invalid");
    }

    @Test
    void aFieldOutsideThePathAlphabetOrLongerThanTheLimitIsEmpty() {
        assertThat(new FieldViolation("items[].name-1_x", "size").field())
                .isEqualTo("items[].name-1_x");
        assertThat(new FieldViolation("a b", "size").field()).isEmpty();
        assertThat(new FieldViolation("<list element>", "size").field()).isEmpty();
        assertThat(new FieldViolation("a".repeat(128), "size").field()).hasSize(128);
        assertThat(new FieldViolation("a".repeat(129), "size").field()).isEmpty();
        assertThat(new FieldViolation(null, "size").field()).isEmpty();
    }
}

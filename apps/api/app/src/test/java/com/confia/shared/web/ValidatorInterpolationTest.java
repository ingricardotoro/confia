package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Constraint messages are never shown (web-edge-foundations design.md, decision 9), so the
 * validator of the real web edge has no use for an expression language: a message holding {@code
 * ${...}} must stay inert text: {@code ${validatedValue}} would otherwise put the rejected
 * value in a message.
 */
class ValidatorInterpolationTest {

    record Probe(@Size(max = 3, message = "${validatedValue}") String value) {
    }

    @Test
    void theValidatorDoesNotEvaluateAnExpressionInAMessage() {
        try (HarnessProcess process = HarnessProcess.start()) {
            Validator validator = process.bean(Validator.class);

            Set<ConstraintViolation<Probe>> violations = validator.validate(new Probe("SECRETVALUE"));

            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getMessage())
                    .as("with an EL interpolator the rejected value is substituted")
                    .doesNotContain("SECRETVALUE").contains("validatedValue");
        }
    }
}

package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The body and the answer of the two second-factor routes (session-endpoints-cookie-and-csrf design.md,
 * decision 13): a verification body carries exactly one proof and prints neither; an enrollment
 * answer carries the secret and the ten codes of the use case and prints neither.
 */
class SecondFactorRequestsTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"123456, null, true", "null, ABCDEFGHJK, true",
            "123456, ABCDEFGHJK, false", "null, null, false", "'', null, false", "null, '  ', false",
            "'', ABCDEFGHJK, true"})
    void exactlyOneProofIsRequired(String code, String recoveryCode, boolean valid) {
        var violations = VALIDATOR.validate(new SecondFactorVerificationRequest(code, recoveryCode));

        assertThat(violations.isEmpty()).isEqualTo(valid);
    }

    @Test
    void aProofLongerThanSixtyFourCharactersIsRefused() {
        assertThat(VALIDATOR.validate(new SecondFactorVerificationRequest("9".repeat(65), null)))
                .isNotEmpty();
        assertThat(VALIDATOR.validate(new SecondFactorVerificationRequest("9".repeat(64), null)))
                .isEmpty();
    }

    @Test
    void theRequestAndTheAnswerPrintNoSecret() {
        PlainTotpSecret secret = PlainTotpSecret.generate(new SecureRandom());
        List<PlainRecoveryCode> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            codes.add(PlainRecoveryCode.generate(new SecureRandom()));
        }
        Enrolled enrolled = new Enrolled(secret, codes, false);

        EnrollmentResponse response = EnrollmentResponse.of(enrolled);

        assertThat(response.secret()).isEqualTo(secret.base32());
        assertThat(response.recoveryCodes()).containsExactlyElementsOf(
                codes.stream().map(PlainRecoveryCode::value).toList());
        assertThat(response.toString()).doesNotContain(secret.base32())
                .doesNotContain(codes.get(0).value());
        assertThat(new SecondFactorVerificationRequest("987654", null).toString())
                .doesNotContain("987654");
    }
}

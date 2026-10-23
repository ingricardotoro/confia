package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The answer of the enrollment route (session-endpoints-cookie-and-csrf design.md, decision 13): it
 * carries the secret and the ten codes of the use case and prints neither.
 */
class SecondFactorRequestsTest {

    @Test
    void theAnswerPrintsNoSecretAndCarriesTheSecretAndTheCodesOfTheUseCase() {
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
    }
}

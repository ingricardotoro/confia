package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link TotpVerificationPolicy} (column-encryption-and-mfa-totp design.md, decision 7):
 * tolerance-window acceptance and counter-based anti-repetition, over a fixed clock — no
 * repository, no I/O, no real waiting (specs/identity/spec.md, requirement "Inscripción y
 * verificación del segundo factor TOTP...").
 */
class TotpVerificationPolicyTest {

    /** Not a real secret: a fixed byte array for a pure-domain test, never persisted. */
    private static final byte[] SECRET =
            "twenty-byte-test-totp-secret-value".getBytes(StandardCharsets.UTF_8);

    private static final Duration PERIOD = Duration.ofSeconds(30);
    private static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    private final TotpVerificationPolicy policy = new TotpVerificationPolicy();

    @Test
    void aCodeOneCounterBehindNowIsAcceptedWithinTheToleranceWindow() {
        long expectedCounter = TotpAlgorithm.counterFor(NOW, PERIOD);
        long priorPeriodCounter = expectedCounter - 1;
        TotpCode presented = TotpAlgorithm.generate(SECRET, priorPeriodCounter);

        Optional<Long> matched =
                policy.matchingCounter(SECRET, -1L, NOW, PERIOD, presented);

        assertThat(matched).contains(priorPeriodCounter);
    }

    /**
     * The escenario publicado "Un código ya aceptado no puede reutilizarse, aunque siga siendo
     * matemáticamente válido" (specs/identity/spec.md): the same code, mathematically valid for a
     * counter that is now less than or equal to the last accepted one, must be rejected — this is
     * the property that distinguishes {@code matchingCounter} from a plain lookup over the
     * tolerance window.
     */
    @Test
    void aCodeWhoseCounterWasAlreadyAcceptedIsRejectedEvenThoughItRemainsMathematicallyValid() {
        long expectedCounter = TotpAlgorithm.counterFor(NOW, PERIOD);
        TotpCode presented = TotpAlgorithm.generate(SECRET, expectedCounter);

        Optional<Long> matched =
                policy.matchingCounter(SECRET, expectedCounter, NOW, PERIOD, presented);

        assertThat(matched).isEmpty();
    }

    @Test
    void anUnrelatedCodeOutsideTheToleranceWindowIsRejected() {
        long expectedCounter = TotpAlgorithm.counterFor(NOW, PERIOD);
        TotpCode presented = TotpAlgorithm.generate(SECRET, expectedCounter + 5);

        Optional<Long> matched =
                policy.matchingCounter(SECRET, -1L, NOW, PERIOD, presented);

        assertThat(matched).isEmpty();
    }
}

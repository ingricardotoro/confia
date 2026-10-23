package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.SecondFactorOutcome.Rejected;
import com.confia.identity.application.SecondFactorOutcome.Started;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.SecondFactorPurpose;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The values {@link CompleteSecondFactor} takes and returns (session-endpoints-cookie-and-csrf design.md,
 * decisions 10 and 13): a command that carries exactly one proof and prints neither, a decision whose
 * delay cannot be negative, and an outcome that either holds a session or says nothing. They are plain
 * Java, so these tests need no database.
 */
class CompleteSecondFactorViewsTest {

    private static final UUID ACCOUNT = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");
    private static final UUID INSTITUTION = UUID.fromString("c3a8e9a0-5b1e-4f7e-9d0e-2b6f0a1c4d11");

    @Test
    void theCommandCarriesExactlyOneProof() {
        assertThatThrownBy(() -> new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION,
                SecondFactorPurpose.VERIFY, "123456", "ABCDEFGHJK", null, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION,
                SecondFactorPurpose.VERIFY, null, null, null, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one");

        assertThat(new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION, SecondFactorPurpose.ENROLL,
                "123456", null, null, null, "").totpCode()).isEqualTo("123456");
        assertThat(new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION, SecondFactorPurpose.VERIFY,
                null, "ABCDEFGHJK", null, null, "").recoveryCode()).isEqualTo("ABCDEFGHJK");
    }

    @Test
    void everyComponentButTheProofAndTheOriginIsRequired() {
        assertThatThrownBy(() -> new CompleteSecondFactorCommand(null, INSTITUTION,
                SecondFactorPurpose.VERIFY, "123456", null, null, null, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompleteSecondFactorCommand(ACCOUNT, null,
                SecondFactorPurpose.VERIFY, "123456", null, null, null, ""))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION, null,
                "123456", null, null, null, "")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION,
                SecondFactorPurpose.VERIFY, "123456", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theCommandPrintsNeitherProofNorTheOrigin() {
        String withTotp = new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION,
                SecondFactorPurpose.VERIFY, "987654", null, ClientAddress.parseLiteral("203.0.113.7"),
                "agente-uno", "req-1").toString();
        String withRecoveryCode = new CompleteSecondFactorCommand(ACCOUNT, INSTITUTION,
                SecondFactorPurpose.VERIFY, null, "QWERTYUIOP", null, null, "req-2").toString();

        assertThat(withTotp).doesNotContain("987654").doesNotContain("203.0.113.7")
                .doesNotContain("agente-uno").contains("REDACTED").contains(ACCOUNT.toString())
                .contains("purpose=VERIFY").contains("req-1");
        assertThat(withRecoveryCode).doesNotContain("QWERTYUIOP").contains("REDACTED");
    }

    @Test
    void theDecisionRefusesANegativeDelayAndAMissingOutcome() {
        assertThatThrownBy(() -> new CompleteSecondFactorDecision(new Rejected(),
                Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompleteSecondFactorDecision(null, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompleteSecondFactorDecision(new Rejected(), null))
                .isInstanceOf(NullPointerException.class);
        assertThat(new CompleteSecondFactorDecision(new Rejected(), Duration.ZERO).requiredDelay())
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void aStartedOutcomeHoldsTheSessionAndPrintsNoToken() {
        SessionGrant grant = new SessionGrant("acceso.firmado.token", Instant.parse("2026-10-22T10:10:00Z"),
                "refresco-en-claro", Instant.parse("2026-10-22T20:00:00Z"),
                Instant.parse("2026-10-22T11:00:00Z"));

        Started started = new Started(grant);

        assertThat(started.session()).isSameAs(grant);
        assertThat(started.toString()).doesNotContain("acceso.firmado.token")
                .doesNotContain("refresco-en-claro");
        assertThatThrownBy(() -> new Started(null)).isInstanceOf(NullPointerException.class);
    }
}

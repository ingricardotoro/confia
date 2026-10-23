package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.SecondFactorChallenge.Kind;
import com.confia.shared.security.ClientAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The views and the command of the login use case (session-endpoints-cookie-and-csrf design.md
 * decisions 10 and 11): classes of {@code identity.application} the web layer can read without a
 * token type, which print redacted because they carry secrets, and a command that never prints the
 * password it carries.
 */
class LoginViewsTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final String ACCESS = "access-token-text-that-must-not-print";
    private static final String REFRESH = "refresh-token-text-that-must-not-print";
    private static final String SECOND_FACTOR = "restricted-token-text-that-must-not-print";
    private static final String PASSWORD = "contrasena-que-no-debe-imprimirse";

    @Test
    void aSessionGrantExposesItsFiveValues() {
        SessionGrant grant = new SessionGrant(ACCESS, NOW.plusSeconds(600), REFRESH,
                NOW.plus(Duration.ofHours(12)), NOW.plus(Duration.ofMinutes(30)));

        assertThat(grant.accessToken()).isEqualTo(ACCESS);
        assertThat(grant.accessTokenExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(grant.refreshToken()).isEqualTo(REFRESH);
        assertThat(grant.sessionExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(grant.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    @Test
    void aSessionGrantPrintsNeitherTokenAndIsNotARecord() {
        SessionGrant grant = new SessionGrant(ACCESS, NOW, REFRESH, NOW, NOW);

        assertThat(grant.toString()).doesNotContain(ACCESS).doesNotContain(REFRESH)
                .contains("REDACTED");
        assertThat(SessionGrant.class.isRecord()).as("a record prints every component").isFalse();
    }

    @Test
    void aSessionGrantRejectsMissingValues() {
        assertThatThrownBy(() -> new SessionGrant(null, NOW, REFRESH, NOW, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("accessToken");
        assertThatThrownBy(() -> new SessionGrant(ACCESS, null, REFRESH, NOW, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("accessTokenExpiresAt");
        assertThatThrownBy(() -> new SessionGrant(ACCESS, NOW, null, NOW, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("refreshToken");
        assertThatThrownBy(() -> new SessionGrant(ACCESS, NOW, REFRESH, null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("sessionExpiresAt");
        assertThatThrownBy(() -> new SessionGrant(ACCESS, NOW, REFRESH, NOW, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("idleExpiresAt");
    }

    @Test
    void aSecondFactorChallengeExposesItsThreeValues() {
        SecondFactorChallenge challenge = new SecondFactorChallenge(Kind.ENROLLMENT_REQUIRED,
                SECOND_FACTOR, NOW.plusSeconds(300));

        assertThat(challenge.kind()).isEqualTo(Kind.ENROLLMENT_REQUIRED);
        assertThat(challenge.token()).isEqualTo(SECOND_FACTOR);
        assertThat(challenge.expiresAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(Kind.values()).containsExactly(Kind.VERIFICATION_REQUIRED, Kind.ENROLLMENT_REQUIRED);
    }

    @Test
    void aSecondFactorChallengePrintsNoTokenAndIsNotARecord() {
        SecondFactorChallenge challenge = new SecondFactorChallenge(Kind.VERIFICATION_REQUIRED,
                SECOND_FACTOR, NOW);

        assertThat(challenge.toString()).doesNotContain(SECOND_FACTOR).contains("REDACTED")
                .contains("VERIFICATION_REQUIRED");
        assertThat(SecondFactorChallenge.class.isRecord()).isFalse();
    }

    @Test
    void aSecondFactorChallengeRejectsMissingValues() {
        assertThatThrownBy(() -> new SecondFactorChallenge(null, SECOND_FACTOR, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("kind");
        assertThatThrownBy(() -> new SecondFactorChallenge(Kind.VERIFICATION_REQUIRED, null, NOW))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("token");
        assertThatThrownBy(
                () -> new SecondFactorChallenge(Kind.VERIFICATION_REQUIRED, SECOND_FACTOR, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("expiresAt");
    }

    @Test
    void theOutcomesCarryTheirViewsAndPrintNothingSecret() {
        SessionGrant grant = new SessionGrant(ACCESS, NOW, REFRESH, NOW, NOW);
        SecondFactorChallenge challenge = new SecondFactorChallenge(Kind.VERIFICATION_REQUIRED,
                SECOND_FACTOR, NOW);

        LoginOutcome started = new LoginOutcome.Started(grant);
        LoginOutcome challenged = new LoginOutcome.ChallengeIssued(challenge);

        assertThat(((LoginOutcome.Started) started).session()).isSameAs(grant);
        assertThat(((LoginOutcome.ChallengeIssued) challenged).challenge()).isSameAs(challenge);
        assertThat(started.toString()).doesNotContain(ACCESS).doesNotContain(REFRESH);
        assertThat(challenged.toString()).doesNotContain(SECOND_FACTOR);
        assertThatThrownBy(() -> new LoginOutcome.Started(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoginOutcome.ChallengeIssued(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aDecisionCarriesItsOutcomeAndAWaitThatIsNeverNegative() {
        LoginDecision decision = new LoginDecision(new LoginOutcome.Failed(), Duration.ofSeconds(2));

        assertThat(decision.outcome()).isEqualTo(new LoginOutcome.Failed());
        assertThat(decision.requiredDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThatThrownBy(() -> new LoginDecision(null, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LoginDecision(new LoginOutcome.Failed(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(
                () -> new LoginDecision(new LoginOutcome.Failed(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aLoginCommandNeverPrintsThePasswordNorTheOrigin() {
        LoginCommand command = new LoginCommand("ana@colegio.edu.hn", PASSWORD,
                ClientAddress.parseLiteral("203.0.113.9"), "agente-prueba",
                "7f2a0c52-3d5e-4b8c-9a61-0f4c2e1d8b33");

        assertThat(command.toString()).doesNotContain(PASSWORD).doesNotContain("203.0.113.9")
                .doesNotContain("agente-prueba").contains("ana@colegio.edu.hn").contains("REDACTED");
    }

    @Test
    void aLoginCommandAcceptsNoOriginButNeverNoCredentials() {
        LoginCommand withoutOrigin = new LoginCommand("ana@colegio.edu.hn", PASSWORD, null, null, "");

        assertThat(withoutOrigin.clientAddress()).isNull();
        assertThat(withoutOrigin.userAgent()).isNull();
        assertThatThrownBy(() -> new LoginCommand(null, PASSWORD, null, null, ""))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("identifier");
        assertThatThrownBy(() -> new LoginCommand("ana@colegio.edu.hn", null, null, null, ""))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("password");
        assertThatThrownBy(() -> new LoginCommand("ana@colegio.edu.hn", PASSWORD, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("requestId");
    }

    @Test
    void anAuthenticationCommandCarriesTheOriginOfTheRequestAndPrintsNone() {
        ClientAddress address = ClientAddress.parseLiteral("203.0.113.9");

        AuthenticationCommand command = new AuthenticationCommand("ana@colegio.edu.hn", PASSWORD,
                address, "agente-prueba");

        assertThat(command.clientAddress()).isEqualTo(address);
        assertThat(command.userAgent()).isEqualTo("agente-prueba");
        assertThat(command.toString()).doesNotContain(PASSWORD).doesNotContain("203.0.113.9")
                .doesNotContain("agente-prueba");
    }

    @Test
    void anAuthenticationCommandWithoutOriginIsAccepted() {
        AuthenticationCommand twoArguments = new AuthenticationCommand("ana@colegio.edu.hn", PASSWORD);
        AuthenticationCommand explicit = new AuthenticationCommand("ana@colegio.edu.hn", PASSWORD,
                null, null);

        assertThat(twoArguments).isEqualTo(explicit);
        assertThat(twoArguments.clientAddress()).isNull();
        assertThat(twoArguments.userAgent()).isNull();
    }
}

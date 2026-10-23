package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.application.SecondFactorOutcome.Started;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.SecondFactorPurpose;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The catalogue of reasons for revoking a family is closed and every reason has a producer
 * (session-endpoints-cookie-and-csrf specs/identity, requirement "El catálogo de motivos de
 * revocación es cerrado y tiene cinco motivos, cada uno con su productor"; scenario I203): each of
 * the five flows, run through its real use case against a real PostgreSQL, leaves the family it
 * affected with its own reason, and the five words are all the words of {@link
 * SessionRevocationReason}. The detail of each flow is asserted by the tests of the use case; this
 * one is the line that fails if a reason loses its producer.
 */
class RevocationProducersIT extends RevocationIntegrationTest {

    private static final String T0 = "2026-10-22T10:00:00Z";
    private static final String LATER = "2026-10-22T10:20:00Z";

    @Test
    void theFiveFlowsLeaveTheirFiveReasonsAndNoReasonIsLeftWithoutAProducer() {
        List<String> produced = List.of(reuseDetected(), passwordChange(), logout(),
                logoutOfEverySession(),
                mfaChange(), bindingChanged());

        assertThat(produced).containsExactly("reuse_detected", "password_change", "logout", "logout",
                "mfa_change", "binding_changed");
        assertThat(Arrays.stream(SessionRevocationReason.values())
                .map(SessionRevocationReason::code).toList())
                .as("the five words of the catalogue are exactly the ones produced")
                .containsExactlyInAnyOrderElementsOf(produced.stream().distinct().toList());
    }

    private String reuseDetected() {
        StaffAccountId account = seedStaffAccount("reuso@colegio.edu.hn", CURRENT_PASSWORD, false);
        IssuedSession first = issue(T0, account);
        rotate("2026-10-22T10:01:00Z", first.refreshToken().value(), AGENT_ONE);
        rotate(LATER, first.refreshToken().value(), AGENT_ONE);
        return reasonOf(first.sessionId());
    }

    private String passwordChange() {
        StaffAccountId account = seedStaffAccount("clave@colegio.edu.hn", CURRENT_PASSWORD, false);
        IssuedSession session = issue(T0, account);
        String token = issueTokenAt(account, T0).value();
        resetAt(token, "Lempira y maíz 2026", new SecondFactorProof.None(), "2026-10-22T10:05:00Z");
        return reasonOf(session.sessionId());
    }

    private String logout() {
        StaffAccountId account = seedStaffAccount("salida@colegio.edu.hn", CURRENT_PASSWORD, false);
        IssuedSession session = issue(T0, account);
        endCurrentSession(LATER).execute(session.refreshToken().value(),
                UUID.randomUUID().toString());
        return reasonOf(session.sessionId());
    }

    private String logoutOfEverySession() {
        StaffAccountId account = seedStaffAccount("todas@colegio.edu.hn", CURRENT_PASSWORD, false);
        IssuedSession session = issue(T0, account);
        endAllSessions(LATER).execute(actorOf(institutionId, account, session.sessionId()),
                UUID.randomUUID().toString());
        return reasonOf(session.sessionId());
    }

    private String mfaChange() {
        StaffAccountId account = seedStaffAccount("factor@colegio.edu.hn", CURRENT_PASSWORD, true);
        Enrolled enrolled = enrollWithoutVerifying(account);
        IssuedSession previous = issue("2026-10-22T08:00:00Z", account);
        CompleteSecondFactorDecision decision = completeSecondFactor(LATER)
                .execute(new CompleteSecondFactorCommand(account.value(), institutionId.value(),
                        SecondFactorPurpose.ENROLL, validCodeAt(enrolled.secret(), LATER).value(),
                        null, null, AGENT_ONE, UUID.randomUUID().toString()));
        assertThat(decision.outcome()).isInstanceOf(Started.class);
        return reasonOf(previous.sessionId());
    }

    private String bindingChanged() {
        StaffAccountId account = seedStaffAccount("vinculo@colegio.edu.hn", CURRENT_PASSWORD, false);
        IssuedSession session = issueSession(T0).execute(context(),
                commandFrom(account, false, AGENT_ONE, "203.0.113.9"));
        rotation(fixedClock(LATER), new JooqAuditLogWriter(dsl), institutionId)
                .execute(new RotateRefreshTokenCommand(session.refreshToken().value(), AGENT_TWO,
                        ClientAddress.parseLiteral("198.51.100.7"), UUID.randomUUID().toString()));
        return reasonOf(session.sessionId());
    }

    private String reasonOf(UUID familyId) {
        return familyRow(familyId).get("revoked_reason", String.class);
    }

    private CompleteSecondFactor completeSecondFactor(String instant) {
        Clock clock = fixedClock(instant);
        AuditLogWriter writer = new JooqAuditLogWriter(dsl);
        VerifyTotpCode verifyTotp = new VerifyTotpCode(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqTotpVerificationBackoffStore(dsl),
                encryptionService(), writer, clock);
        ConsumeRecoveryCode consume = new ConsumeRecoveryCode(transactionRunner(),
                new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), PEPPER), writer, clock);
        return new CompleteSecondFactor(transactionRunner(), new JooqStaffAccountRepository(dsl),
                new JooqTotpCredentialRepository(dsl), verifyTotp, consume,
                new JooqTotpVerificationBackoffStore(dsl), new JooqAccountSessionRevoker(dsl),
                issueSession(clock, writer, new JooqRefreshTokenRepository(dsl)), writer, clock);
    }
}

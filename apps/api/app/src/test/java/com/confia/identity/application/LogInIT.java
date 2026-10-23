package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.identity.application.SecondFactorChallenge.Kind;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.InMemoryLoginFailureSpread;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.observability.events.SecurityEventLog;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.token.MfaPurpose;
import com.confia.shared.security.token.MfaTokenClaims;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link LogIn} end to end against a real PostgreSQL and the real adapters
 * (session-endpoints-cookie-and-csrf design.md decisions 10, 11 and 12; scenarios I74, I75, I164 to
 * I166, I205, I206 and I224 of the use case, and I87 to I90 through the issuance it composes). The
 * three outcomes, the single transaction that holds the authentication, the family and the entries,
 * the restricted token, the spread signal and the origin of the request.
 */
class LogInIT extends SessionIssuanceIntegrationTest {

    private static final String EIGHT = "2026-10-12T08:00:00Z";
    private static final String CLIENT_IP = "203.0.113.9";
    private static final String AGENT = "agente-prueba";
    private static final String WRONG = "contrasena-incorrecta";
    private static final String SIGNAL = "identity.login.failure_spread_detected";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final InMemoryLoginFailureSpread spread = new InMemoryLoginFailureSpread(100);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger eventLogger = (Logger) LoggerFactory.getLogger(SecurityEventLog.class);

    @BeforeEach
    void captureTheSecurityEvents() {
        logged.start();
        eventLogger.addAppender(logged);
    }

    @AfterEach
    void releaseTheSecurityEvents() {
        eventLogger.detachAppender(logged);
    }

    // --- The three outcomes ---

    @Test
    void aCorrectPasswordWithoutSecondFactorStartsASessionWithItsFamilyAndBothEntries() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        LoginDecision decision = logIn(EIGHT).execute(command(ANA, CURRENT_PASSWORD));

        assertThat(decision.requiredDelay()).isEqualTo(Duration.ZERO);
        assertThat(decision.outcome()).isInstanceOf(LoginOutcome.Started.class);
        SessionGrant grant = ((LoginOutcome.Started) decision.outcome()).session();
        AuthenticatedActor actor = verifierAt(EIGHT).verifyAccess(grant.accessToken());
        assertThat(actor.accountId()).isEqualTo(ana.value());
        assertThat(actor.institutionId()).isEqualTo(institutionId.value());
        assertThat(actor.methods()).containsExactly(AuthenticationMethod.PASSWORD);
        assertThat(grant.accessTokenExpiresAt()).isEqualTo(Instant.parse("2026-10-12T08:10:00Z"));
        assertThat(grant.sessionExpiresAt()).isEqualTo(Instant.parse("2026-10-12T20:00:00Z"));
        assertThat(grant.idleExpiresAt()).isEqualTo(Instant.parse("2026-10-12T08:30:00Z"));
        assertThat(grant.refreshToken()).hasSize(43).matches("^[A-Za-z0-9_-]{43}$");
        Record family = familyOf(actor.sessionId());
        assertThat(family.get("account_id", UUID.class)).isEqualTo(ana.value());
        assertThat(family.get("second_factor_verified", Boolean.class)).isFalse();
        assertThat(count("identity_refresh_token")).isEqualTo(1);
        assertThat(auditRows("identity.login.succeeded")).hasSize(1);
        assertThat(auditRows("identity.session.started")).singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo(actor.sessionId().toString()));
        assertThat(auditRows(SIGNAL)).isEmpty();
    }

    @Test
    void aCorrectPasswordOfAnAccountWithAnEnrolledSecondFactorGetsTheVerificationTokenAndNoSession() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, true);
        enroll(ana);

        LoginDecision decision = logIn(EIGHT).execute(command(ANA, CURRENT_PASSWORD));

        assertThat(decision.outcome()).isInstanceOf(LoginOutcome.ChallengeIssued.class);
        SecondFactorChallenge challenge = ((LoginOutcome.ChallengeIssued) decision.outcome())
                .challenge();
        assertThat(challenge.kind()).isEqualTo(Kind.VERIFICATION_REQUIRED);
        MfaTokenClaims claims = verifierAt(EIGHT).verifyMfa(challenge.token());
        assertThat(claims.purpose()).isEqualTo(MfaPurpose.MFA_VERIFY);
        assertThat(claims.accountId()).isEqualTo(ana.value());
        assertThat(claims.institutionId()).isEqualTo(institutionId.value());
        assertThat(challenge.expiresAt()).isEqualTo(Instant.parse("2026-10-12T08:05:00Z"));
        assertThat(claims.expiresAt()).isEqualTo(challenge.expiresAt());
        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(count("identity_refresh_token")).isZero();
        assertThat(auditRows("identity.login.second_factor_required")).hasSize(1);
        assertThat(auditRows("identity.session.started")).isEmpty();
    }

    @Test
    void aCorrectPasswordOfAnAccountThatMustEnrollGetsTheEnrollmentTokenAndNoSession() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, true);

        LoginDecision decision = logIn(EIGHT).execute(command(ANA, CURRENT_PASSWORD));

        SecondFactorChallenge challenge = ((LoginOutcome.ChallengeIssued) decision.outcome())
                .challenge();
        assertThat(challenge.kind()).isEqualTo(Kind.ENROLLMENT_REQUIRED);
        MfaTokenClaims claims = verifierAt(EIGHT).verifyMfa(challenge.token());
        assertThat(claims.purpose()).isEqualTo(MfaPurpose.MFA_ENROLL);
        assertThat(claims.accountId()).isEqualTo(ana.value());
        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(auditRows("identity.login.second_factor_enrollment_required")).hasSize(1);
    }

    @Test
    void emittingTenRestrictedTokensCreatesNoFamilyAndNoRefreshToken() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, true);
        long familiesBefore = count("identity_refresh_token_family");
        long tokensBefore = count("identity_refresh_token");

        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(logIn(EIGHT).execute(command(ANA, CURRENT_PASSWORD)).outcome())
                    .isInstanceOf(LoginOutcome.ChallengeIssued.class);
        }

        assertThat(count("identity_refresh_token_family")).isEqualTo(familiesBefore);
        assertThat(count("identity_refresh_token")).isEqualTo(tokensBefore);
    }

    @Test
    void aWrongPasswordAndAnUnknownIdentifierBothFailWithoutASession() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        LoginDecision wrong = logIn(EIGHT).execute(command(ANA, WRONG));
        LoginDecision unknown = logIn(EIGHT).execute(command("nadie@colegio.edu.hn", WRONG));

        assertThat(wrong.outcome()).isEqualTo(new LoginOutcome.Failed());
        assertThat(unknown.outcome()).isEqualTo(new LoginOutcome.Failed());
        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(auditRows("identity.login.failed")).hasSize(2);
        assertThat(auditRows("identity.session.started")).isEmpty();
    }

    @Test
    void aCorrectPasswordDuringTheBackoffStartsASessionAndCarriesTheDelay() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        logIn("2026-10-12T08:00:00Z").execute(command(ANA, WRONG));
        logIn("2026-10-12T08:00:20Z").execute(command(ANA, WRONG));
        logIn("2026-10-12T08:00:40Z").execute(command(ANA, WRONG));

        LoginDecision decision = logIn("2026-10-12T08:00:50Z").execute(command(ANA, CURRENT_PASSWORD));

        assertThat(decision.outcome()).isInstanceOf(LoginOutcome.Started.class);
        assertThat(decision.requiredDelay()).isEqualTo(Duration.ofSeconds(2));
    }

    // --- One transaction ---

    @Test
    void anEntryThatFailsAfterTheFamilyIsWrittenRollsBackTheFamilyTheEntriesAndTheBackoff() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        assertThatThrownBy(() -> logIn(EIGHT, failingOn("identity.session.started"), spread,
                () -> institutionId).execute(command(ANA, CURRENT_PASSWORD)))
                .isInstanceOf(IllegalStateException.class).hasMessage("the entry could not be written");

        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(count("identity_refresh_token")).isZero();
        assertThat(auditRows("identity.login.succeeded"))
                .as("the entry of the authentication is rolled back with the family").isEmpty();
        assertThat(auditRows("identity.session.started")).isEmpty();
        assertThat(count("identity_login_backoff")).isZero();
    }

    @Test
    void aTokenThatCannotBeWrittenRollsBackTheAuthenticationEntryToo() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        RefreshTokenRepository failing = withInsert(new JooqRefreshTokenRepository(dsl),
                (institution, row) -> {
                    throw new IllegalStateException("the token could not be written");
                });

        assertThatThrownBy(() -> logIn(EIGHT, new JooqAuditLogWriter(dsl), failing, spread,
                () -> institutionId).execute(command(ANA, CURRENT_PASSWORD)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(auditRows("identity.login.succeeded")).isEmpty();
    }

    @Test
    void anInstitutionThatChangesBetweenTheReadAndTheGuardIsAWiringDefectAndWritesNothing() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        AtomicInteger reads = new AtomicInteger();
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        LoginInstitutionProvider unstable = () -> reads.getAndIncrement() == 0 ? institutionId : other;

        assertThatThrownBy(() -> logIn(EIGHT, new JooqAuditLogWriter(dsl), spread, unstable)
                .execute(command(ANA, CURRENT_PASSWORD)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("institution");

        assertThat(count("identity_login_backoff")).isZero();
        assertThat(auditRows()).isEmpty();
    }

    // --- The spread signal ---

    @Test
    void theFifthDistinctIdentifierWritesTheSignalInTheSameTransactionAndWarnsAfterCommit() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        String requestId = UUID.randomUUID().toString();
        logIn("2026-10-12T10:00:00Z").execute(command(ANA, WRONG));
        for (int unknown = 1; unknown <= 3; unknown++) {
            logIn("2026-10-12T10:0" + unknown + ":00Z")
                    .execute(command("intruso" + unknown + "@colegio.edu.hn", WRONG));
        }
        assertThat(auditRows(SIGNAL)).as("four distinct identifiers raise nothing").isEmpty();
        assertThat(logged.list).isEmpty();

        LoginDecision decision = logIn("2026-10-12T10:06:00Z").execute(
                new LoginCommand("intruso4@colegio.edu.hn", WRONG,
                        ClientAddress.parseLiteral(CLIENT_IP), AGENT, requestId));

        assertThat(decision.outcome()).as("the signal never changes the answer")
                .isEqualTo(new LoginOutcome.Failed());
        assertThat(auditRows(SIGNAL)).singleElement().satisfies(row -> {
            assertThat(row.entityType()).isEqualTo("identity.client_address");
            assertThat(row.entityId()).isEqualTo(CLIENT_IP);
            assertThat(row.outcome()).isEqualTo("denied");
            assertThat(row.requestId()).isEqualTo(UUID.fromString(requestId));
            assertThat(JSON.readTree(row.afterValue()))
                    .isEqualTo(JSON.readTree("{\"distinctIdentifiers\":5,\"windowSeconds\":600}"));
            assertThat(row.toString()).doesNotContain("intruso4").doesNotContain(ANA)
                    .doesNotContain(fingerprintOf("intruso4@colegio.edu.hn"))
                    .doesNotContain(fingerprintOf(ANA));
        });
        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).isEqualTo(SIGNAL);
            assertThat(event.getKeyValuePairs()).singleElement().satisfies(pair -> {
                assertThat(pair.key).isEqualTo("requestId");
                assertThat(pair.value).isEqualTo(requestId);
            });
            assertThat(event.toString()).doesNotContain(CLIENT_IP).doesNotContain("intruso4");
        });
    }

    @Test
    void fiveFailuresAgainstOneIdentifierAndFourDistinctOnesRaiseNothing() {
        for (int attempt = 0; attempt < 5; attempt++) {
            logIn("2026-10-12T10:0" + attempt + ":00Z").execute(command("uno@colegio.edu.hn", WRONG));
        }
        for (int other = 2; other <= 4; other++) {
            logIn("2026-10-12T10:05:00Z").execute(command("otro" + other + "@colegio.edu.hn", WRONG));
        }

        assertThat(auditRows(SIGNAL)).isEmpty();
        assertThat(logged.list).isEmpty();
    }

    @Test
    void theEdgeOfTenMinutesIsStrictAndASignalledAddressStillAuthenticates() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        for (int unknown = 1; unknown <= 4; unknown++) {
            logIn("2026-10-12T10:00:00Z").execute(command("intruso" + unknown + "@colegio.edu.hn", WRONG));
        }
        logIn("2026-10-12T10:10:00Z").execute(command("intruso5@colegio.edu.hn", WRONG));
        assertThat(auditRows(SIGNAL)).as("the failures of 10:00:00 are not live at 10:10:00").isEmpty();

        for (int unknown = 6; unknown <= 9; unknown++) {
            logIn("2026-10-12T10:11:00Z").execute(command("intruso" + unknown + "@colegio.edu.hn", WRONG));
        }
        assertThat(auditRows(SIGNAL)).hasSize(1);

        assertThat(logIn("2026-10-12T10:12:00Z").execute(command(ANA, CURRENT_PASSWORD)).outcome())
                .isInstanceOf(LoginOutcome.Started.class);
    }

    @Test
    void aSixthIdentifierAndTheRepetitionOfACountedOneDoNotSignalAgain() {
        for (int unknown = 1; unknown <= 5; unknown++) {
            logIn("2026-10-12T10:00:00Z").execute(command("intruso" + unknown + "@colegio.edu.hn", WRONG));
        }

        logIn("2026-10-12T10:01:00Z").execute(command("intruso6@colegio.edu.hn", WRONG));
        logIn("2026-10-12T10:02:00Z").execute(command("intruso3@colegio.edu.hn", WRONG));

        assertThat(auditRows(SIGNAL)).hasSize(1);
        assertThat(logged.list).hasSize(1);
    }

    @Test
    void aSignalEntryThatFailsRollsBackTheAttemptAndWritesNoWarning() {
        for (int unknown = 1; unknown <= 4; unknown++) {
            logIn("2026-10-12T10:00:00Z").execute(command("intruso" + unknown + "@colegio.edu.hn", WRONG));
        }
        long failedBefore = auditRows("identity.login.failed").size();
        long backoffBefore = count("identity_login_backoff");

        assertThatThrownBy(() -> logIn("2026-10-12T10:01:00Z", failingOn(SIGNAL), spread,
                () -> institutionId).execute(command("intruso5@colegio.edu.hn", WRONG)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(auditRows("identity.login.failed")).hasSize((int) failedBefore);
        assertThat(count("identity_login_backoff")).isEqualTo(backoffBefore);
        assertThat(logged.list).as("nothing is announced for a transaction that did not commit")
                .isEmpty();
    }

    @Test
    void onlyARejectedAttemptWithAnAddressIsRecordedAsAFailure() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        StaffAccountId pending = seedStaffAccount("pendiente@colegio.edu.hn", CURRENT_PASSWORD, true);
        enroll(pending);
        seedStaffAccount("nuevo@colegio.edu.hn", CURRENT_PASSWORD, true);
        RecordingSpread recording = new RecordingSpread();

        logIn(EIGHT, new JooqAuditLogWriter(dsl), recording, () -> institutionId)
                .execute(command(ANA, CURRENT_PASSWORD));
        logIn(EIGHT, new JooqAuditLogWriter(dsl), recording, () -> institutionId)
                .execute(command("pendiente@colegio.edu.hn", CURRENT_PASSWORD));
        logIn(EIGHT, new JooqAuditLogWriter(dsl), recording, () -> institutionId)
                .execute(command("nuevo@colegio.edu.hn", CURRENT_PASSWORD));
        assertThat(recording.calls).as("Started and both challenges are not failures").isEmpty();

        logIn(EIGHT, new JooqAuditLogWriter(dsl), recording, () -> institutionId)
                .execute(command(ANA, WRONG));
        logIn(EIGHT, new JooqAuditLogWriter(dsl), recording, () -> institutionId)
                .execute(command("nadie@colegio.edu.hn", WRONG));
        assertThat(recording.calls).containsExactly(
                CLIENT_IP + "|" + fingerprintOf(ANA) + "|" + EIGHT,
                CLIENT_IP + "|" + fingerprintOf("nadie@colegio.edu.hn") + "|" + EIGHT);

        logIn(EIGHT, new JooqAuditLogWriter(dsl), recording, () -> institutionId)
                .execute(new LoginCommand(ANA, WRONG, null, null, ""));
        assertThat(recording.calls).as("without an address there is nobody to count the failure for")
                .hasSize(2);
    }

    // --- The origin of the request ---

    @Test
    void theFamilyKeepsTheAddressAndTheFingerprintOfTheAgentOfTheRequest() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        LoginDecision decision = logIn(EIGHT).execute(command(ANA, CURRENT_PASSWORD));

        UUID sessionId = verifierAt(EIGHT).verifyAccess(
                ((LoginOutcome.Started) decision.outcome()).session().accessToken()).sessionId();
        Record family = transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select host(ip_first_seen) as ip, user_agent_fingerprint
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, institutionId.value(), sessionId));
        assertThat(family.get("ip", String.class)).isEqualTo(CLIENT_IP);
        assertThat(family.get("user_agent_fingerprint", String.class))
                .isEqualTo(UserAgentFingerprint.of(AGENT).value());
        assertThat(everythingStoredAboutSessions()).doesNotContain(AGENT);
    }

    @Test
    void aLoginWithoutOriginIsAcceptedWithANullAddress() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        LoginDecision decision = logIn(EIGHT).execute(new LoginCommand(ANA, CURRENT_PASSWORD, null,
                null, ""));

        UUID sessionId = verifierAt(EIGHT).verifyAccess(
                ((LoginOutcome.Started) decision.outcome()).session().accessToken()).sessionId();
        Record family = transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select ip_first_seen, user_agent_fingerprint from identity_refresh_token_family
                 where institution_id = ? and id = ?
                """, institutionId.value(), sessionId));
        assertThat(family.get("ip_first_seen")).isNull();
        assertThat(family.get("user_agent_fingerprint", String.class))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void theAuthenticationEntryCarriesTheAddressAndTheAgentOfTheCommand() {
        seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        logIn(EIGHT).execute(command(ANA, WRONG));

        assertThat(auditRows("identity.login.failed")).singleElement().satisfies(row -> {
            assertThat(row.sourceIp()).isEqualTo(CLIENT_IP);
            assertThat(row.userAgent()).isEqualTo(AGENT);
        });
    }

    // ---------------------------------------------------------------------------------------------

    private LogIn logIn(String instant) {
        return logIn(instant, new JooqAuditLogWriter(dsl), spread, () -> institutionId);
    }

    private LogIn logIn(String instant, AuditLogWriter writer, LoginFailureSpread failureSpread,
            LoginInstitutionProvider provider) {
        return logIn(instant, writer, new JooqRefreshTokenRepository(dsl), failureSpread, provider);
    }

    private LogIn logIn(String instant, AuditLogWriter writer, RefreshTokenRepository tokens,
            LoginFailureSpread failureSpread, LoginInstitutionProvider provider) {
        Clock clock = fixedClock(instant);
        AuthenticateWithPassword authenticate = new AuthenticateWithPassword(transactionRunner(),
                () -> institutionId, new JooqStaffAccountRepository(dsl),
                new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), HASHER,
                FINGERPRINTER, writer, clock);
        return new LogIn(transactionRunner(), provider, authenticate,
                issueSession(clock, writer, tokens), accessTokenIssuer(clock), FINGERPRINTER,
                failureSpread, writer, new SecurityEventLog(), clock);
    }

    private static LoginCommand command(String identifier, String password) {
        return new LoginCommand(identifier, password, ClientAddress.parseLiteral(CLIENT_IP), AGENT,
                UUID.randomUUID().toString());
    }

    /** A writer that stores the entry and then fails when it is the given action, as a late failure would. */
    private AuditLogWriter failingOn(String action) {
        JooqAuditLogWriter real = new JooqAuditLogWriter(dsl);
        return entry -> {
            real.append(entry);
            if (entry.action().equals(action)) {
                throw new IllegalStateException("the entry could not be written");
            }
        };
    }

    private static String fingerprintOf(String identifier) {
        IdentifierFingerprint fingerprint = FINGERPRINTER.fingerprintOf(LoginIdentifier.of(identifier));
        return fingerprint.value();
    }

    private Record familyOf(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select account_id, second_factor_verified
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, institutionId.value(), familyId));
    }

    private long count(String table) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle(
                "select count(*) from " + table + " where institution_id = ?",
                institutionId.value()).get(0, Long.class));
    }

    /** The rows of the family and of its tokens as text; the audit trail keeps the agent text itself. */
    private String everythingStoredAboutSessions() {
        return transactionRunner().execute(context(), () -> dsl.fetch("""
                select row_to_json(f)::text from identity_refresh_token_family f
                union all
                select row_to_json(t)::text from identity_refresh_token t
                """).formatCSV());
    }

    /** Records what the use case reports, and signals nothing. */
    private static final class RecordingSpread implements LoginFailureSpread {

        private final List<String> calls = new ArrayList<>();

        @Override
        public boolean recordFailure(ClientAddress client, IdentifierFingerprint identifier,
                Instant now) {
            calls.add(client.canonical() + "|" + identifier.value() + "|" + now);
            return false;
        }
    }
}

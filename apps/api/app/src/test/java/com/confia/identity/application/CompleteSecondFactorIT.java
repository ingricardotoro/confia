package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.application.SecondFactorOutcome.Rejected;
import com.confia.identity.application.SecondFactorOutcome.Started;
import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecondFactorPurpose;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link CompleteSecondFactor} end to end, against a real PostgreSQL (session-endpoints-cookie-and-csrf
 * design.md, decision 13; specs/identity/spec.md, requirements "La verificación del segundo factor se
 * ofrece en ... con un token restringido" and "La primera verificación correcta activa la credencial y
 * revoca las demás sesiones con el motivo mfa_change"). A code is checked against the real secret, a
 * session is a real family, and a failure after the revocation is a real rollback.
 */
class CompleteSecondFactorIT extends RevocationIntegrationTest {

    private static final String SOFIA = "sofia.mejia@colegio.edu.hn";
    private static final String JORGE = "jorge.aguilar@colegio.edu.hn";
    private static final String ACTION_ACTIVATED = "identity.mfa.activated";
    private static final String ACTION_STARTED = "identity.session.started";
    private static final String T0 = "2026-10-22T10:00:00Z";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    // --- The verification of a credential that is already verified (purpose mfa-verify) ---

    /** [I170]: a valid TOTP code with an {@code mfa-verify} token opens the full session. */
    @Test
    void aValidTotpCodeOpensTheSessionWithPasswordAndOneTimePassword() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        Enrolled enrolled = enroll(sofia);

        CompleteSecondFactorDecision decision = useCase(T0).execute(totp(sofia,
                SecondFactorPurpose.VERIFY, validCodeAt(enrolled.secret(), T0).value()));

        assertThat(decision.outcome()).isInstanceOf(Started.class);
        SessionGrant session = ((Started) decision.outcome()).session();
        assertThat(verifierAt(T0).verifyAccess(session.accessToken()).methods())
                .containsExactlyInAnyOrder(AuthenticationMethod.PASSWORD,
                        AuthenticationMethod.ONE_TIME_PASSWORD);
        assertThat(familiesIn(institutionId, sofia)).singleElement().satisfies(family ->
                assertThat(family.get("revoked_at")).isNull());
        assertThat(secondFactorVerifiedFlags(sofia)).containsExactly(true);
        assertThat(JSON.readTree(auditRows(ACTION_STARTED).get(0).afterValue())
                .get("secondFactor").asBoolean()).isTrue();
        assertThat(auditRows(ACTION_REVOKED)).as("a verification of a verified credential revokes none")
                .isEmpty();
        assertThat(auditRows(ACTION_ACTIVATED)).isEmpty();
        assertThat(decision.requiredDelay()).isEqualTo(Duration.ZERO);
    }

    /** [I171]: a recovery code opens the session and is used up; the second use is refused. */
    @Test
    void aRecoveryCodeOpensTheSessionIsConsumedAndCannotBeUsedTwice() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        Enrolled enrolled = enroll(sofia);
        PlainRecoveryCode code = enrolled.recoveryCodes().get(0);

        CompleteSecondFactorDecision first = useCase(T0).execute(
                recovery(sofia, SecondFactorPurpose.VERIFY, code.value()));
        CompleteSecondFactorDecision second = useCase("2026-10-22T10:01:00Z").execute(
                recovery(sofia, SecondFactorPurpose.VERIFY, code.value()));

        assertThat(first.outcome()).isInstanceOf(Started.class);
        assertThat(verifierAt(T0).verifyAccess(((Started) first.outcome()).session().accessToken())
                .methods()).contains(AuthenticationMethod.ONE_TIME_PASSWORD);
        assertThat(unusedRecoveryCodes(sofia)).isEqualTo(9L);
        assertThat(second.outcome()).isInstanceOf(Rejected.class);
        assertThat(familiesIn(institutionId, sofia)).hasSize(1);
    }

    /** [I172]: a wrong code advances the backoff, and from the third failure the answer owes a delay. */
    @Test
    void aWrongCodeAdvancesTheBackoffAndFromTheThirdFailureOwesTheDelay() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        Enrolled enrolled = enroll(sofia);
        String wrong = wrongCodeAt(enrolled.secret(), T0).value();

        CompleteSecondFactorDecision first = useCase(T0).execute(
                totp(sofia, SecondFactorPurpose.VERIFY, wrong));
        CompleteSecondFactorDecision second = useCase(T0).execute(
                totp(sofia, SecondFactorPurpose.VERIFY, wrong));
        CompleteSecondFactorDecision third = useCase(T0).execute(
                totp(sofia, SecondFactorPurpose.VERIFY, wrong));

        assertThat(first.outcome()).isInstanceOf(Rejected.class);
        assertThat(first.requiredDelay()).isEqualTo(Duration.ZERO);
        assertThat(second.requiredDelay()).isEqualTo(Duration.ZERO);
        assertThat(third.outcome()).isInstanceOf(Rejected.class);
        assertThat(third.requiredDelay()).as("the third failure owes the delay of the backoff")
                .isGreaterThan(Duration.ZERO);
        assertThat(consecutiveFailures(sofia, T0)).isEqualTo(3);
        assertThat(familiesIn(institutionId, sofia)).isEmpty();
    }

    /** [I175]: the code that was accepted for a counter is refused in the same window. */
    @Test
    void aCodeReusedInTheSameWindowIsRefused() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        Enrolled enrolled = enroll(sofia);
        String code = validCodeAt(enrolled.secret(), T0).value();

        CompleteSecondFactorDecision first = useCase(T0).execute(
                totp(sofia, SecondFactorPurpose.VERIFY, code));
        CompleteSecondFactorDecision again = useCase("2026-10-22T10:00:05Z").execute(
                totp(sofia, SecondFactorPurpose.VERIFY, code));

        assertThat(first.outcome()).isInstanceOf(Started.class);
        assertThat(again.outcome()).isInstanceOf(Rejected.class);
        assertThat(familiesIn(institutionId, sofia)).hasSize(1);
    }

    /** [I223]: a wrong recovery code is a failure of the same backoff as the TOTP codes. */
    @Test
    void aWrongRecoveryCodeAddsToTheTotpBackoffAndOwesTheDelay() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        Enrolled enrolled = enroll(sofia);
        String wrongTotp = wrongCodeAt(enrolled.secret(), T0).value();
        useCase(T0).execute(totp(sofia, SecondFactorPurpose.VERIFY, wrongTotp));
        useCase(T0).execute(totp(sofia, SecondFactorPurpose.VERIFY, wrongTotp));
        assertThat(consecutiveFailures(sofia, T0)).isEqualTo(2);

        CompleteSecondFactorDecision decision = useCase(T0).execute(
                recovery(sofia, SecondFactorPurpose.VERIFY, "ZZZZZZZZZZ"));

        assertThat(decision.outcome()).isInstanceOf(Rejected.class);
        assertThat(consecutiveFailures(sofia, T0)).as("two TOTP failures and this one").isEqualTo(3);
        assertThat(decision.requiredDelay()).isGreaterThan(Duration.ZERO);
        assertThat(unusedRecoveryCodes(sofia)).isEqualTo(10L);
        assertThat(auditRows("identity.mfa.totp_verification.backoff_applied")).hasSize(1);
    }

    /** A right recovery code clears the backoff, as a right TOTP code does. */
    @Test
    void aRightRecoveryCodeClearsTheBackoff() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        Enrolled enrolled = enroll(sofia);
        useCase(T0).execute(recovery(sofia, SecondFactorPurpose.VERIFY, "ZZZZZZZZZZ"));
        assertThat(consecutiveFailures(sofia, T0)).isEqualTo(1);

        CompleteSecondFactorDecision decision = useCase(T0).execute(recovery(sofia,
                SecondFactorPurpose.VERIFY, enrolled.recoveryCodes().get(3).value()));

        assertThat(decision.outcome()).isInstanceOf(Started.class);
        assertThat(consecutiveFailures(sofia, T0)).isZero();
    }

    // --- The first verification (purpose mfa-enroll, credential never verified) ---

    /** [I169] and [I221]: a recovery code does not verify the credential, and is not consumed. */
    @Test
    void withAnEnrollTokenAndAnUnverifiedCredentialARecoveryCodeIsRefusedUnused() {
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        Enrolled enrolled = enrollWithoutVerifying(jorge);

        CompleteSecondFactorDecision decision = useCase(T0).execute(recovery(jorge,
                SecondFactorPurpose.ENROLL, enrolled.recoveryCodes().get(0).value()));

        assertThat(decision.outcome()).isInstanceOf(Rejected.class);
        assertThat(isVerified(jorge)).isFalse();
        assertThat(unusedRecoveryCodes(jorge)).as("the code stays unused").isEqualTo(10L);
        assertThat(familiesIn(institutionId, jorge)).as("no family was issued").isEmpty();
        assertThat(auditRows(ACTION_REVOKED)).isEmpty();
        assertThat(auditRows(ACTION_ACTIVATED)).isEmpty();
    }

    /** [I222]: with the credential already verified an {@code mfa-enroll} token proves nothing. */
    @Test
    void withAnEnrollTokenAndAVerifiedCredentialEveryProofIsRefusedAndNothingChanges() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        Enrolled enrolled = enroll(sofia);
        issue("2026-10-22T09:00:00Z", sofia);

        CompleteSecondFactorDecision withTotp = useCase(T0).execute(totp(sofia,
                SecondFactorPurpose.ENROLL, validCodeAt(enrolled.secret(), T0).value()));
        CompleteSecondFactorDecision withRecovery = useCase(T0).execute(recovery(sofia,
                SecondFactorPurpose.ENROLL, enrolled.recoveryCodes().get(0).value()));

        assertThat(withTotp.outcome()).isInstanceOf(Rejected.class);
        assertThat(withRecovery.outcome()).isInstanceOf(Rejected.class);
        assertThat(familiesIn(institutionId, sofia)).as("no family was issued and none revoked")
                .singleElement().satisfies(family -> assertThat(family.get("revoked_at")).isNull());
        assertThat(unusedRecoveryCodes(sofia)).isEqualTo(10L);
        assertThat(auditRows(ACTION_REVOKED)).isEmpty();
    }

    /**
     * [I168], [I176]: the first accepted TOTP code activates the credential, revokes the other
     * families of the account with {@code mfa_change}, leaves the new one alive and touches nobody
     * else; the next login then owes a second factor instead of an enrollment.
     */
    @Test
    void theFirstVerificationActivatesTheCredentialAndRevokesTheOtherSessionsOfTheAccountOnly() {
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        Enrolled enrolled = enrollWithoutVerifying(jorge);
        IssuedSession older = issue("2026-10-22T08:00:00Z", jorge);
        IssuedSession newer = issue("2026-10-22T08:30:00Z", jorge);
        IssuedSession strangers = issue("2026-10-22T08:45:00Z", ana);

        CompleteSecondFactorDecision decision = useCase(T0).execute(totp(jorge,
                SecondFactorPurpose.ENROLL, validCodeAt(enrolled.secret(), T0).value()));

        assertThat(decision.outcome()).isInstanceOf(Started.class);
        List<Record> families = familiesIn(institutionId, jorge);
        assertThat(families).hasSize(3);
        assertThat(families.stream().filter(f -> f.get("revoked_at") == null).toList())
                .as("only the family the verification issued is alive").hasSize(1);
        for (UUID previous : List.of(older.sessionId(), newer.sessionId())) {
            Record row = familyIn(institutionId, previous);
            assertThat(row.get("revoked_reason", String.class)).isEqualTo("mfa_change");
            assertThat(instantOf(row, "revoked_at")).isEqualTo(Instant.parse(T0));
        }
        assertThat(familyIn(institutionId, strangers.sessionId()).get("revoked_at"))
                .as("the family of another account did not change").isNull();
        assertThat(isVerified(jorge)).isTrue();
        assertThat(auditRows(ACTION_REVOKED)).singleElement().satisfies(row -> {
            JsonNode after = JSON.readTree(row.afterValue());
            assertThat(after.get("reason").asString()).isEqualTo("mfa_change");
            assertThat(after.get("families").asInt()).isEqualTo(2);
            assertThat(after.size()).isEqualTo(2);
            assertThat(row.entityId()).isEqualTo(jorge.value().toString());
        });
        assertThat(auditRows(ACTION_ACTIVATED)).singleElement()
                .satisfies(row -> assertThat(row.afterValue()).isNull());
        assertThat(loginAt(JORGE, CURRENT_PASSWORD, "2026-10-22T10:05:00Z"))
                .as("[I168] the next login owes the second factor, not the enrollment")
                .isInstanceOf(AuthenticationResult.SecondFactorRequired.class);
    }

    /** [I177]: a later verification of the same account revokes nothing. */
    @Test
    void aLaterVerificationRevokesNothing() {
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        Enrolled enrolled = enrollWithoutVerifying(jorge);
        useCase(T0).execute(totp(jorge, SecondFactorPurpose.ENROLL,
                validCodeAt(enrolled.secret(), T0).value()));
        IssuedSession other = issue("2026-10-22T10:20:00Z", jorge);
        String later = "2026-10-22T10:40:00Z";

        CompleteSecondFactorDecision decision = useCase(later).execute(totp(jorge,
                SecondFactorPurpose.VERIFY, validCodeAt(enrolled.secret(), later).value()));

        assertThat(decision.outcome()).isInstanceOf(Started.class);
        assertThat(familiesIn(institutionId, jorge)).hasSize(3)
                .allSatisfy(family -> assertThat(family.get("revoked_at")).isNull());
        assertThat(familyIn(institutionId, other.sessionId()).get("revoked_at")).isNull();
        assertThat(auditRows(ACTION_REVOKED)).as("only the entry of the activation").hasSize(1);
        assertThat(auditRows(ACTION_ACTIVATED)).hasSize(1);
    }

    /** [I178]: a failure after the revocation and before the commit leaves nothing behind. */
    @Test
    void anActivationThatFailsAfterRevokingRollsEverythingBack() {
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        Enrolled enrolled = enrollWithoutVerifying(jorge);
        IssuedSession previous = issue("2026-10-22T08:00:00Z", jorge);
        AuditLogWriter failingOnActivation = entry -> {
            if (ACTION_ACTIVATED.equals(entry.action())) {
                throw new IllegalStateException("the activation entry could not be written");
            }
            new JooqAuditLogWriter(dsl).append(entry);
        };

        assertThatThrownBy(() -> useCase(T0, failingOnActivation).execute(totp(jorge,
                SecondFactorPurpose.ENROLL, validCodeAt(enrolled.secret(), T0).value())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("activation entry");

        assertThat(isVerified(jorge)).as("the credential is still unverified").isFalse();
        assertThat(familiesIn(institutionId, jorge)).singleElement().satisfies(family -> {
            assertThat(family.get("revoked_at")).as("the previous family is alive").isNull();
            assertThat(family.get("id", UUID.class)).isEqualTo(previous.sessionId());
        });
        assertThat(auditRows(ACTION_REVOKED)).isEmpty();
        assertThat(auditRows(ACTION_ACTIVATED)).isEmpty();
        assertThat(auditRows("identity.mfa.totp_verification.succeeded")).isEmpty();
    }

    /** [I179]: no other sessions still writes the entry, with zero, and another institution is untouched. */
    @Test
    void anActivationWithoutOtherSessionsWritesTheEntryWithZeroAndNeverCrossesInstitutions() {
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        StaffAccountId jorgeElsewhere = seedStaffAccountIn(other, jorge, JORGE);
        IssuedSession elsewhere = issueIn(other, "2026-10-22T08:00:00Z", jorgeElsewhere);
        Enrolled enrolled = enrollWithoutVerifying(jorge);

        CompleteSecondFactorDecision decision = useCase(T0).execute(totp(jorge,
                SecondFactorPurpose.ENROLL, validCodeAt(enrolled.secret(), T0).value()));

        assertThat(decision.outcome()).isInstanceOf(Started.class);
        assertThat(auditRows(ACTION_REVOKED)).singleElement().satisfies(row ->
                assertThat(JSON.readTree(row.afterValue()).get("families").asInt()).isZero());
        assertThat(familyIn(other, elsewhere.sessionId()).get("revoked_at"))
                .as("the same account identifier in another institution is not touched").isNull();
        assertThat(familiesIn(other, jorgeElsewhere)).hasSize(1);
    }

    // --- What the purpose and the state of the credential do not allow ---

    @Test
    void anAccountWithoutACredentialIsRefusedWhateverThePurpose() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);

        for (SecondFactorPurpose purpose : SecondFactorPurpose.values()) {
            assertThat(useCase(T0).execute(totp(sofia, purpose, "123456")).outcome())
                    .isInstanceOf(Rejected.class);
        }
        assertThat(familiesIn(institutionId, sofia)).isEmpty();
    }

    @Test
    void aVerifyTokenWithAnUnverifiedCredentialIsRefusedWithoutEvaluatingTheProof() {
        StaffAccountId jorge = seedStaffAccount(JORGE, CURRENT_PASSWORD, true);
        Enrolled enrolled = enrollWithoutVerifying(jorge);

        CompleteSecondFactorDecision decision = useCase(T0).execute(totp(jorge,
                SecondFactorPurpose.VERIFY, validCodeAt(enrolled.secret(), T0).value()));

        assertThat(decision.outcome()).isInstanceOf(Rejected.class);
        assertThat(isVerified(jorge)).as("the valid code was not even checked").isFalse();
        assertThat(consecutiveFailures(jorge, T0)).isZero();
    }

    @Test
    void aProofThatCannotBeACodeIsRefusedAsAnIncorrectOneWithoutAnError() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT_PASSWORD, true);
        enroll(sofia);

        assertThat(useCase(T0).execute(totp(sofia, SecondFactorPurpose.VERIFY, "12ab")).outcome())
                .isInstanceOf(Rejected.class);
        assertThat(useCase(T0).execute(recovery(sofia, SecondFactorPurpose.VERIFY, "corto"))
                .outcome()).isInstanceOf(Rejected.class);
        assertThat(familiesIn(institutionId, sofia)).isEmpty();
    }

    @Test
    void anAccountThatDoesNotExistIsADefectNotARejection() {
        StaffAccountId ghost = new StaffAccountId(UUID.randomUUID());

        assertThatThrownBy(() -> useCase(T0).execute(totp(ghost, SecondFactorPurpose.VERIFY, "123456")))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Helpers ---

    private CompleteSecondFactor useCase(String instant) {
        return useCase(instant, new JooqAuditLogWriter(dsl));
    }

    private CompleteSecondFactor useCase(String instant, AuditLogWriter writer) {
        Clock clock = fixedClock(instant);
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

    private CompleteSecondFactorCommand totp(StaffAccountId account, SecondFactorPurpose purpose,
            String code) {
        return new CompleteSecondFactorCommand(account.value(), institutionId.value(), purpose, code,
                null, null, AGENT_ONE, UUID.randomUUID().toString());
    }

    private CompleteSecondFactorCommand recovery(StaffAccountId account, SecondFactorPurpose purpose,
            String code) {
        return new CompleteSecondFactorCommand(account.value(), institutionId.value(), purpose, null,
                code, null, AGENT_ONE, UUID.randomUUID().toString());
    }

    private boolean isVerified(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> new JooqTotpCredentialRepository(dsl)
                .findByAccountId(institutionId, account)).orElseThrow().isVerified();
    }

    private long unusedRecoveryCodes(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> new JooqRecoveryCodeRepository(dsl)
                .countUnusedByAccountId(institutionId, account));
    }

    private int consecutiveFailures(StaffAccountId account, String instant) {
        return transactionRunner().execute(context(), () -> new JooqTotpVerificationBackoffStore(dsl)
                .claim(institutionId, account, Instant.parse(instant))).consecutiveFailures();
    }

    private List<Boolean> secondFactorVerifiedFlags(StaffAccountId account) {
        return transactionRunner().execute(context(), () -> dsl.fetch("""
                select second_factor_verified from identity_refresh_token_family
                 where institution_id = ? and account_id = ?
                """, institutionId.value(), account.value()).map(row ->
                row.get("second_factor_verified", Boolean.class)));
    }
}

package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.RequestPasswordResetTest.RecordingScheduler;
import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.application.ResetOutcome.PasswordRejected;
import com.confia.identity.application.ResetOutcome.TokenRejected;
import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.AuthenticationResult.SecondFactorEnrollmentRequired;
import com.confia.identity.domain.PasswordResetRejectionReason;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ResetPasswordWithToken} for accounts without active MFA, through the real transaction and
 * the real adapters (password-recovery-token design.md decisions 7, 8 and 9; tasks.md task 4.1).
 * Every live token comes from the real issuance, and every password is checked by the real login.
 * The second-factor branch is task 4.2's.
 */
class ResetPasswordWithTokenIT extends PasswordResetIntegrationTest {

    private static final String ANA = "ana.martinez@colegio.edu.hn";
    private static final String CURRENT = "Cafetal de Copán 2026";
    private static final String NEW = "Lempira y maíz 2026";
    private static final SecondFactorProof NONE = new SecondFactorProof.None();
    private static final String ACTION_REJECTED = "identity.password_reset.rejected";
    private static final String ACTION_COMPLETED = "identity.password_reset.completed";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Scenario I35: accepted within the window, single use, and the passwords swap. */
    @Test
    void aTokenWorksOnceWithinItsWindowAndTheNewPasswordReplacesTheOld() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-05-14T10:00:00Z");

        ResetPasswordDecision first = resetAt(token.value(), NEW, NONE, "2026-05-14T10:20:00Z");
        ResetPasswordDecision second = resetAt(token.value(), "Otra contraseña 2026", NONE,
                "2026-05-14T10:21:00Z");

        assertThat(first.outcome()).isInstanceOf(Completed.class);
        assertThat(second.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(loginAt(ANA, CURRENT, "2026-05-14T10:22:00Z")).isInstanceOf(Rejected.class);
        assertThat(loginAt(ANA, NEW, "2026-05-14T10:23:00Z")).isInstanceOf(Authenticated.class);
    }

    /** Scenario I36: superseded, not expired, and the newer token completes. */
    @Test
    void aSupersededTokenIsRejectedAsSupersededAndTheNewerOneCompletes() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken older = issueTokenAt(ana, "2026-05-14T10:00:00Z");
        PlainPasswordResetToken newer = issueTokenAt(ana, "2026-05-14T10:25:00Z");

        ResetPasswordDecision withOlder = resetAt(older.value(), NEW, NONE,
                "2026-05-14T10:26:00Z");
        ResetPasswordDecision withNewer = resetAt(newer.value(), NEW, NONE,
                "2026-05-14T10:30:00Z");

        assertThat(withOlder.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(rejectionReasons()).containsExactly("token-superseded");
        assertThat(withNewer.outcome()).isInstanceOf(Completed.class);
    }

    /** Scenario I37: 10:29:59 is inside, 10:30:00 is outside. */
    @Test
    void theThirtyMinuteBorderIsStrict() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        StaffAccountId luis = seedStaffAccount("luis.fernandez@colegio.edu.hn", CURRENT, false);
        PlainPasswordResetToken anaToken = issueTokenAt(ana, "2026-05-14T10:00:00Z");
        PlainPasswordResetToken luisToken = issueTokenAt(luis, "2026-05-14T10:00:00Z");

        assertThat(resetAt(anaToken.value(), NEW, NONE, "2026-05-14T10:29:59Z").outcome())
                .isInstanceOf(Completed.class);
        assertThat(resetAt(luisToken.value(), NEW, NONE, "2026-05-14T10:30:00Z").outcome())
                .isInstanceOf(TokenRejected.class);
        assertThat(rejectionReasons()).containsExactly("token-expired");
    }

    /** Scenario I38: a scheduled but not yet issued request supersedes nothing. */
    @Test
    void aRequestWhoseIssuanceHasNotRunDoesNotSupersedeTheLiveToken() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-05-14T10:00:00Z");
        RecordingScheduler scheduler = new RecordingScheduler();
        new RequestPasswordReset(transactionRunner(), () -> institutionId,
                new JooqStaffAccountRepository(dsl), FINGERPRINTER, scheduler,
                new JooqAuditLogWriter(dsl))
                .execute(context(), new RequestPasswordResetCommand(ANA));

        ResetPasswordDecision decision = resetAt(token.value(), NEW, NONE, "2026-05-14T10:12:00Z");

        assertThat(scheduler.calls).hasSize(1);
        assertThat(decision.outcome()).isInstanceOf(Completed.class);
    }

    /** Scenario I11: mfa_required without a secret resets with the link; enrollment still due. */
    @Test
    void mfaRequiredWithoutASecretResetsWithTheLinkAndEnrollmentIsStillRequired() {
        String jorge = "jorge.aguilar@colegio.edu.hn";
        StaffAccountId account = seedStaffAccount(jorge, CURRENT, true);
        PlainPasswordResetToken token = issueTokenAt(account, "2026-10-13T08:00:00Z");

        ResetPasswordDecision decision = resetAt(token.value(), "Semana Morazánica 2026", NONE,
                "2026-10-13T08:10:00Z");

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(loginAt(jorge, "Semana Morazánica 2026", "2026-10-13T08:12:00Z"))
                .isInstanceOf(SecondFactorEnrollmentRequired.class);
    }

    /** Scenario I12: mfa_required false with a secret needs no code and verifies none. */
    @Test
    void mfaNotRequiredWithAnEnrolledSecretResetsWithoutAnyTotpVerification() {
        String maria = "maria.lopez@colegio.edu.hn";
        StaffAccountId account = seedStaffAccount(maria, CURRENT, false);
        enrollTotp(account);
        PlainPasswordResetToken token = issueTokenAt(account, "2026-10-13T08:00:00Z");

        ResetPasswordDecision decision = resetAt(token.value(), "Feria Juniana de San Pedro", NONE,
                "2026-10-13T08:03:00Z");

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(auditRows()).noneMatch(row -> row.action()
                .startsWith("identity.mfa.totp_verification"));
    }

    /** Scenarios I15 and I16, integration part: 11 and 129 rejected with the token alive. */
    @Test
    void elevenAndOneHundredTwentyNineAreRejectedTwelveAndOneHundredTwentyEightAccepted() {
        PlainPasswordResetToken eleven = liveTokenFor("once@colegio.edu.hn");
        PlainPasswordResetToken twelve = liveTokenFor("doce@colegio.edu.hn");
        PlainPasswordResetToken max = liveTokenFor("maximo@colegio.edu.hn");
        PlainPasswordResetToken over = liveTokenFor("excede@colegio.edu.hn");
        PlainPasswordResetToken emojiEleven = liveTokenFor("emoji.once@colegio.edu.hn");
        PlainPasswordResetToken emojiTwelve = liveTokenFor("emoji.doce@colegio.edu.hn");

        assertThat(resetAt(eleven.value(), "a".repeat(11), NONE, "2026-10-15T15:05:00Z").outcome())
                .isEqualTo(new PasswordRejected(PasswordResetRejectionReason.PASSWORD_TOO_SHORT));
        assertThat(resetAt(over.value(), "a".repeat(129), NONE, "2026-10-15T15:05:00Z").outcome())
                .isEqualTo(new PasswordRejected(PasswordResetRejectionReason.PASSWORD_TOO_LONG));
        assertThat(resetAt(twelve.value(), "a".repeat(12), NONE, "2026-10-15T15:05:00Z").outcome())
                .isInstanceOf(Completed.class);
        assertThat(resetAt(max.value(), "a".repeat(128), NONE, "2026-10-15T15:05:00Z").outcome())
                .isInstanceOf(Completed.class);
        assertThat(resetAt(emojiEleven.value(), "casa azul🌋🌊", NONE,
                "2026-10-15T15:05:00Z").outcome()).isInstanceOf(PasswordRejected.class);
        assertThat(resetAt(emojiTwelve.value(), "casa azul 🌋🌊", NONE,
                "2026-10-15T15:05:00Z").outcome()).isInstanceOf(Completed.class);
        assertThat(isOpen(eleven)).isTrue();
        assertThat(isOpen(over)).isTrue();
        assertThat(isOpen(emojiEleven)).isTrue();
        assertThat(rejectionReasons()).containsExactlyInAnyOrder("password-too-short",
                "password-too-long", "password-too-short");
    }

    /** Scenario I43: twelve digits, no letters, is accepted. */
    @Test
    void twelveDigitsAreAccepted() {
        PlainPasswordResetToken token = liveTokenFor(ANA);

        assertThat(resetAt(token.value(), "123456789012", NONE, "2026-10-15T15:05:00Z").outcome())
                .isInstanceOf(Completed.class);
        assertThat(loginAt(ANA, "123456789012", "2026-10-15T15:06:00Z"))
                .isInstanceOf(Authenticated.class);
    }

    /** Scenarios I17 and I18: the configured institution decides, and a foreign token is unknown. */
    @Test
    void aTokenOfAnotherInstitutionIsUnknownAndStaysAliveInItsOwn() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-10-15T15:00:00Z");
        InstitutionId otherProcess = new InstitutionId(UUID.randomUUID());

        ResetPasswordDecision elsewhere = resetUseCase("2026-10-15T15:05:00Z",
                new JooqAuditLogWriter(dsl), new JooqPasswordResetTokenRepository(dsl),
                otherProcess).execute(contextOf(otherProcess),
                        new ResetPasswordCommand(token.value(), NEW, NONE));

        assertThat(elsewhere.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(isOpen(token)).isTrue();
        assertThat(resetAt(token.value(), NEW, NONE, "2026-10-15T15:06:00Z").outcome())
                .isInstanceOf(Completed.class);
    }

    @Test
    void aContextThatDisagreesWithTheConfiguredInstitutionIsAWiringDefect() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-10-15T15:00:00Z");

        assertThatThrownBy(() -> resetUseCase("2026-10-15T15:05:00Z", new JooqAuditLogWriter(dsl),
                new JooqPasswordResetTokenRepository(dsl), institutionId)
                .execute(contextOf(new InstitutionId(UUID.randomUUID())),
                        new ResetPasswordCommand(token.value(), NEW, NONE)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(isOpen(token)).isTrue();
    }

    /** Scenario I19: four reasons, one result, four audited reasons. */
    @Test
    void theFourTokenRejectionReasonsGiveOneResultAndFourAuditedReasons() {
        PlainPasswordResetToken neverIssued = PlainPasswordResetToken.generate(new SecureRandom());
        StaffAccountId expiredAccount = seedStaffAccount("vencido@colegio.edu.hn", CURRENT, false);
        PlainPasswordResetToken expired = issueTokenAt(expiredAccount, "2026-10-15T15:00:00Z");
        StaffAccountId supersededAccount = seedStaffAccount("superado@colegio.edu.hn", CURRENT,
                false);
        PlainPasswordResetToken superseded = issueTokenAt(supersededAccount,
                "2026-10-15T15:40:00Z");
        issueTokenAt(supersededAccount, "2026-10-15T15:50:00Z");
        StaffAccountId usedAccount = seedStaffAccount("usado@colegio.edu.hn", CURRENT, false);
        PlainPasswordResetToken used = issueTokenAt(usedAccount, "2026-10-15T15:50:00Z");
        assertThat(resetAt(used.value(), NEW, NONE, "2026-10-15T15:55:00Z").outcome())
                .isInstanceOf(Completed.class);

        List<ResetPasswordDecision> decisions = List.of(neverIssued, expired, superseded, used)
                .stream()
                .map(token -> resetAt(token.value(), "Otra contraseña 2026", NONE,
                        "2026-10-15T16:00:00Z"))
                .toList();

        assertThat(decisions).allSatisfy(decision -> assertThat(decision)
                .isEqualTo(new ResetPasswordDecision(new TokenRejected(),
                        java.time.Duration.ZERO)));
        assertThat(rejectionReasons()).containsExactlyInAnyOrder("token-not-found",
                "token-expired", "token-superseded", "token-consumed");
    }

    /** Scenarios I23 and I28: only the consumption, the new hash and their entries; no notice. */
    @Test
    void aCompletedResetOnlyConsumesChangesTheHashAndAuditsWithoutNotifyingAnyone() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT, false);
        PlainPasswordResetToken token = issueTokenAt(ana, "2026-10-15T15:00:00Z");
        List<String> actionsBefore = auditRows().stream().map(AuditRowSnapshot::action).toList();

        resetAt(token.value(), NEW, NONE, "2026-10-15T15:05:00Z");

        List<AuditRowSnapshot> all = auditRows();
        List<AuditRowSnapshot> produced = all.subList(actionsBefore.size(), all.size());
        assertThat(produced).extracting(AuditRowSnapshot::action)
                .containsExactly(ACTION_COMPLETED);
        assertThat(produced.get(0).entityId()).isEqualTo(ana.value().toString());
        assertThat(rowOf(token)).get().extracting(row -> row.consumedAt()).isNotNull();
        assertThat(auditRows()).extracting(row -> row.action().toLowerCase(Locale.ROOT))
                .noneMatch(action -> action.contains("notif") || action.contains("email")
                        || action.contains("mail") || action.contains("sent"));
    }

    /** Scenario I34: a token that was never issued never changes the password. */
    @Test
    void aNeverIssuedTokenNeverChangesThePassword() {
        seedStaffAccount(ANA, CURRENT, false);

        ResetPasswordDecision decision = resetAt(
                PlainPasswordResetToken.generate(new SecureRandom()).value(), NEW, NONE,
                "2026-10-15T15:05:00Z");

        assertThat(decision.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(loginAt(ANA, CURRENT, "2026-10-15T15:06:00Z")).isInstanceOf(Authenticated.class);
    }

    private PlainPasswordResetToken liveTokenFor(String email) {
        return issueTokenAt(seedStaffAccount(email, CURRENT, false), "2026-10-15T15:00:00Z");
    }

    /** The {@code reason} of every rejected entry, oldest first (the reader orders by id). */
    private List<String> rejectionReasons() {
        return auditRows(ACTION_REJECTED).stream()
                .map(row -> JSON.readTree(row.afterValue()).get("reason").asString())
                .toList();
    }
}

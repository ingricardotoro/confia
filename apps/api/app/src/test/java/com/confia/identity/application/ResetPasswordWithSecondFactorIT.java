package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.application.ResetOutcome.SecondFactorMissing;
import com.confia.identity.application.ResetOutcome.SecondFactorRejected;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.AuthenticationResult.SecondFactorRequired;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StaffAccountId;
import com.confia.shared.audit.AuditRowSnapshot;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ResetPasswordWithToken} for an account with active MFA: {@code mfa_required} and an
 * enrolled TOTP secret (password-recovery-token design.md decision 7, step 5; tasks.md task 4.2).
 * The reset composes the part 2 verifiers through their package-private {@code
 * runWithinTransaction}, never their {@code execute}, so they share its transaction. Kept apart from
 * {@code ResetPasswordWithTokenIT} so each can ship in its own reviewable pull request.
 */
class ResetPasswordWithSecondFactorIT extends PasswordResetIntegrationTest {

    private static final String SOFIA = "sofia.mejia@colegio.edu.hn";
    private static final String CURRENT = "Cafetal de Copán 2026";
    private static final String NEW = "Montaña de Celaque 2026";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Scenario I9: no code, no reset and the token stays alive; a valid TOTP code resets. */
    @Test
    void withoutASecondFactorThereIsNoResetAndWithAValidTotpCodeThereIs() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT, true);
        EnrollTotpSecondFactorResult enrollment = enroll(sofia);
        PlainPasswordResetToken token = issueTokenAt(sofia, "2026-10-12T09:00:00Z");

        ResetPasswordDecision withoutCode = resetAt(token.value(), NEW,
                new SecondFactorProof.None(), "2026-10-12T09:05:00Z");

        assertThat(withoutCode.outcome()).isInstanceOf(SecondFactorMissing.class);
        assertThat(isOpen(token)).isTrue();
        assertThat(loginAt(SOFIA, CURRENT, "2026-10-12T09:05:30Z"))
                .isInstanceOf(SecondFactorRequired.class);

        ResetPasswordDecision withCode = resetAt(token.value(), NEW, new SecondFactorProof.Totp(
                validCodeAt(enrollment.secret(), "2026-10-12T09:06:00Z")), "2026-10-12T09:06:00Z");

        assertThat(withCode.outcome()).isInstanceOf(Completed.class);
        assertThat(loginAt(SOFIA, CURRENT, "2026-10-12T09:07:00Z")).isInstanceOf(Rejected.class);
        assertThat(completedSecondFactor()).containsExactly("totp");
    }

    /** Scenario I10: a recovery code replaces TOTP, is used up, and nine remain. */
    @Test
    void aRecoveryCodeReplacesTheTotpCodeAndIsUsedUp() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT, true);
        EnrollTotpSecondFactorResult enrollment = enroll(sofia);
        PlainRecoveryCode code = enrollment.recoveryCodes().get(0);
        PlainPasswordResetToken token = issueTokenAt(sofia, "2026-10-12T11:00:00Z");

        ResetPasswordDecision decision = resetAt(token.value(), NEW,
                new SecondFactorProof.RecoveryCode(code), "2026-10-12T11:04:00Z");

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(unusedRecoveryCodes(sofia)).isEqualTo(9L);
        assertThat(completedSecondFactor()).containsExactly("recovery-code");

        PlainPasswordResetToken later = issueTokenAt(sofia, "2026-10-12T12:00:00Z");
        ResetPasswordDecision reused = resetAt(later.value(), "Otra contraseña 2026",
                new SecondFactorProof.RecoveryCode(code), "2026-10-12T12:01:00Z");

        assertThat(reused.outcome())
                .as("a used recovery code no longer counts as a second factor")
                .isInstanceOf(SecondFactorRejected.class);
        assertThat(isOpen(later)).isTrue();
    }

    /** Scenario I46: consuming one of three remaining codes audits the low-codes signal. */
    @Test
    void consumingOneOfThreeRemainingCodesInAResetAuditsTheLowCodesSignal() {
        StaffAccountId sofia = seedStaffAccount(SOFIA, CURRENT, true);
        EnrollTotpSecondFactorResult enrollment = enroll(sofia);
        List<PlainRecoveryCode> codes = enrollment.recoveryCodes();
        leaveOnlyTheFirstThreeUnused(sofia, codes);
        PlainPasswordResetToken token = issueTokenAt(sofia, "2026-10-12T11:00:00Z");

        ResetPasswordDecision decision = resetAt(token.value(), NEW,
                new SecondFactorProof.RecoveryCode(codes.get(0)), "2026-10-12T11:04:00Z");

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(auditRows("identity.mfa.recovery_codes.low")).singleElement()
                .extracting(row -> JSON.readTree(row.afterValue()).get("remainingUnusedCodes")
                        .asInt())
                .isEqualTo(2);
        assertThat(auditRows()).extracting(row -> row.action().toLowerCase(Locale.ROOT))
                .noneMatch(action -> action.contains("mail") || action.contains("notif")
                        || action.contains("sent"));
    }

    private void leaveOnlyTheFirstThreeUnused(StaffAccountId accountId,
            List<PlainRecoveryCode> codes) {
        ConsumeRecoveryCode consume = new ConsumeRecoveryCode(transactionRunner(),
                new com.confia.identity.infrastructure.JooqRecoveryCodeRepository(dsl),
                new com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher(
                        com.confia.identity.infrastructure.Argon2Profile.floor(), PEPPER),
                new com.confia.shared.infrastructure.JooqAuditLogWriter(dsl),
                fixedClock("2026-10-12T10:00:00Z"));
        for (PlainRecoveryCode code : codes.subList(3, codes.size())) {
            assertThat(consume.execute(context(), accountId, code).accepted()).isTrue();
        }
        assertThat(unusedRecoveryCodes(accountId)).isEqualTo(3L);
    }

    private long unusedRecoveryCodes(StaffAccountId accountId) {
        return transactionRunner().execute(context(), () -> dsl.fetchOne("""
                select count(*) as c from identity_mfa_recovery_code
                where institution_id = ? and account_id = ? and used_at is null
                """, institutionId.value(), accountId.value()).get("c", Number.class).longValue());
    }

    private List<String> completedSecondFactor() {
        return auditRows("identity.password_reset.completed").stream()
                .map(AuditRowSnapshot::afterValue)
                .map(after -> JSON.readTree(after).get("secondFactor").asString())
                .toList();
    }
}

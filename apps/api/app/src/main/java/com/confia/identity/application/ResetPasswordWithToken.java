package com.confia.identity.application;

import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.application.ResetOutcome.PasswordRejected;
import com.confia.identity.application.ResetOutcome.SecondFactorMissing;
import com.confia.identity.application.ResetOutcome.TokenRejected;
import com.confia.identity.domain.PasswordResetRejectionReason;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenPolicy;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffPasswordLengthPolicy;
import com.confia.identity.domain.StaffPasswordLengthPolicy.Accepted;
import com.confia.identity.domain.StaffPasswordLengthPolicy.TooLong;
import com.confia.identity.domain.StaffPasswordLengthPolicy.TooShort;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A password reset with a single-use token (password-recovery-token design.md decision 7; §4.3).
 * Everything happens in one transaction, in an order chosen so each rejection costs as little as
 * possible and never spends what it should not:
 *
 * <ol>
 *   <li>hash the presented token and look it up, without a lock: a made-up token costs a SHA-256
 *       and one indexed read, never a lock, a TOTP check or Argon2id;</li>
 *   <li>check the new password's length before anything else, so a password of the wrong length
 *       never locks the account nor spends a second-factor attempt;</li>
 *   <li>lock the account row, the same lock issuance takes (decision 2);</li>
 *   <li>when the account has active MFA, check the second factor; otherwise ignore any proof;</li>
 *   <li>consume the token with one conditional update: a lost race is a token rejection, and only
 *       the winner pays Argon2id;</li>
 *   <li>hash the new password, replace it, and audit.</li>
 * </ol>
 *
 * <p>Every rejection is a returned value, never an exception, so {@link TransactionRunner} commits
 * it: a wrong second factor keeps its backoff and its audit entry, and the token stays alive.
 */
public final class ResetPasswordWithToken {

    private static final String ACTION_COMPLETED = "identity.password_reset.completed";
    private static final String ACTION_REJECTED = "identity.password_reset.rejected";
    private static final String TOKEN_ENTITY_TYPE = "identity.password_reset_token";
    private static final String STAFF_ACCOUNT_ENTITY_TYPE = "identity.staff_account";
    private static final String UNKNOWN_TOKEN_LABEL = "unknown-token";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final LoginInstitutionProvider institutionProvider;
    private final PasswordResetTokenRepository tokens;
    private final StaffAccountRepository accounts;
    private final TotpCredentialRepository totpCredentials;
    private final VerifyTotpCode verifyTotp;
    private final ConsumeRecoveryCode consumeRecoveryCode;
    private final PasswordHasher passwordHasher;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final PasswordResetTokenPolicy tokenPolicy;
    private final StaffPasswordLengthPolicy lengthPolicy;

    public ResetPasswordWithToken(TransactionRunner transactionRunner,
            LoginInstitutionProvider institutionProvider, PasswordResetTokenRepository tokens,
            StaffAccountRepository accounts, TotpCredentialRepository totpCredentials,
            VerifyTotpCode verifyTotp, ConsumeRecoveryCode consumeRecoveryCode,
            PasswordHasher passwordHasher, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.institutionProvider = Objects.requireNonNull(institutionProvider, "institutionProvider");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.totpCredentials = Objects.requireNonNull(totpCredentials, "totpCredentials");
        this.verifyTotp = Objects.requireNonNull(verifyTotp, "verifyTotp");
        this.consumeRecoveryCode = Objects.requireNonNull(consumeRecoveryCode, "consumeRecoveryCode");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tokenPolicy = new PasswordResetTokenPolicy();
        this.lengthPolicy = new StaffPasswordLengthPolicy();
    }

    public ResetPasswordDecision execute(SecurityContext context, ResetPasswordCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");

        InstitutionId contextInstitutionId =
                new InstitutionId(UUID.fromString(context.institutionId()));
        if (!contextInstitutionId.equals(institutionProvider.loginInstitutionId())) {
            // Part 1's decision 11, literally: a wiring defect or an attack, never a rejection.
            throw new IllegalStateException(
                    "password reset institution mismatch: the security context declares a "
                            + "different institution than the configured login institution");
        }

        return transactionRunner.execute(context,
                () -> runWithinTransaction(contextInstitutionId, context.requestId(), command));
    }

    ResetPasswordDecision runWithinTransaction(InstitutionId institutionId, String requestId,
            ResetPasswordCommand command) {
        Instant now = clock.instant();
        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);

        // 1-2. The token first: no lock, no Argon2id, no second factor for a made-up one.
        Optional<PasswordResetTokenRow> found = hashOf(command.presentedToken())
                .flatMap(hash -> tokens.findByHash(institutionId, hash));
        if (found.isEmpty()) {
            auditLogWriter.append(tokenRejection(institutionId, auditRequestId, null,
                    PasswordResetRejectionReason.TOKEN_NOT_FOUND));
            return decision(new TokenRejected());
        }
        PasswordResetTokenRow row = found.get();
        Optional<PasswordResetRejectionReason> tokenReason = tokenPolicy.rejectionReasonOf(row, now);
        if (tokenReason.isPresent()) {
            auditLogWriter.append(tokenRejection(institutionId, auditRequestId, row,
                    tokenReason.get()));
            return decision(new TokenRejected());
        }

        // 3. The length, before the lock and before any second-factor attempt.
        PlainPassword newPassword;
        switch (lengthPolicy.check(command.newPassword())) {
            case Accepted accepted -> newPassword = accepted.password();
            case TooShort tooShort -> {
                return passwordRejection(institutionId, auditRequestId, row,
                        PasswordResetRejectionReason.PASSWORD_TOO_SHORT);
            }
            case TooLong tooLong -> {
                return passwordRejection(institutionId, auditRequestId, row,
                        PasswordResetRejectionReason.PASSWORD_TOO_LONG);
            }
        }

        // 4. The account lock, the same one issuance takes.
        Optional<StaffAccount> account = accounts.lockById(institutionId, row.accountId());
        if (account.isEmpty()) {
            // No role may delete an account, so this cannot happen; the token is still refused.
            auditLogWriter.append(tokenRejection(institutionId, auditRequestId, row,
                    PasswordResetRejectionReason.TOKEN_NOT_FOUND));
            return decision(new TokenRejected());
        }

        // 5. The second factor, only when MFA is active; the same expression as login.
        if (mfaActive(institutionId, account.get())) {
            auditLogWriter.append(accountRejection(institutionId, auditRequestId, row,
                    PasswordResetRejectionReason.SECOND_FACTOR_MISSING));
            return decision(new SecondFactorMissing());
        }

        // 6. Consume: the conditional update decides the winner of a race.
        if (!tokens.consume(institutionId, row.id(), now)) {
            auditLogWriter.append(tokenRejection(institutionId, auditRequestId, row,
                    PasswordResetRejectionReason.TOKEN_CONSUMED));
            return decision(new TokenRejected());
        }

        // 7-9. Only the winner pays Argon2id.
        accounts.replacePasswordHash(institutionId, row.accountId(),
                passwordHasher.hash(newPassword));
        auditLogWriter.append(new AuditEntry(institutionId.value(), row.accountId().value(),
                ACTOR_KIND_STAFF, row.accountId().value().toString(), null, null, auditRequestId,
                null, ACTION_COMPLETED, STAFF_ACCOUNT_ENTITY_TYPE,
                row.accountId().value().toString(), OUTCOME_SUCCESS, null,
                "{\"secondFactor\":\"none\"}", null, null));
        return decision(new Completed());
    }

    private boolean mfaActive(InstitutionId institutionId, StaffAccount account) {
        return account.mfaRequired()
                && totpCredentials.findByAccountId(institutionId, account.id()).isPresent();
    }

    /** A malformed token is simply not found: it cannot be the text of any issued token. */
    private static Optional<PasswordResetTokenHash> hashOf(String presentedToken) {
        try {
            return Optional.of(PasswordResetTokenHash.of(PlainPasswordResetToken.of(presentedToken)));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private ResetPasswordDecision passwordRejection(InstitutionId institutionId, UUID requestId,
            PasswordResetTokenRow row, PasswordResetRejectionReason reason) {
        auditLogWriter.append(accountRejection(institutionId, requestId, row, reason));
        return decision(new PasswordRejected(reason));
    }

    private static ResetPasswordDecision decision(ResetOutcome outcome) {
        return new ResetPasswordDecision(outcome, Duration.ZERO);
    }

    /** Entity: the token row, or the {@code unknown-token} label when there is no row. */
    private static AuditEntry tokenRejection(InstitutionId institutionId, UUID requestId,
            PasswordResetTokenRow row, PasswordResetRejectionReason reason) {
        UUID actorId = row == null ? null : row.accountId().value();
        String actorLabel = row == null ? UNKNOWN_TOKEN_LABEL : actorId.toString();
        String entityId = row == null ? UNKNOWN_TOKEN_LABEL : row.id().toString();
        return new AuditEntry(institutionId.value(), actorId, ACTOR_KIND_STAFF, actorLabel, null,
                null, requestId, null, ACTION_REJECTED, TOKEN_ENTITY_TYPE, entityId,
                OUTCOME_DENIED, null, reasonJson(reason), null, null);
    }

    /** Entity: the account, for password and second-factor rejections. */
    private static AuditEntry accountRejection(InstitutionId institutionId, UUID requestId,
            PasswordResetTokenRow row, PasswordResetRejectionReason reason) {
        String accountId = row.accountId().value().toString();
        return new AuditEntry(institutionId.value(), row.accountId().value(), ACTOR_KIND_STAFF,
                accountId, null, null, requestId, null, ACTION_REJECTED,
                STAFF_ACCOUNT_ENTITY_TYPE, accountId, OUTCOME_DENIED, null, reasonJson(reason),
                null, null);
    }

    private static String reasonJson(PasswordResetRejectionReason reason) {
        return "{\"reason\":\"" + reason.auditCode() + "\"}";
    }
}

package com.confia.identity.application;

import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenPolicy;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues one password-reset token for an account: the body of the future worker handler
 * (password-recovery-token design.md decisions 2, 5, 6 and 9; §4.2). It receives a {@link
 * SecurityContext} with actor kind {@code system} and the institution taken from the task (ADR-0016
 * rule 4), so it applies no {@link LoginInstitutionProvider} guard: the row policy scopes it.
 *
 * <p>Inside one transaction it locks the account row first, which serializes every issuance and
 * reset of that account (decision 2); counts the issuances in the rolling sixty-minute window and
 * skips at three; otherwise supersedes every open token, inserts the new one with the expiry from
 * {@link PasswordResetTokenPolicy}, and audits. <b>Only after the transaction commits, and outside
 * it</b>, does it hand the clear-text token to {@link PasswordResetLinkSender}: never with the lock
 * held, and never for a token whose transaction rolled back.
 */
public final class IssuePasswordResetToken {

    private static final String ACTION_ISSUED = "identity.password_reset.issued";
    private static final String ACTION_ISSUANCE_SKIPPED = "identity.password_reset.issuance_skipped";
    private static final String TOKEN_ENTITY_TYPE = "identity.password_reset_token";
    private static final String STAFF_ACCOUNT_ENTITY_TYPE = "identity.staff_account";
    private static final String ACTOR_KIND_SYSTEM = "system";
    private static final String ACTOR_LABEL = "password-reset-issuance";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final StaffAccountRepository accounts;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetLinkSender linkSender;
    private final AuditLogWriter auditLogWriter;
    private final SecureRandom secureRandom;
    private final Clock clock;
    private final PasswordResetTokenPolicy policy;

    public IssuePasswordResetToken(TransactionRunner transactionRunner,
            StaffAccountRepository accounts, PasswordResetTokenRepository tokens,
            PasswordResetLinkSender linkSender, AuditLogWriter auditLogWriter,
            SecureRandom secureRandom, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.linkSender = Objects.requireNonNull(linkSender, "linkSender");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = new PasswordResetTokenPolicy();
    }

    public IssuePasswordResetTokenDecision execute(SecurityContext context,
            StaffAccountId accountId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(accountId, "accountId");
        InstitutionId institutionId = new InstitutionId(UUID.fromString(context.institutionId()));

        Issuance issuance = transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, context.requestId(), accountId));

        issuance.token().ifPresent(token -> linkSender.send(institutionId, accountId, token));
        return issuance.decision();
    }

    Issuance runWithinTransaction(InstitutionId institutionId, String requestId,
            StaffAccountId accountId) {
        if (accounts.lockById(institutionId, accountId).isEmpty()) {
            return new Issuance(IssuePasswordResetTokenDecision.ACCOUNT_NOT_FOUND, Optional.empty());
        }
        Instant now = clock.instant();
        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);

        long issuedInWindow = tokens.countIssuedSince(institutionId, accountId,
                policy.issuanceWindowStart(now));
        if (!policy.allowsAnotherIssuance(issuedInWindow)) {
            auditLogWriter.append(entry(institutionId, auditRequestId, ACTION_ISSUANCE_SKIPPED,
                    STAFF_ACCOUNT_ENTITY_TYPE, accountId.value().toString(), OUTCOME_DENIED,
                    "{\"reason\":\"rate-limit\",\"issuedInWindow\":" + issuedInWindow + "}"));
            return new Issuance(IssuePasswordResetTokenDecision.SKIPPED, Optional.empty());
        }

        PlainPasswordResetToken token = PlainPasswordResetToken.generate(secureRandom);
        int supersededCount = tokens.supersedeOpen(institutionId, accountId, now);
        UUID tokenId = UUID.randomUUID();
        Instant expiresAt = policy.expiresAt(now);
        tokens.insert(institutionId, tokenId, accountId, PasswordResetTokenHash.of(token), now,
                expiresAt);
        auditLogWriter.append(entry(institutionId, auditRequestId, ACTION_ISSUED,
                TOKEN_ENTITY_TYPE, tokenId.toString(), OUTCOME_SUCCESS,
                "{\"expiresAt\":\"" + expiresAt + "\",\"supersededCount\":" + supersededCount
                        + "}"));
        return new Issuance(IssuePasswordResetTokenDecision.ISSUED, Optional.of(token));
    }

    private static AuditEntry entry(InstitutionId institutionId, UUID requestId, String action,
            String entityType, String entityId, String outcome, String afterValue) {
        return new AuditEntry(institutionId.value(), null, ACTOR_KIND_SYSTEM, ACTOR_LABEL, null,
                null, requestId, null, action, entityType, entityId, outcome, null, afterValue,
                null, null);
    }

    /** The committed outcome, and the token to send once the transaction is over. */
    record Issuance(IssuePasswordResetTokenDecision decision,
            Optional<PlainPasswordResetToken> token) {

        Issuance {
            Objects.requireNonNull(decision, "decision");
            Objects.requireNonNull(token, "token");
        }
    }
}

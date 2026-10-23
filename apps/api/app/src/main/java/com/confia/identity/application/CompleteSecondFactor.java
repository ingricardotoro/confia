package com.confia.identity.application;

import com.confia.identity.domain.BackoffPolicy;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpCode;
import com.confia.identity.domain.TotpCredential;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecondFactorPurpose;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The completion of a second factor (session-endpoints-cookie-and-csrf design.md, decision 13;
 * specs/identity/spec.md, "La verificación del segundo factor se ofrece en ... con un token
 * restringido" and "La primera verificación correcta activa la credencial y revoca las demás
 * sesiones con el motivo mfa_change"): a restricted token and one proof become a session, in <b>one</b>
 * transaction opened by {@link TransactionRunner}.
 *
 * <p><b>What each purpose may prove.</b> Under the lock of the account, the purpose of the token and
 * the state of the credential decide what is looked at, and a proof that the state does not accept is
 * refused without being judged:
 *
 * <ul>
 *   <li>{@code mfa-verify} with a verified credential: a TOTP code or a recovery code.
 *   <li>{@code mfa-enroll} with a credential never verified (the first verification): a TOTP code
 *       only. A recovery code proves that the user saw the answer of the enrollment, not that the
 *       secret is in an authenticator, so it activates nothing and is not consumed.
 *   <li>{@code mfa-enroll} with a verified credential, {@code mfa-verify} with an unverified one, or
 *       no credential at all: nothing is accepted.
 * </ul>
 *
 * <p><b>One backoff.</b> A TOTP code is judged by {@link VerifyTotpCode}, which claims and saves the
 * backoff of the account. A recovery code takes the same claim before and the same save after, so a
 * wrong recovery code is a failure of the same counter and a guessing campaign cannot switch from one
 * kind of proof to the other to escape the delay. A proof the state does not accept, or a text that
 * cannot be a code, touches no counter.
 *
 * <p><b>The first verification</b> (the credential was never verified and the TOTP code is accepted,
 * which is what activates it) revokes every other live family of the account with {@code mfa_change}
 * and only then issues the new one, so the family this verification opens is never revoked by it. The
 * entry of the revocation exists even for zero families. Every step is in the one transaction: a
 * failure anywhere leaves the credential unverified, the previous families alive and no new family.
 *
 * <p><b>After the commit</b> the access token is signed, as {@link LogIn} does. Nothing is signed for
 * a transaction that did not commit. <b>Never waits</b>: the delay of the backoff is returned for the
 * edge to materialize.
 */
public final class CompleteSecondFactor {

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String CREDENTIAL_ENTITY_TYPE = "identity.mfa_totp_credential";
    private static final String BACKOFF_ENTITY_TYPE = "identity.mfa_totp_backoff";
    private static final String ACTION_ACTIVATED = "identity.mfa.activated";
    private static final String ACTION_BACKOFF_APPLIED = "identity.mfa.totp_verification.backoff_applied";
    private static final String OUTCOME_SUCCESS = "success";

    private final TransactionRunner transactionRunner;
    private final StaffAccountRepository accounts;
    private final TotpCredentialRepository credentials;
    private final VerifyTotpCode verifyTotp;
    private final ConsumeRecoveryCode consumeRecoveryCode;
    private final TotpVerificationBackoffStore backoffStore;
    private final AccountSessionRevoker sessionRevoker;
    private final IssueSession issueSession;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final BackoffPolicy backoffPolicy;

    public CompleteSecondFactor(TransactionRunner transactionRunner, StaffAccountRepository accounts,
            TotpCredentialRepository credentials, VerifyTotpCode verifyTotp,
            ConsumeRecoveryCode consumeRecoveryCode, TotpVerificationBackoffStore backoffStore,
            AccountSessionRevoker sessionRevoker, IssueSession issueSession,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.verifyTotp = Objects.requireNonNull(verifyTotp, "verifyTotp");
        this.consumeRecoveryCode = Objects.requireNonNull(consumeRecoveryCode, "consumeRecoveryCode");
        this.backoffStore = Objects.requireNonNull(backoffStore, "backoffStore");
        this.sessionRevoker = Objects.requireNonNull(sessionRevoker, "sessionRevoker");
        this.issueSession = Objects.requireNonNull(issueSession, "issueSession");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.backoffPolicy = new BackoffPolicy();
    }

    public CompleteSecondFactorDecision execute(CompleteSecondFactorCommand command) {
        Objects.requireNonNull(command, "command");
        UUID requestId = command.requestId().isBlank() ? UUID.randomUUID()
                : UUID.fromString(command.requestId());
        InstitutionId institutionId = new InstitutionId(command.institutionId());
        StaffAccountId accountId = new StaffAccountId(command.accountId());
        SecurityContext context = new SecurityContext(command.accountId().toString(),
                ACTOR_KIND_STAFF, command.institutionId().toString(), requestId.toString());

        Committed committed = transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, accountId, requestId, command));

        if (committed.stored() == null) {
            return new CompleteSecondFactorDecision(new SecondFactorOutcome.Rejected(),
                    committed.requiredDelay());
        }
        IssuedSession issued = issueSession.signAfterCommit(institutionId,
                new IssueSessionCommand(accountId, true, command.userAgent(),
                        command.clientAddress()), committed.stored());
        SessionGrant grant = new SessionGrant(issued.accessToken().compact(),
                issued.accessExpiresAt(), issued.refreshToken().value(),
                committed.stored().absoluteExpiresAt(), committed.stored().idleExpiresAt());
        return new CompleteSecondFactorDecision(new SecondFactorOutcome.Started(grant),
                committed.requiredDelay());
    }

    private Committed runWithinTransaction(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, CompleteSecondFactorCommand command) {
        // 1. The account first (account, then credential, then families), the order of issuance,
        // reset and enrollment.
        accounts.lockById(institutionId, accountId).orElseThrow(() -> new IllegalStateException(
                "a second factor cannot be completed for an account that does not exist"));

        // 2. What the state of the credential lets the purpose prove.
        Optional<TotpCredential> credential = credentials.findByAccountId(institutionId, accountId);
        if (credential.isEmpty()) {
            return rejected(Duration.ZERO);
        }
        boolean verified = credential.get().isVerified();
        Proof proof = acceptedProof(command.purpose(), verified, command);
        if (proof == Proof.NONE) {
            return rejected(Duration.ZERO);
        }

        // 3. The proof, against the shared backoff.
        Judgement judgement = judge(institutionId, accountId, requestId, command, proof);
        if (!judgement.accepted()) {
            return rejected(judgement.requiredDelay());
        }

        // 4. The first verification activates the credential (the accepted counter did it) and ends
        // every other session of the account, before the new family exists.
        Instant now = clock.instant();
        if (!verified) {
            int revoked = sessionRevoker.revokeAllLive(institutionId, accountId,
                    SessionRevocationReason.MFA_CHANGE, now);
            auditLogWriter.append(SessionRevocationAudit.mfaChange(institutionId, accountId,
                    requestId, revoked));
            auditLogWriter.append(activatedEntry(institutionId, accountId, requestId));
        }

        // 5. The session, in the same transaction, with the second factor marked as verified.
        IssueSession.Stored stored = issueSession.runWithinTransaction(institutionId,
                requestId.toString(), new IssueSessionCommand(accountId, true, command.userAgent(),
                        command.clientAddress()));
        return new Committed(stored, judgement.requiredDelay());
    }

    /** Which of the proofs of the command the purpose and the state accept, if any. */
    private static Proof acceptedProof(SecondFactorPurpose purpose, boolean verified,
            CompleteSecondFactorCommand command) {
        boolean totp = command.totpCode() != null;
        return switch (purpose) {
            case VERIFY -> !verified ? Proof.NONE : (totp ? Proof.TOTP : Proof.RECOVERY_CODE);
            case ENROLL -> verified || !totp ? Proof.NONE : Proof.TOTP;
        };
    }

    private Judgement judge(InstitutionId institutionId, StaffAccountId accountId, UUID requestId,
            CompleteSecondFactorCommand command, Proof proof) {
        return switch (proof) {
            case TOTP -> judgeTotp(institutionId, accountId, requestId, command.totpCode());
            case RECOVERY_CODE -> judgeRecoveryCode(institutionId, accountId, requestId,
                    command.recoveryCode());
            case NONE -> throw new IllegalStateException("a proof that is not accepted is not judged");
        };
    }

    private Judgement judgeTotp(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, String presented) {
        TotpCode code;
        try {
            code = new TotpCode(presented);
        } catch (IllegalArgumentException cannotBeACode) {
            return new Judgement(false, Duration.ZERO);
        }
        VerifyTotpCodeDecision decision = verifyTotp.runWithinTransaction(institutionId, accountId,
                requestId.toString(), code);
        return new Judgement(decision.accepted(), decision.requiredDelay());
    }

    /**
     * A recovery code against the same backoff as the TOTP codes: the claim before, the consumption,
     * the save after, and the entry of the cycle when it owes a delay, exactly as {@link
     * VerifyTotpCode} does for its own.
     */
    private Judgement judgeRecoveryCode(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, String presented) {
        PlainRecoveryCode code;
        try {
            code = PlainRecoveryCode.of(presented);
        } catch (IllegalArgumentException cannotBeACode) {
            return new Judgement(false, Duration.ZERO);
        }
        Instant now = clock.instant();
        BackoffState prior = backoffStore.claim(institutionId, accountId, now);
        int attemptOrdinal = backoffPolicy.attemptOrdinal(prior, now);
        Duration requiredDelay = backoffPolicy.delayFor(attemptOrdinal);

        boolean accepted = consumeRecoveryCode.runWithinTransaction(institutionId, accountId,
                requestId.toString(), code).accepted();

        backoffStore.save(institutionId, accountId, accepted ? backoffPolicy.afterSuccess(now)
                : backoffPolicy.afterFailure(attemptOrdinal, now));
        if (requiredDelay.compareTo(Duration.ZERO) > 0) {
            auditLogWriter.append(new AuditEntry(institutionId.value(), accountId.value(),
                    ACTOR_KIND_STAFF, accountId.value().toString(), null, null, requestId, null,
                    ACTION_BACKOFF_APPLIED, BACKOFF_ENTITY_TYPE, accountId.value().toString(),
                    OUTCOME_SUCCESS, null,
                    VerifyTotpCode.backoffCycleAfterValue(attemptOrdinal, requiredDelay), null,
                    null));
        }
        return new Judgement(accepted, requiredDelay);
    }

    private static AuditEntry activatedEntry(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId) {
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF,
                accountId.value().toString(), null, null, requestId, null, ACTION_ACTIVATED,
                CREDENTIAL_ENTITY_TYPE, accountId.value().toString(), OUTCOME_SUCCESS, null, null,
                null, null);
    }

    private static Committed rejected(Duration requiredDelay) {
        return new Committed(null, requiredDelay);
    }

    /** Which proof of the command is judged. */
    private enum Proof {
        NONE, TOTP, RECOVERY_CODE
    }

    /** Whether the proof was accepted, and the delay of the backoff the answer still owes. */
    private record Judgement(boolean accepted, Duration requiredDelay) {
    }

    /** What the transaction leaves behind for the half that runs after the commit; no family when rejected. */
    private record Committed(IssueSession.Stored stored, Duration requiredDelay) {
    }
}

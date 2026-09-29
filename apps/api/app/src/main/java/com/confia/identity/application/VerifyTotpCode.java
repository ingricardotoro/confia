package com.confia.identity.application;

import com.confia.identity.domain.BackoffPolicy;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpCode;
import com.confia.identity.domain.TotpCredential;
import com.confia.identity.domain.TotpVerificationPolicy;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Verifies a presented TOTP code against the account's already-enrolled secret
 * (column-encryption-and-mfa-totp design.md, §4.2; specs/identity/spec.md, requirements
 * "Inscripción y verificación del segundo factor TOTP..." and "Límite de tasa sobre la
 * verificación de código TOTP"). The seven steps design.md §4.2 draws inside one transaction:
 * claim the backoff state, read the credential, decrypt its secret, match the tolerance window
 * with anti-repetition, conditionally persist the accepted counter, persist the backoff state, and
 * audit the outcome.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): {@link #execute} delegates
 * the whole body to {@link TransactionRunner#execute(SecurityContext, java.util.function.Supplier)},
 * the same pattern {@link AuthenticateWithPassword} already establishes for the password step.
 *
 * <p><b>Never waits</b> (design.md, decision 1 of the part-1 design; {@link
 * VerifyTotpCodeDecision}'s own Javadoc): this class computes {@code requiredDelay} and returns it
 * once its transaction has already committed; materializing the wait is the caller's job.
 */
public final class VerifyTotpCode {

    private static final Duration PERIOD = Duration.ofSeconds(30);
    private static final String TABLE = "identity_mfa_totp_credential";
    private static final String COLUMN = "encrypted_secret";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String CREDENTIAL_ENTITY_TYPE = "identity.mfa_totp_credential";
    private static final String BACKOFF_ENTITY_TYPE = "identity.mfa_totp_backoff";
    private static final String ACTION_VERIFICATION_SUCCEEDED =
            "identity.mfa.totp_verification.succeeded";
    private static final String ACTION_VERIFICATION_FAILED =
            "identity.mfa.totp_verification.failed";
    private static final String ACTION_BACKOFF_APPLIED =
            "identity.mfa.totp_verification.backoff_applied";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final TotpCredentialRepository credentials;
    private final TotpVerificationBackoffStore backoffStore;
    private final ColumnEncryptionService encryption;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final BackoffPolicy backoffPolicy;
    private final TotpVerificationPolicy verificationPolicy;

    public VerifyTotpCode(TransactionRunner transactionRunner, TotpCredentialRepository credentials,
            TotpVerificationBackoffStore backoffStore, ColumnEncryptionService encryption,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.backoffStore = Objects.requireNonNull(backoffStore, "backoffStore");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.backoffPolicy = new BackoffPolicy();
        this.verificationPolicy = new TotpVerificationPolicy();
    }

    public VerifyTotpCodeDecision execute(SecurityContext context, StaffAccountId accountId,
            TotpCode presented) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(presented, "presented");

        InstitutionId institutionId = new InstitutionId(UUID.fromString(context.institutionId()));
        return transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, accountId, context.requestId(), presented));
    }

    /** Package-private so {@code VerifyTotpCodeTest} (if written) could exercise this exact
     * sequence with the ports doubled, mirroring {@code AuthenticateWithPassword}'s own split. */
    VerifyTotpCodeDecision runWithinTransaction(InstitutionId institutionId,
            StaffAccountId accountId, String requestId, TotpCode presented) {
        Instant now = clock.instant();

        BackoffState priorState = backoffStore.claim(institutionId, accountId, now);
        int attemptOrdinal = backoffPolicy.attemptOrdinal(priorState, now);
        Duration requiredDelay = backoffPolicy.delayFor(attemptOrdinal);

        boolean accepted = tryAccept(institutionId, accountId, now, presented);

        BackoffState newState = accepted
                ? backoffPolicy.afterSuccess(now)
                : backoffPolicy.afterFailure(attemptOrdinal, now);
        backoffStore.save(institutionId, accountId, newState);

        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        auditLogWriter.append(outcomeEntry(institutionId, accountId, auditRequestId, accepted));
        if (requiredDelay.compareTo(Duration.ZERO) > 0) {
            auditLogWriter.append(backoffCycleEntry(institutionId, accountId, auditRequestId,
                    attemptOrdinal, requiredDelay));
        }

        return new VerifyTotpCodeDecision(accepted, requiredDelay);
    }

    /**
     * Design.md §4.2, steps 2-5: reads the credential, decrypts its secret, matches the tolerance
     * window with anti-repetition, and conditionally persists the accepted counter. Returns
     * {@code false} whenever any of those steps does not end in an accepted counter — including
     * the case where {@link TotpCredentialRepository#acceptCounter} affects zero rows because a
     * concurrent verification already won (design.md decision 7, step 5a: "0 filas = derrota
     * concurrente, se trata como rechazo aunque el código fuera válido").
     */
    private boolean tryAccept(InstitutionId institutionId, StaffAccountId accountId, Instant now,
            TotpCode presented) {
        Optional<TotpCredential> credential = credentials.findByAccountId(institutionId, accountId);
        if (credential.isEmpty()) {
            return false;
        }
        TotpCredential found = credential.get();
        byte[] secret = decryptOrFail(institutionId, accountId, found.encryptedSecret());
        Optional<Long> matchingCounter = verificationPolicy.matchingCounter(secret,
                found.lastAcceptedCounter(), now, PERIOD, presented);
        return matchingCounter.isPresent()
                && credentials.acceptCounter(institutionId, accountId, matchingCounter.get());
    }

    private byte[] decryptOrFail(InstitutionId institutionId, StaffAccountId accountId,
            String storedValue) {
        try {
            return encryption.decrypt(TABLE, COLUMN, institutionId,
                    rowIdOf(institutionId, accountId), storedValue);
        } catch (AeadIntegrityException e) {
            throw new IllegalStateException("stored TOTP secret failed AEAD verification", e);
        }
    }

    /** {@code institutionId:accountId} (design.md, decision 5): the row's own composite key. */
    private static String rowIdOf(InstitutionId institutionId, StaffAccountId accountId) {
        return institutionId.value() + ":" + accountId.value();
    }

    /** Design.md, §4.2 step 7: the verification-succeeded or verification-failed audit row. Entity
     * id is the account id itself, never hashed (design.md, decision 8: the account is already
     * identified by a correct password, so there is no enumeration oracle to protect here). */
    private static AuditEntry outcomeEntry(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, boolean accepted) {
        String action = accepted ? ACTION_VERIFICATION_SUCCEEDED : ACTION_VERIFICATION_FAILED;
        String outcome = accepted ? OUTCOME_SUCCESS : OUTCOME_DENIED;
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF,
                accountId.value().toString(), null, null, requestId, null, action,
                CREDENTIAL_ENTITY_TYPE, accountId.value().toString(), outcome, null, null, null,
                null);
    }

    /** Design.md, §4.2 step 8: the backoff-cycle audit row, written only when {@code requiredDelay
     * > 0}. */
    private static AuditEntry backoffCycleEntry(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, int attemptOrdinal, Duration requiredDelay) {
        String afterValue = "{\"consecutiveFailures\":" + attemptOrdinal + ",\"delaySeconds\":"
                + requiredDelay.toSeconds() + "}";
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF,
                accountId.value().toString(), null, null, requestId, null, ACTION_BACKOFF_APPLIED,
                BACKOFF_ENTITY_TYPE, accountId.value().toString(), OUTCOME_SUCCESS, null,
                afterValue, null, null);
    }
}

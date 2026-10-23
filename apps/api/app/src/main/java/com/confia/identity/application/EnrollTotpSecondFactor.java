package com.confia.identity.application;

import com.confia.identity.application.EnrollTotpSecondFactorResult.AlreadyEnrolled;
import com.confia.identity.application.EnrollTotpSecondFactorResult.Enrolled;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import com.confia.identity.domain.TotpCredential;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enrolls the TOTP second factor of an account: a fresh encrypted secret and exactly ten hashed MFA
 * recovery codes, in one transaction with one audit entry (column-encryption-and-mfa-totp design.md,
 * §4.1; session-endpoints-cookie-and-csrf design.md, decision 13; specs/identity/spec.md,
 * requirements "Inscripción y verificación del segundo factor TOTP...", "Diez códigos de recuperación
 * de MFA..." and "La inscripción del segundo factor ... es repetible hasta la primera verificación").
 *
 * <p><b>Repeatable until the first verification.</b> Under the lock of the account, in the order
 * account, credential, families that issuance and reset use as well: no credential is inserted; a
 * credential that was never verified has its secret replaced and the codes handed out with the
 * previous one retired (they were shown once, to a screen that may be lost); a verified credential is
 * never replaced, and the refusal writes only {@code identity.mfa.enrollment_rejected}. The lock is
 * what turns two concurrent enrollments of one account into an enrollment and a re-enrollment, one
 * after the other, instead of a uniqueness violation. Enrolling never activates the credential.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): {@link #execute} delegates the
 * whole body to {@link TransactionRunner#execute(SecurityContext, java.util.function.Supplier)}, the
 * same pattern {@link VerifyTotpCode} establishes.
 */
public final class EnrollTotpSecondFactor {

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String TABLE = "identity_mfa_totp_credential";
    private static final String COLUMN = "encrypted_secret";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String CREDENTIAL_ENTITY_TYPE = "identity.mfa_totp_credential";
    private static final String ACTION_ENROLLED = "identity.mfa.enrolled";
    private static final String ACTION_REJECTED = "identity.mfa.enrollment_rejected";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final StaffAccountRepository accounts;
    private final TotpCredentialRepository credentials;
    private final RecoveryCodeRepository recoveryCodes;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final ColumnEncryptionService encryption;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;
    private final SecureRandom secureRandom;

    public EnrollTotpSecondFactor(TransactionRunner transactionRunner,
            StaffAccountRepository accounts, TotpCredentialRepository credentials,
            RecoveryCodeRepository recoveryCodes, RecoveryCodeHasher recoveryCodeHasher,
            ColumnEncryptionService encryption, AuditLogWriter auditLogWriter, Clock clock) {
        this(transactionRunner, accounts, credentials, recoveryCodes, recoveryCodeHasher, encryption,
                auditLogWriter, clock, new SecureRandom());
    }

    /** Package-private: the same kind of testing seam {@code BouncyCastleArgon2PasswordHasher}
     * establishes for its own {@code SecureRandom}. */
    EnrollTotpSecondFactor(TransactionRunner transactionRunner, StaffAccountRepository accounts,
            TotpCredentialRepository credentials, RecoveryCodeRepository recoveryCodes,
            RecoveryCodeHasher recoveryCodeHasher, ColumnEncryptionService encryption,
            AuditLogWriter auditLogWriter, Clock clock, SecureRandom secureRandom) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.recoveryCodes = Objects.requireNonNull(recoveryCodes, "recoveryCodes");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public EnrollTotpSecondFactorResult execute(SecurityContext context, StaffAccountId accountId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(accountId, "accountId");

        InstitutionId institutionId = new InstitutionId(UUID.fromString(context.institutionId()));
        return transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, accountId, context.requestId()));
    }

    /** Package-private so the sequence can be exercised with the ports doubled. */
    EnrollTotpSecondFactorResult runWithinTransaction(InstitutionId institutionId,
            StaffAccountId accountId, String requestId) {
        accounts.lockById(institutionId, accountId).orElseThrow(() -> new IllegalStateException(
                "the account of an enrollment does not exist"));
        Instant now = clock.instant();
        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);

        Optional<TotpCredential> existing = credentials.findByAccountId(institutionId, accountId);
        if (existing.isPresent() && existing.get().isVerified()) {
            auditLogWriter.append(entry(institutionId, accountId, auditRequestId, ACTION_REJECTED,
                    OUTCOME_DENIED, rejectionAfterValue()));
            return new AlreadyEnrolled();
        }

        PlainTotpSecret secret = PlainTotpSecret.generate(secureRandom);
        String encryptedSecret = encryption.encryptForNewValue(TABLE, COLUMN, institutionId,
                rowIdOf(institutionId, accountId), secret.value());
        boolean replacedUnverified = existing.isPresent();
        int invalidatedCodes = 0;
        if (replacedUnverified) {
            if (!credentials.replaceUnverifiedSecret(institutionId, accountId, encryptedSecret, now)) {
                // The account is locked and the credential was read as unverified a moment ago.
                throw new IllegalStateException(
                        "the unverified secret of a locked account could not be replaced");
            }
            invalidatedCodes = recoveryCodes.invalidateUnused(institutionId, accountId, now);
        } else {
            credentials.insert(institutionId, accountId, encryptedSecret);
        }

        List<PlainRecoveryCode> plainCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            PlainRecoveryCode code = PlainRecoveryCode.generate(secureRandom);
            StoredRecoveryCodeHash hash = recoveryCodeHasher.hash(code);
            recoveryCodes.insert(institutionId, accountId, UUID.randomUUID(), hash);
            plainCodes.add(code);
        }

        auditLogWriter.append(entry(institutionId, accountId, auditRequestId, ACTION_ENROLLED,
                OUTCOME_SUCCESS, replacedUnverified ? reenrollmentAfterValue(invalidatedCodes) : null));
        return new Enrolled(secret, plainCodes, replacedUnverified);
    }

    /** {@code institutionId:accountId} (design.md, decision 5): the credential row's own composite
     * key, the same row-identifier shape {@link VerifyTotpCode} already uses. The additional data of
     * the secret does not change when it is replaced. */
    private static String rowIdOf(InstitutionId institutionId, StaffAccountId accountId) {
        return institutionId.value() + ":" + accountId.value();
    }

    /** The detail of a re-enrollment: that it replaced an unverified credential, and how many codes it retired. */
    static String reenrollmentAfterValue(int invalidatedRecoveryCodes) {
        return AuditJson.object().bool("replacedUnverified", true)
                .number("invalidatedRecoveryCodes", invalidatedRecoveryCodes).toJson();
    }

    /** The detail of a refusal: the credential is already verified. */
    static String rejectionAfterValue() {
        return AuditJson.object().text("reason", "already_enrolled").toJson();
    }

    /** One entry covering the credential and the ten recovery codes, never one entry per code. */
    private static AuditEntry entry(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, String action, String outcome, String afterValue) {
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF,
                accountId.value().toString(), null, null, requestId, null, action,
                CREDENTIAL_ENTITY_TYPE, accountId.value().toString(), outcome, null, afterValue,
                null, null);
    }
}

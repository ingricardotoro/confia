package com.confia.identity.application;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Enrolls the TOTP second factor of an account: a fresh encrypted secret and exactly ten hashed
 * MFA recovery codes, in one transaction with a single audit event
 * (column-encryption-and-mfa-totp design.md, §4.1; specs/identity/spec.md, requirements
 * "Inscripción y verificación del segundo factor TOTP..." and "Diez códigos de recuperación de
 * MFA..."). The five steps design.md §4.1 draws inside one transaction: generate and encrypt the
 * secret, insert the credential, generate and hash ten recovery codes, insert all ten, and audit
 * once.
 *
 * <p><b>Built entire, in this cut (C3), never split across C2 and C3</b> — the tasks.md
 * discrepancy resolution this change's own apply-progress.md records: generating the ten recovery
 * codes needs {@link RecoveryCodeHasher}, which design.md assigns to C3, so this class could not
 * exist complete before this cut's own {@link RecoveryCodeRepository} and hasher were built.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): {@link #execute} delegates
 * the whole body to {@link TransactionRunner#execute(SecurityContext, java.util.function.Supplier)},
 * the same pattern {@link VerifyTotpCode} already establishes.
 */
public final class EnrollTotpSecondFactor {

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String TABLE = "identity_mfa_totp_credential";
    private static final String COLUMN = "encrypted_secret";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String CREDENTIAL_ENTITY_TYPE = "identity.mfa_totp_credential";
    private static final String ACTION_ENROLLED = "identity.mfa.enrolled";
    private static final String OUTCOME_SUCCESS = "success";

    private final TransactionRunner transactionRunner;
    private final TotpCredentialRepository credentials;
    private final RecoveryCodeRepository recoveryCodes;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final ColumnEncryptionService encryption;
    private final AuditLogWriter auditLogWriter;
    private final SecureRandom secureRandom;

    public EnrollTotpSecondFactor(TransactionRunner transactionRunner,
            TotpCredentialRepository credentials, RecoveryCodeRepository recoveryCodes,
            RecoveryCodeHasher recoveryCodeHasher, ColumnEncryptionService encryption,
            AuditLogWriter auditLogWriter) {
        this(transactionRunner, credentials, recoveryCodes, recoveryCodeHasher, encryption,
                auditLogWriter, new SecureRandom());
    }

    /** Package-private: {@code secureRandom} is the same kind of testing seam {@code
     * BouncyCastleArgon2PasswordHasher} already establishes for its own {@code SecureRandom} —
     * here only for constructor symmetry, since no test in this cut needs a deterministic secret
     * or a deterministic set of ten codes. */
    EnrollTotpSecondFactor(TransactionRunner transactionRunner, TotpCredentialRepository credentials,
            RecoveryCodeRepository recoveryCodes, RecoveryCodeHasher recoveryCodeHasher,
            ColumnEncryptionService encryption, AuditLogWriter auditLogWriter,
            SecureRandom secureRandom) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.recoveryCodes = Objects.requireNonNull(recoveryCodes, "recoveryCodes");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher");
        this.encryption = Objects.requireNonNull(encryption, "encryption");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    public EnrollTotpSecondFactorResult execute(SecurityContext context, StaffAccountId accountId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(accountId, "accountId");

        InstitutionId institutionId = new InstitutionId(UUID.fromString(context.institutionId()));
        return transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, accountId, context.requestId()));
    }

    /** Package-private so a future {@code EnrollTotpSecondFactorTest} could exercise this exact
     * sequence with the ports doubled, mirroring {@link VerifyTotpCode}'s own split. */
    EnrollTotpSecondFactorResult runWithinTransaction(InstitutionId institutionId,
            StaffAccountId accountId, String requestId) {
        PlainTotpSecret secret = PlainTotpSecret.generate(secureRandom);
        String encryptedSecret = encryption.encryptForNewValue(TABLE, COLUMN, institutionId,
                rowIdOf(institutionId, accountId), secret.value());
        credentials.insert(institutionId, accountId, encryptedSecret);

        List<PlainRecoveryCode> plainCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            PlainRecoveryCode code = PlainRecoveryCode.generate(secureRandom);
            StoredRecoveryCodeHash hash = recoveryCodeHasher.hash(code);
            recoveryCodes.insert(institutionId, accountId, UUID.randomUUID(), hash);
            plainCodes.add(code);
        }

        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        auditLogWriter.append(enrolledEntry(institutionId, accountId, auditRequestId));

        return new EnrollTotpSecondFactorResult(secret, plainCodes);
    }

    /** {@code institutionId:accountId} (design.md, decision 5): the credential row's own composite
     * key, the same row-identifier shape {@link VerifyTotpCode} already uses. */
    private static String rowIdOf(InstitutionId institutionId, StaffAccountId accountId) {
        return institutionId.value() + ":" + accountId.value();
    }

    /** Design.md, §4.1 step 6: the single enrollment audit row covering both the credential and
     * the ten recovery codes — never one row per recovery code, per the escenario publicado
     * "un único evento identity.mfa.enrolled auditado". */
    private static AuditEntry enrolledEntry(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId) {
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF,
                accountId.value().toString(), null, null, requestId, null, ACTION_ENROLLED,
                CREDENTIAL_ENTITY_TYPE, accountId.value().toString(), OUTCOME_SUCCESS, null, null,
                null, null);
    }
}

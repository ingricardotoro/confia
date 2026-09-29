package com.confia.identity.application;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.RecoveryCodeRow;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Consumes one MFA recovery code, invalidating it without affecting the other unused codes of the
 * same account (column-encryption-and-mfa-totp design.md, §4.3; specs/identity/spec.md,
 * requirement "Diez códigos de recuperación de MFA...", "Usar un código lo invalida sin afectar a
 * los nueve restantes"). Steps 1-4 design.md §4.3 draws inside one transaction: read the unused
 * codes, match the presented one against each stored hash, conditionally mark it used, and audit
 * the outcome.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): {@link #execute} delegates
 * the whole body to {@link TransactionRunner#execute(SecurityContext, java.util.function.Supplier)},
 * the same pattern {@link VerifyTotpCode} and {@link EnrollTotpSecondFactor} already establish.
 *
 * <p><b>No {@code SELECT ... FOR UPDATE} before the match</b> (design.md, §4.3, "Por qué no hace
 * falta..."): step 3's own conditional {@code UPDATE ... WHERE id = ? AND used_at IS NULL} is
 * already the serialization point, the exact reasoning {@code JooqTotpCredentialRepository#acceptCounter}
 * already applies to its own predicate.
 */
public final class ConsumeRecoveryCode {

    /** specs/identity/spec.md, "Aviso al quedar con menos de tres códigos de recuperación de MFA
     * sin usar": the signal fires when the remaining unused count falls strictly BELOW three, not
     * when it reaches three (design.md, §4.3, decision D8; escenario "Quedar con exactamente tres
     * códigos no activa el aviso"). */
    private static final long LOW_RECOVERY_CODE_THRESHOLD = 3;

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String ENTITY_TYPE = "identity.mfa_recovery_code";
    private static final String ACTION_USED = "identity.mfa.recovery_code.used";
    private static final String ACTION_LOW = "identity.mfa.recovery_codes.low";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_DENIED = "denied";

    private final TransactionRunner transactionRunner;
    private final RecoveryCodeRepository recoveryCodes;
    private final RecoveryCodeHasher recoveryCodeHasher;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ConsumeRecoveryCode(TransactionRunner transactionRunner,
            RecoveryCodeRepository recoveryCodes, RecoveryCodeHasher recoveryCodeHasher,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.recoveryCodes = Objects.requireNonNull(recoveryCodes, "recoveryCodes");
        this.recoveryCodeHasher = Objects.requireNonNull(recoveryCodeHasher, "recoveryCodeHasher");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ConsumeRecoveryCodeDecision execute(SecurityContext context, StaffAccountId accountId,
            PlainRecoveryCode presented) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(presented, "presented");

        InstitutionId institutionId = new InstitutionId(UUID.fromString(context.institutionId()));
        return transactionRunner.execute(context,
                () -> runWithinTransaction(institutionId, accountId, context.requestId(), presented));
    }

    /** Package-private so a future {@code ConsumeRecoveryCodeTest} could exercise this exact
     * sequence with the ports doubled, mirroring {@link VerifyTotpCode}'s own split. */
    ConsumeRecoveryCodeDecision runWithinTransaction(InstitutionId institutionId,
            StaffAccountId accountId, String requestId, PlainRecoveryCode presented) {
        List<RecoveryCodeRow> unused = recoveryCodes.findUnusedByAccountId(institutionId, accountId);
        Optional<UUID> matchedId = unused.stream()
                .filter(row -> recoveryCodeHasher.matches(presented, row.hash()))
                .map(RecoveryCodeRow::id)
                .findFirst();

        Instant now = clock.instant();
        boolean accepted = matchedId.isPresent()
                && recoveryCodes.markUsed(institutionId, matchedId.get(), now);

        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        auditLogWriter.append(usedEntry(institutionId, accountId, auditRequestId, accepted));

        if (accepted) {
            long remaining = recoveryCodes.countUnusedByAccountId(institutionId, accountId);
            if (remaining < LOW_RECOVERY_CODE_THRESHOLD) {
                auditLogWriter.append(
                        lowCodesEntry(institutionId, accountId, auditRequestId, remaining));
            }
        }

        return new ConsumeRecoveryCodeDecision(accepted);
    }

    /** Design.md, §4.3 step 6: the recovery-code-used audit row, for both the accepted and the
     * denied outcome — {@code outcome} carries the distinction, the same "no hay identificador
     * ajeno a una cuenta real que proteger" reasoning {@link VerifyTotpCode} already applies to
     * using the account id, unhashed, as {@code entity_id}. */
    private static AuditEntry usedEntry(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, boolean accepted) {
        String outcome = accepted ? OUTCOME_SUCCESS : OUTCOME_DENIED;
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF,
                accountId.value().toString(), null, null, requestId, null, ACTION_USED,
                ENTITY_TYPE, accountId.value().toString(), outcome, null, null, null, null);
    }

    /** Design.md, §4.3 step 7: the low-recovery-codes signal, calculated and audited only — this
     * change delivers no email or notification adapter of any kind (specs/identity/spec.md,
     * "Ausencia de envío real del aviso..."). */
    private static AuditEntry lowCodesEntry(InstitutionId institutionId, StaffAccountId accountId,
            UUID requestId, long remainingUnusedCodes) {
        String afterValue = "{\"remainingUnusedCodes\":" + remainingUnusedCodes + "}";
        return new AuditEntry(institutionId.value(), accountId.value(), ACTOR_KIND_STAFF,
                accountId.value().toString(), null, null, requestId, null, ACTION_LOW,
                ENTITY_TYPE, accountId.value().toString(), OUTCOME_SUCCESS, null, afterValue, null,
                null);
    }
}

package com.confia.identity.application;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A password-reset request: it only schedules the issuance of a token, and never generates, stores
 * or returns one (password-recovery-token design.md decisions 6 and 9; specs/identity/spec.md, "La
 * solicitud de recuperación solo programa la emisión del token").
 *
 * <p>No Spring annotation and no bean yet, the same as {@link AuthenticateWithPassword}: {@link
 * #execute} delegates to {@link TransactionRunner#execute}, and {@link #runWithinTransaction} is the
 * package-private body for tests with doubles.
 *
 * <p><b>Uniform by construction.</b> An existing account, an address with no account and an
 * account at its hourly limit all get the same {@link RequestPasswordResetDecision} and exactly one
 * {@code identity.password_reset.requested} entry, whose entity is the keyed fingerprint of the
 * presented identifier. The real outcome is only in that entry's {@code after_value}, and an address
 * with no account never appears in clear. The hourly limit is decided later, at issuance, so it can
 * never show here.
 */
public final class RequestPasswordReset {

    private static final String ACTION_REQUESTED = "identity.password_reset.requested";
    private static final String STAFF_ACCOUNT_ENTITY_TYPE = "identity.staff_account";
    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String UNKNOWN_ACCOUNT_ACTOR_LABEL = "unknown-account";
    private static final String OUTCOME_SUCCESS = "success";
    private static final String AFTER_ISSUANCE_SCHEDULED = "{\"outcome\":\"issuance-scheduled\"}";
    private static final String AFTER_ACCOUNT_NOT_FOUND = "{\"outcome\":\"account-not-found\"}";

    private final TransactionRunner transactionRunner;
    private final LoginInstitutionProvider institutionProvider;
    private final StaffAccountRepository accounts;
    private final LoginIdentifierFingerprinter fingerprinter;
    private final PasswordResetIssuanceScheduler scheduler;
    private final AuditLogWriter auditLogWriter;

    public RequestPasswordReset(TransactionRunner transactionRunner,
            LoginInstitutionProvider institutionProvider, StaffAccountRepository accounts,
            LoginIdentifierFingerprinter fingerprinter, PasswordResetIssuanceScheduler scheduler,
            AuditLogWriter auditLogWriter) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.institutionProvider = Objects.requireNonNull(institutionProvider, "institutionProvider");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
    }

    public RequestPasswordResetDecision execute(SecurityContext context,
            RequestPasswordResetCommand command) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(command, "command");

        InstitutionId contextInstitutionId =
                new InstitutionId(UUID.fromString(context.institutionId()));
        if (!contextInstitutionId.equals(institutionProvider.loginInstitutionId())) {
            // Part 1's decision 11, literally: a wiring defect or an attack, never a uniform result.
            throw new IllegalStateException(
                    "password-reset request institution mismatch: the security context declares a "
                            + "different institution than the configured login institution");
        }

        return transactionRunner.execute(context,
                () -> runWithinTransaction(contextInstitutionId, context.requestId(), command));
    }

    RequestPasswordResetDecision runWithinTransaction(InstitutionId institutionId,
            String requestId, RequestPasswordResetCommand command) {
        LoginIdentifier identifier = LoginIdentifier.of(command.presentedIdentifier());
        IdentifierFingerprint fingerprint = fingerprinter.fingerprintOf(identifier);
        Optional<StaffAccount> account = accounts.findBy(institutionId, identifier);

        UUID actorId = null;
        String actorLabel = UNKNOWN_ACCOUNT_ACTOR_LABEL;
        String afterValue = AFTER_ACCOUNT_NOT_FOUND;
        if (account.isPresent()) {
            scheduler.schedule(institutionId, account.get().id());
            actorId = account.get().id().value();
            actorLabel = identifier.value();
            afterValue = AFTER_ISSUANCE_SCHEDULED;
        }

        UUID auditRequestId = requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
        auditLogWriter.append(new AuditEntry(institutionId.value(), actorId, ACTOR_KIND_STAFF,
                actorLabel, null, null, auditRequestId, null, ACTION_REQUESTED,
                STAFF_ACCOUNT_ENTITY_TYPE, fingerprint.value(), OUTCOME_SUCCESS, null, afterValue,
                null, null));
        return new RequestPasswordResetDecision();
    }
}

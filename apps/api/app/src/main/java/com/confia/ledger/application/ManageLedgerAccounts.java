package com.confia.ledger.application;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountUsage;
import com.confia.ledger.domain.AccountInUseException;
import com.confia.ledger.domain.AccountType;
import com.confia.ledger.domain.LedgerAccount;
import com.confia.ledger.domain.LedgerResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Loads, edits, lists and reads the chart of accounts of the institution of the actor (docs/cortes/ledger-chart-of-accounts.md).
 * The chart is DATA: nothing here knows an account code. The institution is the one of the actor, never a member of a request.
 * An account is never deleted; its code, type and student flag never change; only its name and active flag do, with the
 * version the editor read.
 *
 * <p>An edit locks the row ({@code SELECT ... FOR UPDATE}) and, when it turns the account off, asks every {@link
 * LedgerAccountUsage} whether a module still points at it, inside the same transaction: a module that points at an account
 * takes the row in share mode first, so the two take turns and neither wins silently. Every write leaves an entry in the audit
 * trail in the same transaction.
 */
public final class ManageLedgerAccounts {

    private final TransactionRunner transactionRunner;
    private final LedgerAccountRepository accounts;
    private final List<LedgerAccountUsage> usages;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageLedgerAccounts(TransactionRunner transactionRunner, LedgerAccountRepository accounts,
            List<LedgerAccountUsage> usages, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.usages = List.copyOf(Objects.requireNonNull(usages, "usages"));
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LedgerAccountView create(AuthenticatedActor actor, String requestId, String code, String name, String type,
            boolean holdsStudentBalances) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = AccountAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        AccountType accountType = AccountType.parse(type);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            LedgerAccount account = LedgerAccount.create(UUID.randomUUID(), code, name, accountType,
                    holdsStudentBalances, clock.instant());
            accounts.insert(institution, account);
            auditLogWriter.append(AccountAudit.created(actor, auditRequestId, account));
            return LedgerAccountView.of(account);
        });
    }

    /**
     * Replaces the name and the active flag of the account.
     *
     * @param version the version the editor read
     */
    public LedgerAccountView update(AuthenticatedActor actor, String requestId, UUID id, long version, String name,
            boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = AccountAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            LedgerAccount before = accounts.lockById(institution, id).orElseThrow(LedgerResourceNotFoundException::new);
            LedgerAccount after = before.edit(version, name, active, clock.instant());
            if (before.deactivates(active)
                    && usages.stream().anyMatch(usage -> usage.isReferenced(institution, id))) {
                throw new AccountInUseException();
            }
            accounts.update(institution, after, version);
            auditLogWriter.append(AccountAudit.updated(actor, auditRequestId, before, after));
            return LedgerAccountView.of(after);
        });
    }

    public Page<LedgerAccountView> list(AuthenticatedActor actor, String requestId, int page, int size, String sort,
            String type, Boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = AccountAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        AccountPageRequest request = AccountPageRequest.of(page, size, sort);
        AccountFilter filter = new AccountFilter(type == null || type.isBlank() ? null : AccountType.parse(type), active);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Page<LedgerAccount> found = accounts.list(institution, request, filter);
            return new Page<>(found.items().stream().map(LedgerAccountView::of).toList(), found.total(), found.page(),
                    found.size());
        });
    }
}

package com.confia.ledger.application;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.ledger.domain.EntrySide;
import com.confia.ledger.domain.LedgerEntry;
import com.confia.ledger.domain.TransactionKind;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The implementation of the public API of the ledger ({@link LedgerPostingApi} and {@link LedgerAccountDirectory}): it
 * translates the plain types of the boundary to the domain of the ledger and delegates to {@link LedgerPosting}, in the
 * transaction of the caller. It adds no rule of its own.
 */
public final class LedgerApi implements LedgerPostingApi, LedgerAccountDirectory,
        com.confia.ledger.application.api.LedgerStatementApi {

    private final LedgerPosting posting;
    private final LedgerRepository ledger;

    public LedgerApi(LedgerPosting posting, LedgerRepository ledger) {
        this.posting = Objects.requireNonNull(posting, "posting");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    @Override
    public Posted postCharge(Actor actor, ChargePosting charge) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(charge, "charge");
        List<LedgerEntry> entries = charge.lines().stream().map(line -> new LedgerEntry(line.accountId(),
                line.studentId(), line.side() == Side.DEBIT ? EntrySide.DEBIT : EntrySide.CREDIT, line.amount())).toList();
        LedgerPosting.Posted posted = posting.postWithinCurrentTransaction(toActor(actor),
                new LedgerPosting.PostCommand(TransactionKind.CHARGE, charge.effectiveOn(), charge.sourceModule(),
                        charge.sourceRefId(), null, charge.idempotencyKey(), entries));
        return new Posted(posted.stored().transaction().id(), posted.created());
    }

    @Override
    public List<LockedAccount> lockStudentAccounts(Actor actor, Collection<AccountKey> keys) {
        List<StudentCurrency> studentKeys = keys.stream()
                .map(key -> new StudentCurrency(key.studentId(), key.currency())).toList();
        return posting.lockStudentAccounts(toActor(actor), studentKeys).stream()
                .map(account -> new LockedAccount(new AccountKey(account.key().studentId(), account.key().currency()),
                        account.id(), account.underReview()))
                .toList();
    }

    @Override
    public Map<UUID, LedgerAccountRef> find(InstitutionId institutionId, Collection<UUID> accountIds) {
        Map<UUID, LedgerAccountRef> found = new HashMap<>();
        if (accountIds.isEmpty()) {
            return found;
        }
        for (AccountDetails account : ledger.findAccountDetails(institutionId, new java.util.HashSet<>(accountIds))) {
            found.put(account.id(), new LedgerAccountRef(account.id(), account.code(), account.name(), account.type(),
                    account.holdsStudentBalances(), account.active()));
        }
        return found;
    }

    @Override
    public Map<UUID, LedgerAccountRef> lockShare(InstitutionId institutionId, Collection<UUID> accountIds) {
        Map<UUID, LedgerAccountRef> found = new HashMap<>();
        if (accountIds.isEmpty()) {
            return found;
        }
        for (AccountDetails account : ledger.lockShareAccountDetails(institutionId, new java.util.HashSet<>(accountIds))) {
            found.put(account.id(), new LedgerAccountRef(account.id(), account.code(), account.name(), account.type(),
                    account.holdsStudentBalances(), account.active()));
        }
        return found;
    }

    @Override
    public List<com.confia.kernel.CurrencyCode> currenciesOf(InstitutionId institutionId, UUID studentId) {
        return ledger.studentCurrencies(institutionId, studentId);
    }

    @Override
    public Section statement(InstitutionId institutionId, UUID studentId, com.confia.kernel.CurrencyCode currency,
            java.time.LocalDate from, java.time.LocalDate to, int page, int size) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new com.confia.ledger.domain.InvalidLedgerEntryException("the statement starts after it ends");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new com.confia.ledger.domain.InvalidLedgerEntryException(
                    "the page is zero or more and the size is between 1 and " + MAX_PAGE_SIZE);
        }
        return ledger.statement(institutionId, studentId, currency, from, to, page, size);
    }

    private static LedgerActor toActor(Actor actor) {
        return new LedgerActor(actor.institutionId(), actor.actorId(), actor.actorKind(), actor.requestId());
    }
}

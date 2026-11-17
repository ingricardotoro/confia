package com.confia.ledger.application;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.domain.AccountRef;
import com.confia.ledger.domain.LedgerTransaction;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The port of the ledger. It inserts and reads: no operation updates or deletes a transaction or an entry (INV-04). The
 * one write that is not an insert is the lock of a student account, which is a read with {@code FOR UPDATE}.
 */
public interface LedgerRepository {

    Optional<StoredTransaction> findByIdempotencyKey(InstitutionId institution, String key);

    Optional<StoredTransaction> findById(InstitutionId institution, UUID id);

    boolean isReversed(InstitutionId institution, UUID transactionId);

    List<AccountRef> findAccounts(InstitutionId institution, Set<UUID> accountIds);

    /** The accounts of the chart with these identifiers, with their code, name and type; unknown identifiers are absent. */
    List<AccountDetails> findAccountDetails(InstitutionId institution, Set<UUID> accountIds);

    /**
     * The same accounts read with {@code SELECT ... FOR SHARE}, in the order of their identifiers (a fixed order, so two
     * callers never deadlock): another module that points at an account holds it so it cannot be deactivated before the
     * pointer is committed. Unknown identifiers are absent.
     */
    List<AccountDetails> lockShareAccountDetails(InstitutionId institution, Set<UUID> accountIds);

    /**
     * Opens the missing sub-accounts and locks every one with {@code SELECT ... FOR UPDATE}, one by one in the order of
     * {@code keys}, which the caller sorts: the fixed lock order that keeps two transactions from deadlocking.
     */
    List<StudentAccountRef> openAndLock(InstitutionId institution, List<StudentCurrency> keys);

    /** Inserts the header and its entries; {@code accounts} maps each student and currency to its sub-account. */
    void insert(InstitutionId institution, LedgerTransaction transaction, String payloadHash,
            Map<StudentCurrency, UUID> accounts);

    /** Debits minus credits of the sub-account up to {@code asOf}, optionally as it was known at {@code knownAt}. */
    Money balance(InstitutionId institution, UUID studentId, CurrencyCode currency, LocalDate asOf, Instant knownAt);

    Page<EntryView> listEntries(InstitutionId institution, UUID studentId, CurrencyCode currency, int page, int size);

    /** Convenience for tests and callers that hold a collection. */
    default List<StudentCurrency> sorted(Collection<StudentCurrency> keys) {
        return keys.stream().distinct().sorted().toList();
    }
}

package com.confia.ledger.application.api;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Output port, read-only, through which another module reads what the ledger holds so that it can check its own records
 * against it (docs/cortes/charges-void-and-integrity.md). Like {@link LedgerStatementApi} it opens no transaction and applies
 * no security context: it runs inside the transaction of the caller, and row-level security plus the institution given are
 * the two filters. It decides nothing: the comparison belongs to the module that owns the records.
 */
public interface LedgerIntegrityApi {

    /**
     * A transaction of one source module as the ledger holds it: its kind, the record of the source it refers to, the
     * transaction it reverses (reversals only) and the exact sums of its debits and of its credits. A transaction has one
     * currency (INV-06), so the two sums share it.
     */
    record SourcePosting(UUID transactionId, String kind, UUID sourceRefId, UUID reversesTransactionId, Money debitTotal,
            Money creditTotal) {

        public SourcePosting {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(debitTotal, "debitTotal");
            Objects.requireNonNull(creditTotal, "creditTotal");
        }
    }

    /** A transaction whose debits and credits differ. The database refuses them at commit; finding one means that failed. */
    record UnbalancedTransaction(UUID transactionId, Money debitTotal, Money creditTotal) {

        public UnbalancedTransaction {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(debitTotal, "debitTotal");
            Objects.requireNonNull(creditTotal, "creditTotal");
        }
    }

    /** What the transactions of a source module debited and credited to the student sub-accounts, in one currency. */
    record ReceivableMovement(CurrencyCode currency, Money debit, Money credit) {

        public ReceivableMovement {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(debit, "debit");
            Objects.requireNonNull(credit, "credit");
        }
    }

    /** Every transaction of the source module, whatever its kind. */
    List<SourcePosting> postingsOfSource(InstitutionId institutionId, String sourceModule);

    /** Every transaction of the institution whose debits differ from its credits; empty when the ledger is sound. */
    List<UnbalancedTransaction> unbalancedTransactions(InstitutionId institutionId);

    /**
     * The movement of the student sub-accounts caused by the transactions of the source module of the given kinds (the
     * names of the kinds of transaction, for example {@code CHARGE}), by currency.
     */
    List<ReceivableMovement> receivableMovementOfSource(InstitutionId institutionId, String sourceModule,
            Collection<String> kinds);
}

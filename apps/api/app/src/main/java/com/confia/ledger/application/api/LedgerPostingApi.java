package com.confia.ledger.application.api;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a source module asks of the ledger, inside the database transaction it already opened with the transactional
 * component (docs/cortes/charges-accrual.md, appendix B). No operation opens a transaction, applies a security context
 * or retries: a deadlock or a race propagates and the OUTER transaction runner repeats the whole unit of work of the caller.
 * Every one refuses to run without an active transaction. The ledger checks everything it always checks (balance, idempotency
 * key and content hash, accounts, student flag, minor unit) and the database repeats it at commit.
 */
public interface LedgerPostingApi {

    /** Which side of the transaction an entry is on. */
    enum Side { DEBIT, CREDIT }

    /** Who writes: the institution, the actor id and kind ({@code staff} or {@code system}) and the request. */
    record Actor(UUID institutionId, UUID actorId, String actorKind, UUID requestId) {

        public Actor {
            Objects.requireNonNull(institutionId, "institutionId");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(actorKind, "actorKind");
            Objects.requireNonNull(requestId, "requestId");
        }
    }

    /** One entry: an account of the chart, the student when the account holds student balances, a side and an amount. */
    record Line(UUID accountId, UUID studentId, Side side, Money amount) {

        public Line {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** A transaction of kind {@code CHARGE} to post: its date, its origin, its permanent key and its entries. */
    record ChargePosting(LocalDate effectiveOn, String sourceModule, UUID sourceRefId, String idempotencyKey,
            List<Line> lines) {

        public ChargePosting {
            Objects.requireNonNull(effectiveOn, "effectiveOn");
            Objects.requireNonNull(sourceModule, "sourceModule");
            Objects.requireNonNull(sourceRefId, "sourceRefId");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            lines = List.copyOf(lines);
        }
    }

    /** The transaction as posted and whether this call created it ({@code false} when the key was already posted). */
    record Posted(UUID transactionId, boolean created) {
    }

    /** The key of a student sub-account: the student and the currency. */
    record AccountKey(UUID studentId, CurrencyCode currency) {
    }

    /** A student sub-account that was opened or found and locked. */
    record LockedAccount(AccountKey key, UUID accountId, boolean underReview) {
    }

    /**
     * The reversal of a {@code CHARGE} transaction that the caller posted: the original is named by its identifier, and the
     * reversal is its exact mirror dated {@code effectiveOn} (not before the original). {@code callerModule} must be the
     * module of origin of the original (INV-41). The reason is mandatory.
     */
    record ChargeReversal(UUID originalTransactionId, String callerModule, LocalDate effectiveOn, String reason,
            String idempotencyKey) {

        public ChargeReversal {
            Objects.requireNonNull(originalTransactionId, "originalTransactionId");
            Objects.requireNonNull(callerModule, "callerModule");
            Objects.requireNonNull(effectiveOn, "effectiveOn");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        }
    }

    /** Why the ledger refused a reversal; the caller decides what that means for its own operation. */
    enum ReversalRefusal { NOT_FOUND, NOT_ALLOWED, ALREADY_REVERSED, ACCOUNT_UNDER_REVIEW }

    /**
     * The ledger refused a reversal. A plain exception on purpose: the domain errors of the ledger do not cross the module
     * boundary (ADR-0022), so the caller receives the closed reason and publishes its own error.
     */
    final class ReversalRefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient ReversalRefusal refusal;

        public ReversalRefusedException(ReversalRefusal refusal) {
            super("the ledger refused the reversal: " + refusal);
            this.refusal = Objects.requireNonNull(refusal, "refusal");
        }

        public ReversalRefusal refusal() {
            return refusal;
        }
    }

    Posted postCharge(Actor actor, ChargePosting posting);

    /**
     * Posts the exact mirror of a charge transaction inside the transaction of the caller. Same contract as {@link
     * #postCharge}: no transaction opened, no security context applied, no retry.
     *
     * @throws ReversalRefusedException when the ledger refuses (unknown transaction, not reversible, already reversed, or
     *     the student account is under review)
     */
    Posted reverseCharge(Actor actor, ChargeReversal reversal);

    /** Opens the missing sub-accounts and locks each one {@code FOR UPDATE} in the order student, currency. */
    List<LockedAccount> lockStudentAccounts(Actor actor, Collection<AccountKey> keys);
}

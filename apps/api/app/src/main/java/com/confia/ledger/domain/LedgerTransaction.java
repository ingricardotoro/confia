package com.confia.ledger.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A transaction of the double-entry ledger: at least two and at most one hundred entries of one currency whose debits
 * equal its credits (INV-01, INV-02, INV-06). It is built once and never changes (INV-04): a correction is a new
 * transaction, and the only one that undoes another is its exact {@link #mirror mirror} (INV-05, INV-38, INV-41). All the
 * arithmetic of the ledger is that of {@link Money}; the database repeats every rule at COMMIT.
 */
public final class LedgerTransaction {

    public static final int MAX_ENTRIES = 100;
    public static final int MIN_REASON = 20;
    public static final int MAX_REASON = 500;

    private static final Pattern SOURCE = Pattern.compile("[a-z][a-z0-9_]{1,39}");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_.-]{0,199}");

    private final UUID id;
    private final TransactionKind kind;
    private final LocalDate effectiveOn;
    private final String sourceModule;
    private final UUID sourceRefId;
    private final UUID reversesTransactionId;
    private final String reason;
    private final String idempotencyKey;
    private final UUID createdBy;
    private final List<LedgerEntry> entries;
    private final Money total;

    private LedgerTransaction(UUID id, TransactionKind kind, LocalDate effectiveOn, String sourceModule,
            UUID sourceRefId, UUID reversesTransactionId, String reason, String idempotencyKey, UUID createdBy,
            List<LedgerEntry> entries) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.effectiveOn = Objects.requireNonNull(effectiveOn, "effectiveOn");
        this.sourceModule = requireSource(sourceModule);
        this.sourceRefId = sourceRefId;
        this.reversesTransactionId = reversesTransactionId;
        this.reason = requireReason(kind, reason);
        this.idempotencyKey = requireKey(idempotencyKey);
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
        if ((kind == TransactionKind.REVERSAL) != (reversesTransactionId != null)) {
            throw new InvalidLedgerEntryException("only a reversal points at the transaction it reverses");
        }
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.total = requireBalanced(this.entries);
    }

    /** A new transaction of any kind but a reversal. */
    public static LedgerTransaction post(UUID id, TransactionKind kind, LocalDate effectiveOn, String sourceModule,
            UUID sourceRefId, String reason, String idempotencyKey, UUID createdBy, List<LedgerEntry> entries) {
        if (kind == TransactionKind.REVERSAL) {
            throw new ReversalNotAllowedException("a reversal is built from its original, never posted");
        }
        return new LedgerTransaction(id, kind, effectiveOn, sourceModule, sourceRefId, null, reason, idempotencyKey,
                createdBy, entries);
    }

    /** A transaction read back from the database; the same rules hold. */
    public static LedgerTransaction restore(UUID id, TransactionKind kind, LocalDate effectiveOn, String sourceModule,
            UUID sourceRefId, UUID reversesTransactionId, String reason, String idempotencyKey, UUID createdBy,
            List<LedgerEntry> entries) {
        return new LedgerTransaction(id, kind, effectiveOn, sourceModule, sourceRefId, reversesTransactionId, reason,
                idempotencyKey, createdBy, entries);
    }

    /**
     * The exact mirror of {@code original}: the same accounts, students, amounts and currency on the opposite side. Only
     * the module of origin reverses (INV-41), a reversal is never reversed, and it is not dated before the original
     * (INV-38). The reason is mandatory.
     */
    public static LedgerTransaction mirror(LedgerTransaction original, UUID id, String callerModule,
            LocalDate effectiveOn, String reason, String idempotencyKey, UUID createdBy) {
        Objects.requireNonNull(original, "original");
        if (original.kind == TransactionKind.REVERSAL) {
            throw new ReversalNotAllowedException("a reversal is never reversed");
        }
        if (!original.sourceModule.equals(callerModule)) {
            throw new ReversalNotAllowedException("only the module of origin reverses its transaction");
        }
        Objects.requireNonNull(effectiveOn, "effectiveOn");
        if (effectiveOn.isBefore(original.effectiveOn)) {
            throw new ReversalNotAllowedException("a reversal is not dated before its original");
        }
        List<LedgerEntry> mirrored = original.entries.stream().map(LedgerEntry::mirror).toList();
        return new LedgerTransaction(id, TransactionKind.REVERSAL, effectiveOn, original.sourceModule,
                original.sourceRefId, original.id, reason, idempotencyKey, createdBy, mirrored);
    }

    /** The content the idempotency key vouches for: the same text for the same facts, in any order of entries. */
    public String canonicalForm() {
        List<String> lines = new ArrayList<>(entries.stream().map(LedgerEntry::canonical).toList());
        lines.sort(Comparator.naturalOrder());
        return kind + "|" + effectiveOn + "|" + sourceModule + "|" + (sourceRefId == null ? "-" : sourceRefId) + "|"
                + (reversesTransactionId == null ? "-" : reversesTransactionId) + "|" + (reason == null ? "-" : reason)
                + "|" + String.join(";", lines);
    }

    private static Money requireBalanced(List<LedgerEntry> entries) {
        if (entries.size() < 2) {
            throw new UnbalancedTransactionException("a transaction has at least two entries");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new InvalidLedgerEntryException("a transaction has at most " + MAX_ENTRIES + " entries");
        }
        CurrencyCode currency = entries.get(0).amount().currency();
        Money debit = Money.zero(currency);
        Money credit = Money.zero(currency);
        for (LedgerEntry entry : entries) {
            // Money refuses a second currency: the ledger converts nothing (INV-06).
            if (entry.side() == EntrySide.DEBIT) {
                debit = debit.add(entry.amount());
            } else {
                credit = credit.add(entry.amount());
            }
        }
        if (debit.compareTo(credit) != 0) {
            throw new UnbalancedTransactionException("debit and credit of a transaction are equal");
        }
        return debit;
    }

    private static String requireSource(String module) {
        if (module == null || !SOURCE.matcher(module).matches()) {
            throw new InvalidLedgerEntryException("the source module is a lowercase identifier");
        }
        return module;
    }

    private static String requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new InvalidLedgerEntryException("the idempotency key is 1 to 200 characters of letters, digits and :_.-");
        }
        return key;
    }

    private static String requireReason(TransactionKind kind, String reason) {
        boolean required = kind == TransactionKind.REVERSAL || kind == TransactionKind.ADJUSTMENT;
        if (reason == null) {
            if (required) {
                throw new InvalidLedgerEntryException("a reversal and an adjustment carry a reason");
            }
            return null;
        }
        if (reason.strip().length() < MIN_REASON || reason.length() > MAX_REASON) {
            throw new InvalidLedgerEntryException(
                    "the reason has between " + MIN_REASON + " and " + MAX_REASON + " characters");
        }
        return reason;
    }

    public UUID id() {
        return id;
    }

    public TransactionKind kind() {
        return kind;
    }

    public LocalDate effectiveOn() {
        return effectiveOn;
    }

    public String sourceModule() {
        return sourceModule;
    }

    public UUID sourceRefId() {
        return sourceRefId;
    }

    public UUID reversesTransactionId() {
        return reversesTransactionId;
    }

    public String reason() {
        return reason;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public List<LedgerEntry> entries() {
        return entries;
    }

    /** The sum of the debits, which equals the sum of the credits. */
    public Money total() {
        return total;
    }

    public CurrencyCode currency() {
        return total.currency();
    }
}

package com.confia.charges.domain;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The nightly check of the charges against the ledger (docs/cortes/charges-void-and-integrity.md), as a pure function of
 * what was read: it decides, it reads nothing and writes nothing. It proves, for one institution,
 * <ol>
 *   <li>every transaction of the ledger balances;
 *   <li>every charge has exactly one {@code CHARGE} transaction, the one it points at, for its total; every voided charge has
 *       exactly one reversal, the one its void names, for the same total; a charge that is not voided has none; and no
 *       transaction of {@code charges} exists that no charge explains;
 *   <li>the charges that are not voided add up, in each currency, to what the transactions of {@code charges} moved on the
 *       receivable sub-accounts.
 * </ol>
 * All the arithmetic is that of {@link Money}; it compares with {@code Money.equals}, which is exact on the normalized amount
 * and the currency, never with {@code BigDecimal.equals}.
 */
public final class IntegrityCheck {

    /** The kind of a ledger transaction as the check sees it; any other kind of {@code charges} is ignored by (b) and (c). */
    public static final String CHARGE_KIND = "CHARGE";
    public static final String REVERSAL_KIND = "REVERSAL";

    private IntegrityCheck() {
    }

    /** A charge as the module holds it: its transaction, its total and, when voided, the reversal its void names. */
    public record ChargeFact(UUID chargeId, UUID ledgerTransactionId, Money total, UUID voidReversalTransactionId) {

        public ChargeFact {
            Objects.requireNonNull(chargeId, "chargeId");
            Objects.requireNonNull(ledgerTransactionId, "ledgerTransactionId");
            Objects.requireNonNull(total, "total");
        }

        public boolean voided() {
            return voidReversalTransactionId != null;
        }
    }

    /** A transaction of {@code charges} as the ledger holds it. */
    public record PostingFact(UUID transactionId, String kind, UUID sourceRefId, UUID reversesTransactionId,
            Money debitTotal, Money creditTotal) {

        public PostingFact {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(debitTotal, "debitTotal");
            Objects.requireNonNull(creditTotal, "creditTotal");
            if (debitTotal.currency() != creditTotal.currency()) {
                throw new IllegalArgumentException("a transaction has one currency");
            }
        }
    }

    /** A transaction of the ledger whose sides differ. */
    public record UnbalancedFact(UUID transactionId, Money debitTotal, Money creditTotal) {

        public UnbalancedFact {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(debitTotal, "debitTotal");
            Objects.requireNonNull(creditTotal, "creditTotal");
            if (debitTotal.currency() != creditTotal.currency()) {
                throw new IllegalArgumentException("a transaction has one currency");
            }
        }
    }

    /** What the {@code CHARGE} and {@code REVERSAL} transactions of {@code charges} moved on the receivable, in a currency. */
    public record MovementFact(CurrencyCode currency, Money debit, Money credit) {

        public MovementFact {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(debit, "debit");
            Objects.requireNonNull(credit, "credit");
            if (debit.currency() != currency || credit.currency() != currency) {
                throw new IllegalArgumentException("a movement is in the currency it names");
            }
        }
    }

    /** Everything one reading of the check needs. */
    public record Reading(List<ChargeFact> charges, List<PostingFact> postings, List<UnbalancedFact> unbalanced,
            List<MovementFact> movements) {

        public Reading {
            charges = List.copyOf(charges);
            postings = List.copyOf(postings);
            unbalanced = List.copyOf(unbalanced);
            movements = List.copyOf(movements);
        }
    }

    /** The result of one reading: how much was checked and what is wrong, in a stable order. */
    public record Outcome(int chargesChecked, int postingsChecked, List<IntegrityFinding> findings) {

        public Outcome {
            findings = List.copyOf(findings);
        }

        public boolean sound() {
            return findings.isEmpty();
        }

        /** How many findings there are of each kind, only the kinds that occur. */
        public Map<IntegrityKind, Integer> countsByKind() {
            Map<IntegrityKind, Integer> counts = new EnumMap<>(IntegrityKind.class);
            findings.forEach(finding -> counts.merge(finding.kind(), 1, Integer::sum));
            return counts;
        }
    }

    public static Outcome evaluate(Reading reading) {
        Objects.requireNonNull(reading, "reading");
        List<IntegrityFinding> findings = new ArrayList<>();
        unbalanced(reading, findings);
        perCharge(reading, findings);
        orphans(reading, findings);
        receivable(reading, findings);
        findings.sort(Comparator.comparing(IntegrityFinding::kind)
                .thenComparing(finding -> String.valueOf(finding.chargeId()))
                .thenComparing(finding -> String.valueOf(finding.ledgerTransactionId()))
                .thenComparing(finding -> String.valueOf(finding.currency())));
        return new Outcome(reading.charges().size(), reading.postings().size(), findings);
    }

    /**
     * The discrepancies that two readings agree on. A finding that only the first reading has was caused by an operation that
     * committed between its reads (the readings are not one snapshot), and it is dropped; one that persists is real. The
     * counts are those of the second reading, the later one.
     */
    public static Outcome confirmed(Outcome first, Outcome second) {
        Set<String> firstReading = first.findings().stream().map(IntegrityFinding::identity)
                .collect(Collectors.toSet());
        List<IntegrityFinding> kept = second.findings().stream()
                .filter(finding -> firstReading.contains(finding.identity())).toList();
        return new Outcome(second.chargesChecked(), second.postingsChecked(), kept);
    }

    // --- (a) every transaction balances ---

    private static void unbalanced(Reading reading, List<IntegrityFinding> findings) {
        for (UnbalancedFact fact : reading.unbalanced()) {
            findings.add(new IntegrityFinding(IntegrityKind.UNBALANCED_TRANSACTION, null, fact.transactionId(),
                    fact.debitTotal(), fact.creditTotal()));
        }
    }

    // --- (b) charges and their transactions ---

    private static void perCharge(Reading reading, List<IntegrityFinding> findings) {
        Map<UUID, List<PostingFact>> postingsByCharge = new HashMap<>();
        Map<UUID, List<PostingFact>> reversalsByOriginal = new HashMap<>();
        for (PostingFact posting : reading.postings()) {
            if (CHARGE_KIND.equals(posting.kind()) && posting.sourceRefId() != null) {
                postingsByCharge.computeIfAbsent(posting.sourceRefId(), id -> new ArrayList<>()).add(posting);
            } else if (REVERSAL_KIND.equals(posting.kind()) && posting.reversesTransactionId() != null) {
                reversalsByOriginal.computeIfAbsent(posting.reversesTransactionId(), id -> new ArrayList<>())
                        .add(posting);
            }
        }
        for (ChargeFact charge : reading.charges()) {
            chargePosting(charge, postingsByCharge.getOrDefault(charge.chargeId(), List.of()), findings);
            reversal(charge, reversalsByOriginal.getOrDefault(charge.ledgerTransactionId(), List.of()), findings);
        }
    }

    private static void chargePosting(ChargeFact charge, List<PostingFact> postings, List<IntegrityFinding> findings) {
        if (postings.isEmpty()) {
            findings.add(new IntegrityFinding(IntegrityKind.CHARGE_POSTING_MISSING, charge.chargeId(),
                    charge.ledgerTransactionId(), charge.total(), Money.zero(charge.total().currency())));
            return;
        }
        PostingFact own = postings.stream().filter(p -> p.transactionId().equals(charge.ledgerTransactionId()))
                .findFirst().orElse(null);
        if (own == null) {
            findings.add(new IntegrityFinding(IntegrityKind.CHARGE_POSTING_MISSING, charge.chargeId(),
                    charge.ledgerTransactionId(), charge.total(), Money.zero(charge.total().currency())));
        } else if (!sameCurrencyAndEqual(own.debitTotal(), charge.total())) {
            findings.add(totalMismatch(charge, own.transactionId(), own.debitTotal()));
        }
        for (PostingFact extra : postings) {
            if (extra != own) {
                findings.add(new IntegrityFinding(IntegrityKind.CHARGE_POSTING_DUPLICATE, charge.chargeId(),
                        extra.transactionId(), null, null));
            }
        }
    }

    private static void reversal(ChargeFact charge, List<PostingFact> reversals, List<IntegrityFinding> findings) {
        if (!charge.voided()) {
            for (PostingFact each : reversals) {
                findings.add(new IntegrityFinding(IntegrityKind.REVERSAL_WITHOUT_VOID, charge.chargeId(),
                        each.transactionId(), null, null));
            }
            return;
        }
        PostingFact named = reversals.stream()
                .filter(p -> p.transactionId().equals(charge.voidReversalTransactionId())).findFirst().orElse(null);
        if (named == null) {
            findings.add(new IntegrityFinding(IntegrityKind.VOID_REVERSAL_MISSING, charge.chargeId(),
                    charge.voidReversalTransactionId(), charge.total(), Money.zero(charge.total().currency())));
        } else if (!sameCurrencyAndEqual(named.creditTotal(), charge.total())) {
            findings.add(totalMismatch(charge, named.transactionId(), named.creditTotal()));
        } else if (!sameCurrencyAndEqual(named.debitTotal(), charge.total())) {
            findings.add(totalMismatch(charge, named.transactionId(), named.debitTotal()));
        }
        for (PostingFact extra : reversals) {
            if (extra != named) {
                findings.add(new IntegrityFinding(IntegrityKind.VOID_REVERSAL_DUPLICATE, charge.chargeId(),
                        extra.transactionId(), null, null));
            }
        }
    }

    /** A transaction of {@code charges} that no charge explains. */
    private static void orphans(Reading reading, List<IntegrityFinding> findings) {
        Set<UUID> chargeIds = reading.charges().stream().map(ChargeFact::chargeId).collect(Collectors.toSet());
        Set<UUID> chargeTransactions = reading.charges().stream().map(ChargeFact::ledgerTransactionId)
                .collect(Collectors.toSet());
        Set<UUID> reported = new HashSet<>();
        for (PostingFact posting : reading.postings()) {
            boolean orphan = switch (posting.kind()) {
                case CHARGE_KIND -> posting.sourceRefId() == null || !chargeIds.contains(posting.sourceRefId());
                case REVERSAL_KIND -> posting.reversesTransactionId() == null
                        || !chargeTransactions.contains(posting.reversesTransactionId());
                default -> false;
            };
            if (orphan && reported.add(posting.transactionId())) {
                findings.add(new IntegrityFinding(IntegrityKind.ORPHAN_POSTING, null, posting.transactionId(),
                        null, null));
            }
        }
    }

    // --- (c) the charges tie to the receivable ---

    private static void receivable(Reading reading, List<IntegrityFinding> findings) {
        Map<CurrencyCode, Money> expected = new EnumMap<>(CurrencyCode.class);
        for (ChargeFact charge : reading.charges()) {
            if (!charge.voided()) {
                expected.merge(charge.total().currency(), charge.total(), Money::add);
            }
        }
        Map<CurrencyCode, Money> actual = new EnumMap<>(CurrencyCode.class);
        for (MovementFact movement : reading.movements()) {
            actual.merge(movement.currency(), movement.debit().subtract(movement.credit()), Money::add);
        }
        Set<CurrencyCode> currencies = new HashSet<>(expected.keySet());
        currencies.addAll(actual.keySet());
        currencies.stream().sorted().forEach(currency -> {
            Money wanted = expected.getOrDefault(currency, Money.zero(currency));
            Money found = actual.getOrDefault(currency, Money.zero(currency));
            if (!wanted.equals(found)) {
                findings.add(new IntegrityFinding(IntegrityKind.RECEIVABLE_MISMATCH, null, null, wanted, found));
            }
        });
    }

    /** A total that is not the charge total; in another currency the two cannot be set side by side, so none is carried. */
    private static IntegrityFinding totalMismatch(ChargeFact charge, UUID transactionId, Money found) {
        if (found.currency() != charge.total().currency()) {
            return new IntegrityFinding(IntegrityKind.CHARGE_TOTAL_MISMATCH, charge.chargeId(), transactionId, null,
                    null);
        }
        return new IntegrityFinding(IntegrityKind.CHARGE_TOTAL_MISMATCH, charge.chargeId(), transactionId,
                charge.total(), found);
    }

    private static boolean sameCurrencyAndEqual(Money left, Money right) {
        return left.currency() == right.currency() && left.equals(right);
    }
}

package com.confia.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.CurrencyMismatchException;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rules of a ledger transaction in the domain (docs/cortes/ledger-core.md), with fictional accounts and amounts. */
class LedgerTransactionTest {

    private static final UUID RECEIVABLE = UUID.randomUUID();
    private static final UUID INCOME = UUID.randomUUID();
    private static final UUID STUDENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2027, 2, 1);
    private static final String REASON = "duplicated by the first run of the day";

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static LedgerEntry debit(String amount) {
        return new LedgerEntry(RECEIVABLE, STUDENT, EntrySide.DEBIT, hnl(amount));
    }

    private static LedgerEntry credit(String amount) {
        return new LedgerEntry(INCOME, null, EntrySide.CREDIT, hnl(amount));
    }

    private static LedgerTransaction charge(String key, List<LedgerEntry> entries) {
        return LedgerTransaction.post(UUID.randomUUID(), TransactionKind.CHARGE, DAY, "charges", null, null, key, ACTOR,
                entries);
    }

    private static LedgerTransaction accrual() {
        return charge("charges:accrual:1", List.of(debit("1500.00"), credit("1500.00")));
    }

    @Test
    void postsABalancedTransactionAndExposesItsTotal() {
        LedgerTransaction transaction = accrual();

        assertThat(transaction.total()).isEqualTo(hnl("1500.00"));
        assertThat(transaction.currency()).isEqualTo(CurrencyCode.HNL);
        assertThat(transaction.entries()).hasSize(2);
    }

    @Test
    void rejectsAnUnbalancedTransactionByOneCent() {
        assertThatThrownBy(() -> charge("k1", List.of(debit("1500.01"), credit("1500.00"))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsFewerThanTwoEntries() {
        assertThatThrownBy(() -> charge("k1", List.of())).isInstanceOf(UnbalancedTransactionException.class);
        assertThatThrownBy(() -> charge("k1", List.of(debit("10.00")))).isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void rejectsZeroAndNegativeEntries() {
        assertThatThrownBy(() -> debit("0.00")).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> debit("-5.00")).isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void rejectsAThirdDecimalInsteadOfRoundingIt() {
        assertThatThrownBy(() -> debit("10.005")).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> debit("10.0001")).isInstanceOf(InvalidLedgerEntryException.class);
        assertThat(debit("10.10").amount()).isEqualTo(hnl("10.10"));
    }

    @Test
    void rejectsMixedCurrencies() {
        LedgerEntry usd = new LedgerEntry(INCOME, null, EntrySide.CREDIT, Money.of("10.00", CurrencyCode.USD));

        assertThatThrownBy(() -> charge("k1", List.of(debit("10.00"), usd))).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void acceptsMoreThanTwoEntriesAndRejectsMoreThanOneHundred() {
        assertThat(charge("k1", List.of(debit("30.00"), credit("10.00"), credit("20.00"))).entries()).hasSize(3);

        List<LedgerEntry> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(debit("1.00"));
            many.add(credit("1.00"));
        }
        assertThatThrownBy(() -> charge("k1", many)).isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void acceptsTheLargestEntryAndRejectsTheBalancedPairThatOverflowsTheTotal() {
        assertThat(charge("k1", List.of(debit("9999999999.99"), credit("9999999999.99"))).total())
                .isEqualTo(hnl("9999999999.99"));
        assertThatThrownBy(() -> hnl("10000000000.00")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsABadKeySourceAndReason() {
        List<LedgerEntry> entries = List.of(debit("1.00"), credit("1.00"));

        assertThatThrownBy(() -> charge("has space", entries)).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> charge("", entries)).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> LedgerTransaction.post(UUID.randomUUID(), TransactionKind.CHARGE, DAY, "Charges", null,
                null, "k", ACTOR, entries)).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> LedgerTransaction.post(UUID.randomUUID(), TransactionKind.ADJUSTMENT, DAY, "charges",
                null, null, "k", ACTOR, entries)).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> LedgerTransaction.post(UUID.randomUUID(), TransactionKind.ADJUSTMENT, DAY, "charges",
                null, "too short", "k", ACTOR, entries)).isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void aReversalIsNeverPostedDirectly() {
        assertThatThrownBy(() -> LedgerTransaction.post(UUID.randomUUID(), TransactionKind.REVERSAL, DAY, "charges", null,
                REASON, "k", ACTOR, List.of(debit("1.00"), credit("1.00")))).isInstanceOf(ReversalNotAllowedException.class);
    }

    @Test
    void theMirrorHasTheSameAccountsAndAmountsOnTheOppositeSide() {
        LedgerTransaction original = accrual();

        LedgerTransaction reversal = LedgerTransaction.mirror(original, UUID.randomUUID(), "charges", DAY, REASON,
                "charges:void:1", ACTOR);

        assertThat(reversal.kind()).isEqualTo(TransactionKind.REVERSAL);
        assertThat(reversal.reversesTransactionId()).isEqualTo(original.id());
        assertThat(reversal.total()).isEqualTo(original.total());
        assertThat(reversal.entries()).containsExactly(
                new LedgerEntry(RECEIVABLE, STUDENT, EntrySide.CREDIT, hnl("1500.00")),
                new LedgerEntry(INCOME, null, EntrySide.DEBIT, hnl("1500.00")));
    }

    @Test
    void aReversalIsNeverReversed() {
        LedgerTransaction original = accrual();
        LedgerTransaction reversal = LedgerTransaction.mirror(original, UUID.randomUUID(), "charges", DAY, REASON, "r1",
                ACTOR);

        assertThatThrownBy(() -> LedgerTransaction.mirror(reversal, UUID.randomUUID(), "charges", DAY, REASON, "r2", ACTOR))
                .isInstanceOf(ReversalNotAllowedException.class);
    }

    @Test
    void aReversalIsNotDatedBeforeItsOriginalAndMayBeOnTheSameDay() {
        LedgerTransaction original = accrual();

        assertThatThrownBy(() -> LedgerTransaction.mirror(original, UUID.randomUUID(), "charges", DAY.minusDays(1), REASON,
                "r1", ACTOR)).isInstanceOf(ReversalNotAllowedException.class);
        assertThat(LedgerTransaction.mirror(original, UUID.randomUUID(), "charges", DAY, REASON, "r1", ACTOR))
                .isNotNull();
    }

    @Test
    void onlyTheModuleOfOriginReverses() {
        LedgerTransaction original = accrual();

        assertThatThrownBy(() -> LedgerTransaction.mirror(original, UUID.randomUUID(), "ledger", DAY, REASON, "r1", ACTOR))
                .isInstanceOf(ReversalNotAllowedException.class);
    }

    @Test
    void aReversalNeedsAReason() {
        LedgerTransaction original = accrual();

        assertThatThrownBy(() -> LedgerTransaction.mirror(original, UUID.randomUUID(), "charges", DAY, null, "r1", ACTOR))
                .isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void theCanonicalFormIgnoresTheOrderOfEntriesAndSeesAnyChange() {
        LedgerTransaction a = charge("k1", List.of(debit("1500.00"), credit("1500.00")));
        LedgerTransaction reordered = charge("k1", List.of(credit("1500.00"), debit("1500.00")));
        LedgerTransaction other = charge("k1", List.of(debit("1500.01"), credit("1500.01")));

        assertThat(a.canonicalForm()).isEqualTo(reordered.canonicalForm()).isNotEqualTo(other.canonicalForm());
    }

    @Test
    void anAccountThatHoldsStudentBalancesNeedsAStudentAndTheOtherOnesNeverHaveOne() {
        AccountRef receivable = new AccountRef(RECEIVABLE, true, true);
        AccountRef income = new AccountRef(INCOME, false, true);

        receivable.requireUsableBy(debit("1.00"), false);
        income.requireUsableBy(credit("1.00"), false);
        assertThatThrownBy(() -> receivable.requireUsableBy(new LedgerEntry(RECEIVABLE, null, EntrySide.DEBIT, hnl("1.00")),
                false)).isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> income.requireUsableBy(new LedgerEntry(INCOME, STUDENT, EntrySide.CREDIT, hnl("1.00")),
                false)).isInstanceOf(InvalidLedgerEntryException.class);
    }

    @Test
    void anInactiveAccountRefusesNewFactsButAcceptsTheMirrorOfAnOldOne() {
        AccountRef inactive = new AccountRef(INCOME, false, false);

        assertThatThrownBy(() -> inactive.requireUsableBy(credit("1.00"), false))
                .isInstanceOf(InvalidLedgerEntryException.class);
        inactive.requireUsableBy(credit("1.00"), true);
    }
}

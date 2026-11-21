package com.confia.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.CurrencyMismatchException;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * INV-17 and INV-18 on {@link Money}, without a database: the order in which the charges are served, that no charge receives more
 * than its pending, that the lines never exceed the payment, and the remainder that stays as a credit.
 */
class AllocationPlanTest {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static OpenCharge charge(String due, String period, String code, String total, String allocated) {
        return new OpenCharge(UUID.randomUUID(), UUID.randomUUID(), code, LocalDate.parse(period), LocalDate.parse(due),
                hnl(total), hnl(allocated));
    }

    private static OpenCharge charge(String due, String total) {
        return charge(due, due.substring(0, 8) + "01", "MENS", total, "0");
    }

    @Test
    void theAcceptanceExampleCoversTheOldestInFullAndPartsTheNext() {
        OpenCharge january = charge("2027-01-10", "1265.00");
        OpenCharge february = charge("2027-02-10", "1265.00");

        AllocationPlan plan = AllocationPlan.of(hnl("1500.00"), List.of(february, january));

        assertThat(plan.lines()).extracting(AllocationPlan.Line::chargeId)
                .containsExactly(january.chargeId(), february.chargeId());
        assertThat(plan.lines()).extracting(AllocationPlan.Line::amount).containsExactly(hnl("1265.00"), hnl("235.00"));
        assertThat(plan.unallocated().isZero()).isTrue();
    }

    @Test
    void anExactPaymentLeavesNothingPendingAndNoRemainder() {
        OpenCharge only = charge("2027-01-10", "1265.00");

        AllocationPlan plan = AllocationPlan.of(hnl("1265.00"), List.of(only));

        assertThat(plan.lines()).hasSize(1);
        assertThat(plan.lines().get(0).amount()).isEqualTo(hnl("1265.00"));
        assertThat(plan.unallocated().isZero()).isTrue();
    }

    @Test
    void aPaymentLargerThanEverythingPendingLeavesTheRemainderAsCredit() {
        AllocationPlan plan = AllocationPlan.of(hnl("3000.00"),
                List.of(charge("2027-01-10", "1265.00"), charge("2027-02-10", "1265.00")));

        assertThat(plan.lines()).hasSize(2);
        assertThat(plan.unallocated()).isEqualTo(hnl("470.00"));
        assertThat(plan.allocated(hnl("3000.00"))).isEqualTo(hnl("2530.00"));
    }

    @Test
    void aStudentWithNoOpenChargeGetsEverythingAsCredit() {
        AllocationPlan plan = AllocationPlan.of(hnl("100.00"), List.of());

        assertThat(plan.lines()).isEmpty();
        assertThat(plan.unallocated()).isEqualTo(hnl("100.00"));
    }

    @Test
    void aChargeThatWasPaidInFullIsSkippedAndAPartlyPaidOneReceivesOnlyItsPending() {
        OpenCharge paid = charge("2027-01-10", "2027-01-01", "MENS", "1265.00", "1265.00");
        OpenCharge partly = charge("2027-02-10", "2027-02-01", "MENS", "1265.00", "1000.00");
        OpenCharge next = charge("2027-03-10", "1265.00");

        AllocationPlan plan = AllocationPlan.of(hnl("300.00"), List.of(paid, partly, next));

        assertThat(plan.lines()).extracting(AllocationPlan.Line::chargeId).containsExactly(partly.chargeId(),
                next.chargeId());
        assertThat(plan.lines()).extracting(AllocationPlan.Line::amount).containsExactly(hnl("265.00"), hnl("35.00"));
    }

    @Test
    void aPartialPaymentLeavesTheChargeWithAPending() {
        OpenCharge only = charge("2027-01-10", "1265.00");

        AllocationPlan plan = AllocationPlan.of(hnl("0.01"), List.of(only));

        assertThat(plan.lines().get(0).amount()).isEqualTo(hnl("0.01"));
        assertThat(plan.unallocated().isZero()).isTrue();
    }

    @Test
    void theOrderIsDueDateThenPeriodThenConceptCodeThenIdentifier() {
        OpenCharge laterDue = charge("2027-03-10", "2027-01-01", "AAA", "10.00", "0");
        OpenCharge earlierPeriod = charge("2027-02-10", "2027-01-01", "ZZZ", "10.00", "0");
        OpenCharge laterPeriod = charge("2027-02-10", "2027-02-01", "AAA", "10.00", "0");
        OpenCharge codeA = charge("2027-02-10", "2027-02-01", "AAA", "10.00", "0");
        OpenCharge codeB = charge("2027-02-10", "2027-02-01", "BBB", "10.00", "0");
        OpenCharge noCode = charge("2027-02-10", "2027-02-01", null, "10.00", "0");
        List<OpenCharge> shuffled = new ArrayList<>(
                List.of(laterDue, noCode, codeB, laterPeriod, earlierPeriod, codeA));
        Collections.shuffle(shuffled);

        AllocationPlan plan = AllocationPlan.of(hnl("1000.00"), shuffled);

        List<UUID> sameKey = List.of(laterPeriod.chargeId(), codeA.chargeId()).stream().sorted().toList();
        assertThat(plan.lines()).extracting(AllocationPlan.Line::chargeId).containsExactly(earlierPeriod.chargeId(),
                sameKey.get(0), sameKey.get(1), codeB.chargeId(), noCode.chargeId(), laterDue.chargeId());
    }

    @Test
    void twoChargesWithTheSameDueDatePeriodAndCodeAreServedInAStableOrder() {
        OpenCharge first = charge("2027-02-10", "2027-02-01", "MENS", "10.00", "0");
        OpenCharge second = charge("2027-02-10", "2027-02-01", "MENS", "10.00", "0");

        List<UUID> forward = AllocationPlan.of(hnl("10.00"), List.of(first, second)).lines().stream()
                .map(AllocationPlan.Line::chargeId).toList();
        List<UUID> backward = AllocationPlan.of(hnl("10.00"), List.of(second, first)).lines().stream()
                .map(AllocationPlan.Line::chargeId).toList();

        assertThat(forward).isEqualTo(backward).hasSize(1);
    }

    @Test
    void zeroAndNegativeAmountsAreRefused() {
        assertThatThrownBy(() -> AllocationPlan.of(hnl("0.00"), List.of(charge("2027-01-10", "10.00"))))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> AllocationPlan.of(hnl("-1.00"), List.of(charge("2027-01-10", "10.00"))))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aChargeInAnotherCurrencyIsNeverMixed() {
        OpenCharge dollars = new OpenCharge(UUID.randomUUID(), UUID.randomUUID(), "MENS", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-01-10"), Money.of("10.00", CurrencyCode.USD), Money.zero(CurrencyCode.USD));

        assertThatThrownBy(() -> AllocationPlan.of(hnl("10.00"), List.of(dollars)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void anOverAllocatedChargeCountsAsHavingNothingPending() {
        OpenCharge odd = charge("2027-01-10", "2027-01-01", "MENS", "10.00", "12.00");

        assertThat(odd.pending().isZero()).isTrue();
        assertThat(AllocationPlan.of(hnl("5.00"), List.of(odd)).lines()).isEmpty();
    }

    @Property
    void noChargeReceivesMoreThanItsPendingAndTheLinesNeverExceedThePayment(
            @ForAll("cents") long paymentCents, @ForAll("charges") List<long[]> raw) {
        List<OpenCharge> open = new ArrayList<>();
        int day = 1;
        for (long[] pair : raw) {
            open.add(charge("2027-01-%02d".formatted(Math.min(28, day++)), "2027-01-01", "C" + day,
                    cents(pair[0]), cents(Math.min(pair[0], pair[1]) / 2)));
        }
        Money payment = Money.of(cents(paymentCents), CurrencyCode.HNL);

        AllocationPlan plan = AllocationPlan.of(payment, open);

        Money sum = Money.zero(CurrencyCode.HNL);
        for (AllocationPlan.Line line : plan.lines()) {
            OpenCharge served = open.stream().filter(c -> c.chargeId().equals(line.chargeId())).findFirst().orElseThrow();
            assertThat(line.amount().isPositive()).isTrue();
            assertThat(line.amount().compareTo(served.pending())).isLessThanOrEqualTo(0);
            sum = sum.add(line.amount());
        }
        assertThat(sum.compareTo(payment)).isLessThanOrEqualTo(0);
        assertThat(sum.add(plan.unallocated())).isEqualTo(payment);
        assertThat(plan.unallocated().isNegative()).isFalse();
    }

    @Provide
    Arbitrary<Long> cents() {
        return Arbitraries.longs().between(1, 5_000_000);
    }

    @Provide
    Arbitrary<List<long[]>> charges() {
        return Arbitraries.longs().between(1, 2_000_000).flatMap(total ->
                Arbitraries.longs().between(0, 2_000_000).map(paid -> new long[] {total, paid})).list().ofMaxSize(8);
    }

    private static String cents(long cents) {
        return new java.math.BigDecimal(cents).movePointLeft(2).toPlainString();
    }
}

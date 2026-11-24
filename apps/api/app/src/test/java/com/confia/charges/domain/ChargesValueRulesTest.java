package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.kernel.Percentage;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The value rules of the charges domain that the larger rule tests do not reach, with fictional amounts. */
class ChargesValueRulesTest {

    private static final LocalDate FIRST = LocalDate.of(2027, 2, 1);

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static TaxRateInfo rate(boolean active, TaxRateInfo.Validity... validities) {
        return new TaxRateInfo(UUID.randomUUID(), active, List.of(validities));
    }

    // --- ChargeTax ---

    @Test
    void aTaxableChargeRoundsTheTaxOnceHalfUpAndKeepsItsReferences() {
        UUID period = UUID.randomUUID();
        TaxRateInfo info = rate(true, new TaxRateInfo.Validity(period, Percentage.of("15"), FIRST, null));

        ChargeTax tax = ChargeTax.taxable(info, FIRST, hnl("33.33"));

        assertThat(tax.amount()).isEqualTo(hnl("5.00"));
        assertThat(tax.taxable()).isTrue();
        assertThat(tax.taxRatePeriodId()).isEqualTo(period);
        assertThat(tax.percent()).isEqualTo(Percentage.of("15"));
    }

    @Test
    void aMissingInactiveOrOutOfForceRateIsAnErrorOfTheChargeNeverARateOfZero() {
        Money base = hnl("100.00");
        TaxRateInfo.Validity ended = new TaxRateInfo.Validity(UUID.randomUUID(), Percentage.of("15"), FIRST,
                FIRST.plusDays(10));

        assertThatThrownBy(() -> ChargeTax.taxable(null, FIRST, base)).isInstanceOfSatisfying(AccrualFailure.class,
                e -> assertThat(e.errorCode()).isEqualTo(AccrualErrorCode.TAX_RATE_MISSING));
        assertThatThrownBy(() -> ChargeTax.taxable(rate(false, ended), FIRST, base)).isInstanceOfSatisfying(
                AccrualFailure.class, e -> assertThat(e.errorCode()).isEqualTo(AccrualErrorCode.TAX_RATE_INACTIVE));
        assertThatThrownBy(() -> ChargeTax.taxable(rate(true, ended), FIRST.plusDays(11), base))
                .isInstanceOfSatisfying(AccrualFailure.class,
                        e -> assertThat(e.errorCode()).isEqualTo(AccrualErrorCode.TAX_RATE_NOT_IN_FORCE));
        assertThatThrownBy(() -> ChargeTax.taxable(rate(true), FIRST, base)).isInstanceOf(AccrualFailure.class);
        assertThat(ChargeTax.taxable(rate(true, ended), FIRST.plusDays(10), base).amount()).isEqualTo(hnl("15.00"));
    }

    @Test
    void aChargeTaxIsAllOrNothing() {
        ChargeTax none = ChargeTax.none(CurrencyCode.HNL);
        assertThat(none.taxable()).isFalse();
        assertThat(none.amount()).isEqualTo(hnl("0.00"));
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new ChargeTax(hnl("1.00"), null, null, null))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new ChargeTax(hnl("1.00"), id, null, Percentage.of("15")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new ChargeTax(hnl("1.00"), id, id, null))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new ChargeTax(hnl("1.00"), null, id, null))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void noBenefitsMeansADiscountOfZero() {
        assertThat(BenefitsStep.NONE.offersOn(hnl("10.00"))).isEmpty();
    }

    // --- ChargeAmounts ---

    @Test
    void theAmountsOfAChargeKeepTheirArithmeticAndLimits() {
        ChargeAmounts amounts = ChargeAmounts.of(hnl("100.00"), hnl("10.00"), hnl("13.50"));
        assertThat(amounts.total()).isEqualTo(hnl("103.50"));

        assertThatThrownBy(() -> ChargeAmounts.of(hnl("0.00"), hnl("0.00"), hnl("0.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(hnl("100.00"), hnl("-1.00"), hnl("0.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(hnl("100.00"), hnl("0.00"), hnl("-1.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(hnl("100.00"), hnl("100.01"), hnl("0.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new ChargeAmounts(hnl("100.00"), hnl("0.00"), hnl("0.00"), hnl("99.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(hnl("100.005"), hnl("0.00"), hnl("0.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(hnl("100.00"), hnl("0.005"), hnl("0.00")))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargeAmounts.of(hnl("100.00"), hnl("0.00"), hnl("0.005")))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    // --- AccrualPeriod and AccrualCounters ---

    @Test
    void aPeriodStartsOnTheFirstAndNeitherEndsNorFallsDueBeforeItStarts() {
        assertThat(new AccrualPeriod(FIRST, FIRST.plusDays(27), FIRST.plusDays(4), "2027-02").label())
                .isEqualTo("2027-02");
        assertThatThrownBy(() -> new AccrualPeriod(FIRST.plusDays(1), FIRST.plusDays(27), FIRST.plusDays(4), "x"))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AccrualPeriod(FIRST, FIRST.minusDays(1), FIRST.plusDays(4), "x"))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AccrualPeriod(FIRST, FIRST.plusDays(27), FIRST.minusDays(1), "x"))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void theCountersOfARunAreNeverNegative() {
        assertThat(AccrualCounters.ZERO.chargesCreated()).isZero();
        assertThat(new AccrualCounters(4, 3, 1, 0).studentsEvaluated()).isEqualTo(4);
        assertThatThrownBy(() -> new AccrualCounters(-1, 0, 0, 0)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AccrualCounters(0, -1, 0, 0)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AccrualCounters(0, 0, -1, 0)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new AccrualCounters(0, 0, 0, -1)).isInstanceOf(InvalidChargesValueException.class);
    }

    // --- RunStatus ---

    @Test
    void aRunMovesForwardOnlyAndTerminalStatusesAreFinal() {
        assertThat(RunStatus.QUEUED.canMoveTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.QUEUED.canMoveTo(RunStatus.REJECTED)).isTrue();
        assertThat(RunStatus.QUEUED.canMoveTo(RunStatus.FAILED)).isTrue();
        assertThat(RunStatus.QUEUED.canMoveTo(RunStatus.COMPLETED)).isFalse();
        assertThat(RunStatus.RUNNING.canMoveTo(RunStatus.COMPLETED)).isTrue();
        assertThat(RunStatus.RUNNING.canMoveTo(RunStatus.COMPLETED_WITH_ERRORS)).isTrue();
        assertThat(RunStatus.RUNNING.canMoveTo(RunStatus.REJECTED)).isTrue();
        assertThat(RunStatus.RUNNING.canMoveTo(RunStatus.FAILED)).isTrue();
        assertThat(RunStatus.RUNNING.canMoveTo(RunStatus.QUEUED)).isFalse();
        for (RunStatus terminal : List.of(RunStatus.COMPLETED, RunStatus.COMPLETED_WITH_ERRORS, RunStatus.REJECTED,
                RunStatus.FAILED)) {
            assertThat(terminal.isTerminal()).isTrue();
            for (RunStatus next : RunStatus.values()) {
                assertThat(terminal.canMoveTo(next)).isFalse();
            }
        }
        assertThat(RunStatus.QUEUED.isTerminal()).isFalse();
        assertThat(RunStatus.RUNNING.isTerminal()).isFalse();
    }

    // --- PostingPurpose and PostingAccountKey ---

    @Test
    void eachPurposeAcceptsOnlyTheAccountOfItsTypeAndStudentFlag() {
        assertThat(PostingPurpose.RECEIVABLE.accountType()).isEqualTo("ASSET");
        assertThat(PostingPurpose.RECEIVABLE.holdsStudentBalances()).isTrue();
        assertThat(PostingPurpose.REVENUE.holdsStudentBalances()).isFalse();
        assertThat(PostingPurpose.RECEIVABLE.accepts("ASSET", true)).isTrue();
        assertThat(PostingPurpose.RECEIVABLE.accepts("ASSET", false)).isFalse();
        assertThat(PostingPurpose.REVENUE.accepts("ASSET", false)).isFalse();
        assertThat(PostingPurpose.TAX_PAYABLE.accepts("LIABILITY", false)).isTrue();
    }

    @Test
    void theBenefitsPurposeAcceptsAContraRevenueOrExpenseAccountThatHoldsNoStudentBalances() {
        // Assumption S4 of docs/cortes/scholarships-awards.md, pending the accountant.
        assertThat(PostingPurpose.BENEFITS.holdsStudentBalances()).isFalse();
        assertThat(PostingPurpose.BENEFITS.accepts("EXPENSE", false)).isTrue();
        assertThat(PostingPurpose.BENEFITS.accepts("INCOME", false)).isTrue();
        assertThat(PostingPurpose.BENEFITS.accepts("ASSET", false)).isFalse();
        assertThat(PostingPurpose.BENEFITS.accepts("LIABILITY", false)).isFalse();
        assertThat(PostingPurpose.BENEFITS.accepts("EXPENSE", true)).isFalse();
        assertThat(PostingPurpose.parse("BENEFITS")).isEqualTo(PostingPurpose.BENEFITS);
    }

    @Test
    void aPurposeIsParsedWithItsExactNameOrRefused() {
        assertThat(PostingPurpose.parse("TAX_PAYABLE")).isEqualTo(PostingPurpose.TAX_PAYABLE);
        assertThatThrownBy(() -> PostingPurpose.parse("tax_payable")).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> PostingPurpose.parse(null)).isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void onlyARevenueKeyMayNameAKnownCategory() {
        assertThat(PostingAccountKey.of("REVENUE", "TUITION").conceptCategory()).isEqualTo("TUITION");
        assertThatThrownBy(() -> new PostingAccountKey(null, null)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new PostingAccountKey(PostingPurpose.RECEIVABLE, "TUITION"))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new PostingAccountKey(PostingPurpose.REVENUE, "UNKNOWN"))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void theErrorsKeepTheirPublishedCodes() {
        assertThat(new AccrualRunInProgressException().code()).isEqualTo("accrual-run-in-progress");
        assertThat(new IdempotencyKeyRequiredException().code()).isEqualTo("idempotency-key-missing");
    }
}

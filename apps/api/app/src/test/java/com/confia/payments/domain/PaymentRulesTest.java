package com.confia.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Rules of the payment value objects that the scenario tests do not reach: coverage, amounts, vocabularies. */
class PaymentRulesTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2027-02-10");

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static Payment payment(PaymentStatus status, String amount) {
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), null, PaymentMethod.CASH, status, hnl(amount), TODAY,
                null, null, null, null, null, UUID.randomUUID(), NOW, "key", "h".repeat(64));
    }

    private static PaymentAllocation allocation(String amount) {
        return new PaymentAllocation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), hnl(amount), NOW);
    }

    // PaymentCoverage

    @Test
    void aConfirmedPaymentIsCoveredBySumOfItsAllocationsAndTheRestIsCredit() {
        PaymentCoverage coverage = PaymentCoverage.of(payment(PaymentStatus.CONFIRMED, "100.00"),
                List.of(allocation("30.00"), allocation("25.50")));

        assertThat(coverage.allocated()).isEqualTo(hnl("55.50"));
        assertThat(coverage.unallocated()).isEqualTo(hnl("44.50"));
    }

    @Test
    void aConfirmedPaymentExactlyAllocatedLeavesNoCredit() {
        PaymentCoverage coverage = PaymentCoverage.of(payment(PaymentStatus.CONFIRMED, "100.00"),
                List.of(allocation("100.00")));

        assertThat(coverage.unallocated().isZero()).isTrue();
    }

    @Test
    void aConfirmedPaymentWithoutAllocationsIsAllCredit() {
        PaymentCoverage coverage = PaymentCoverage.of(payment(PaymentStatus.CONFIRMED, "100.00"), List.of());

        assertThat(coverage.allocated().isZero()).isTrue();
        assertThat(coverage.unallocated()).isEqualTo(hnl("100.00"));
    }

    @Test
    void overAllocationNeverYieldsANegativeCredit() {
        PaymentCoverage coverage = PaymentCoverage.of(payment(PaymentStatus.CONFIRMED, "100.00"),
                List.of(allocation("70.00"), allocation("40.00")));

        assertThat(coverage.allocated()).isEqualTo(hnl("110.00"));
        assertThat(coverage.unallocated().isZero()).isTrue();
    }

    @Test
    void aPaymentThatIsNotConfirmedCoversAndLeavesNothing() {
        for (PaymentStatus status : List.of(PaymentStatus.DECLARED, PaymentStatus.REJECTED)) {
            PaymentCoverage coverage = PaymentCoverage.of(payment(status, "100.00"), List.of(allocation("10.00")));

            assertThat(coverage.allocated().isZero()).isTrue();
            assertThat(coverage.unallocated().isZero()).isTrue();
        }
    }

    @Test
    void coverageRefusesMissingParts() {
        assertThatThrownBy(() -> new PaymentCoverage(null, hnl("1.00"))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentCoverage(hnl("1.00"), null)).isInstanceOf(NullPointerException.class);
    }

    // PaymentAllocation

    @Test
    void anAllocationIsAlwaysOfAPositiveAmount() {
        assertThat(allocation("0.01").amount()).isEqualTo(hnl("0.01"));
        assertThatThrownBy(() -> allocation("0.00")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> allocation("-1.00")).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void anAllocationRefusesEveryMissingPart() {
        UUID id = UUID.randomUUID();
        Money one = hnl("1.00");
        assertThatThrownBy(() -> new PaymentAllocation(null, id, id, one, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentAllocation(id, null, id, one, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentAllocation(id, id, null, one, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentAllocation(id, id, id, null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentAllocation(id, id, id, one, null)).isInstanceOf(NullPointerException.class);
    }

    // PaymentAmounts

    @Test
    void parsedAmountsAreLempirasWithAtMostTwoDecimals() {
        assertThat(PaymentAmounts.parse("0.01")).isEqualTo(hnl("0.01"));
        assertThat(PaymentAmounts.parse("1234.5")).isEqualTo(hnl("1234.50"));
        assertThatThrownBy(() -> PaymentAmounts.parse("10.005")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentAmounts.parse("0")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentAmounts.parse("-5.00")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentAmounts.parse("abc")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentAmounts.parse(null)).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aValidatedAmountInAnotherCurrencyIsRefused() {
        assertThatThrownBy(() -> PaymentAmounts.requireValid(Money.of("10.00", CurrencyCode.USD)))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(PaymentAmounts.requireValid(hnl("10.00"))).isEqualTo(hnl("10.00"));
    }

    // Vocabularies

    @Test
    void theDecisionKindsMapToTheirCodeAndTheStatusTheyProduce() {
        assertThat(DecisionKind.ofCode("confirmed")).isEqualTo(DecisionKind.CONFIRMED);
        assertThat(DecisionKind.ofCode("rejected")).isEqualTo(DecisionKind.REJECTED);
        assertThat(DecisionKind.CONFIRMED.code()).isEqualTo("confirmed");
        assertThat(DecisionKind.REJECTED.code()).isEqualTo("rejected");
        assertThat(DecisionKind.CONFIRMED.resultingStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(DecisionKind.REJECTED.resultingStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThatThrownBy(() -> DecisionKind.ofCode("declared")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionKind.ofCode(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStatusesAreFoundByTheirExactCodeOnly() {
        assertThat(PaymentStatus.ofCode("declared")).contains(PaymentStatus.DECLARED);
        assertThat(PaymentStatus.ofCode("confirmed")).contains(PaymentStatus.CONFIRMED);
        assertThat(PaymentStatus.ofCode("rejected")).contains(PaymentStatus.REJECTED);
        assertThat(PaymentStatus.ofCode("CONFIRMED")).isEmpty();
        assertThat(PaymentStatus.ofCode(null)).isEmpty();
        assertThat(PaymentStatus.DECLARED.code()).isEqualTo("declared");
    }

    @Test
    void onlyCashNeedsACashSessionAndEachMethodPostsToItsOwnPurpose() {
        assertThat(PaymentMethod.CASH.requiresCashSession()).isTrue();
        assertThat(PaymentMethod.BANK_TRANSFER.requiresCashSession()).isFalse();
        assertThat(PaymentMethod.CASH.purpose()).isEqualTo(PostingPurpose.CASH);
        assertThat(PaymentMethod.BANK_TRANSFER.purpose()).isEqualTo(PostingPurpose.BANK);
        assertThat(PaymentMethod.ofName("CASH")).contains(PaymentMethod.CASH);
        assertThat(PaymentMethod.ofName("cash")).isEmpty();
        assertThat(PaymentMethod.ofName(null)).isEmpty();
    }

    @Test
    void aPostingPurposeAcceptsOnlyAnAssetThatHoldsNoStudentBalance() {
        assertThat(PostingPurpose.CASH.accepts("ASSET", false)).isTrue();
        assertThat(PostingPurpose.BANK.accepts("ASSET", true)).isFalse();
        assertThat(PostingPurpose.BANK.accepts("LIABILITY", false)).isFalse();
        assertThat(PostingPurpose.BANK.accepts(null, false)).isFalse();
        assertThat(PostingPurpose.ofName("BANK")).contains(PostingPurpose.BANK);
        assertThat(PostingPurpose.ofName("bank")).isEmpty();
        assertThat(PostingPurpose.parse("CASH")).isEqualTo(PostingPurpose.CASH);
        assertThatThrownBy(() -> PostingPurpose.parse("OTHER")).isInstanceOf(InvalidPaymentsValueException.class);
    }

    // PostingAccounts

    @Test
    void theReceivableAccountMustBeAnAssetThatHoldsStudentBalances() {
        assertThat(PostingAccounts.acceptsReceivable("ASSET", true)).isTrue();
        assertThat(PostingAccounts.acceptsReceivable("ASSET", false)).isFalse();
        assertThat(PostingAccounts.acceptsReceivable("INCOME", true)).isFalse();
        assertThat(PostingAccounts.acceptsReceivable(null, true)).isFalse();
    }

    @Test
    void aMapIsCompleteOnlyWithTheThreeAccounts() {
        UUID a = UUID.randomUUID();
        assertThat(new PostingAccounts(a, a, a).complete()).isTrue();
        assertThat(new PostingAccounts(null, a, a).complete()).isFalse();
        assertThat(new PostingAccounts(a, null, a).complete()).isFalse();
        assertThat(new PostingAccounts(a, a, null).complete()).isFalse();
    }

    @Test
    void theDebitAccountFollowsTheMethodAndTheReceivableIsTheCredit() {
        UUID cash = UUID.randomUUID();
        UUID bank = UUID.randomUUID();
        UUID receivable = UUID.randomUUID();
        PostingAccounts accounts = new PostingAccounts(cash, bank, receivable);

        assertThat(accounts.debitFor(PaymentMethod.CASH)).isEqualTo(cash);
        assertThat(accounts.debitFor(PaymentMethod.BANK_TRANSFER)).isEqualTo(bank);
        assertThat(accounts.receivableAccount()).isEqualTo(receivable);
    }

    @Test
    void anIncompleteMapRefusesEveryLookup() {
        PostingAccounts accounts = new PostingAccounts(UUID.randomUUID(), null, UUID.randomUUID());

        assertThatThrownBy(() -> accounts.debitFor(PaymentMethod.CASH))
                .isInstanceOf(PostingAccountsIncompleteException.class);
        assertThatThrownBy(accounts::receivableAccount).isInstanceOf(PostingAccountsIncompleteException.class);
    }

    // Exceptions: stable codes the API maps

    @Test
    void everyDomainRefusalCarriesItsStableCode() {
        assertThat(new CashSessionNotOpenException().code()).isEqualTo("cash-session-not-open");
        assertThat(new DuplicateTransferReferenceException().code()).isEqualTo("duplicate-transfer-reference");
        assertThat(new IdempotencyKeyRequiredException().code()).isEqualTo("idempotency-key-missing");
        assertThat(new InactiveReferenceException().code()).isEqualTo("inactive-reference");
        assertThat(new PaymentNotDeclaredException().code()).isEqualTo("payment-not-declared");
        assertThat(new PostingAccountInvalidException().code()).isEqualTo("posting-account-invalid");
        assertThat(new PostingAccountsIncompleteException().code()).isEqualTo("posting-accounts-incomplete");
        assertThat(new ResourceNotFoundException().code()).isEqualTo("resource-not-found");
        assertThat(new SelfApprovalForbiddenException().code()).isEqualTo("self-approval-forbidden");
        assertThat(new StaleVersionException().code()).isEqualTo("stale-version");
    }

    @Test
    void theValueRefusalKeepsTheFieldAndTheReasonItWasGiven() {
        InvalidPaymentsValueException bare = new InvalidPaymentsValueException();
        InvalidPaymentsValueException detailed = new InvalidPaymentsValueException("amount", "too-small");

        assertThat(bare.code()).isEqualTo("validation-failed");
        assertThat(bare.field()).isNull();
        assertThat(bare.reason()).isNull();
        assertThat(detailed.field()).isEqualTo("amount");
        assertThat(detailed.reason()).isEqualTo("too-small");
    }
}

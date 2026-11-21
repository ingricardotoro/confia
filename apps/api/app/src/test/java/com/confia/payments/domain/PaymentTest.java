package com.confia.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The states of a payment and the segregation of duties (docs/02 section 6.2), without a database. */
class PaymentTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2027-02-10");
    private static final String HASH = "a".repeat(64);

    private final UUID cashier = UUID.randomUUID();
    private final UUID administrator = UUID.randomUUID();
    private final UUID student = UUID.randomUUID();

    private PaymentRequest request(String method) {
        boolean transfer = method.equals("BANK_TRANSFER");
        return PaymentRequest.of(student, null, method, "100.00", TODAY, TODAY, transfer ? "BAC-123456" : null,
                transfer ? "BAC" : null, null);
    }

    private Payment declared() {
        return Payment.declaredTransfer(UUID.randomUUID(), request("BANK_TRANSFER"), cashier, NOW, "key", HASH);
    }

    @Test
    void cashIsBornConfirmedWithItsSessionMovementAndPosting() {
        Payment payment = Payment.cash(UUID.randomUUID(), request("CASH"), cashier, NOW, "key", HASH,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(payment.status()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(payment.method()).isEqualTo(PaymentMethod.CASH);
        assertThat(payment.cashSessionId()).isNotNull();
        assertThat(payment.cashMovementId()).isNotNull();
        assertThat(payment.ledgerTransactionId()).isNotNull();
        assertThat(payment.amount()).isEqualTo(Money.of("100.00", CurrencyCode.HNL));
    }

    @Test
    void cashWithoutItsSessionMovementOrPostingCannotExist() {
        assertThatThrownBy(() -> Payment.cash(UUID.randomUUID(), request("CASH"), cashier, NOW, "key", HASH, null,
                UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.cash(UUID.randomUUID(), request("CASH"), cashier, NOW, "key", HASH,
                UUID.randomUUID(), null, UUID.randomUUID())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Payment.cash(UUID.randomUUID(), request("CASH"), cashier, NOW, "key", HASH,
                UUID.randomUUID(), UUID.randomUUID(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theFactoryOfEachMethodRefusesTheOtherMethod() {
        assertThatThrownBy(() -> Payment.cash(UUID.randomUUID(), request("BANK_TRANSFER"), cashier, NOW, "key", HASH,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> Payment.declaredTransfer(UUID.randomUUID(), request("CASH"), cashier, NOW, "key", HASH))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aTransferIsBornDeclaredAndTouchesNeitherCashNorLedger() {
        Payment payment = declared();

        assertThat(payment.status()).isEqualTo(PaymentStatus.DECLARED);
        assertThat(payment.isDeclared()).isTrue();
        assertThat(payment.cashSessionId()).isNull();
        assertThat(payment.cashMovementId()).isNull();
        assertThat(payment.ledgerTransactionId()).isNull();
    }

    @Test
    void anotherPersonConfirmsATransferAndTheStatusFollowsTheDecision() {
        Payment payment = declared();
        UUID posting = UUID.randomUUID();

        PaymentDecision decision = payment.confirm(UUID.randomUUID(), administrator, posting, NOW, "k2", HASH);

        assertThat(decision.kind()).isEqualTo(DecisionKind.CONFIRMED);
        assertThat(decision.ledgerTransactionId()).isEqualTo(posting);
        assertThat(decision.reason()).isNull();
        assertThat(payment.decided(decision).status()).isEqualTo(PaymentStatus.CONFIRMED);
    }

    @Test
    void aRejectionKeepsItsReasonLeavesNoPostingAndReversesTheStatus() {
        Payment payment = declared();

        PaymentDecision decision = payment.reject(UUID.randomUUID(), administrator,
                RejectionReason.of("El banco no registra esta transferencia"), NOW, "k2", HASH);

        assertThat(decision.kind()).isEqualTo(DecisionKind.REJECTED);
        assertThat(decision.ledgerTransactionId()).isNull();
        assertThat(decision.reason()).startsWith("El banco");
        assertThat(payment.decided(decision).status()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(decision.toString()).doesNotContain("banco");
    }

    @Test
    void whoRegisteredTheTransferNeitherConfirmsNorRejectsIt() {
        Payment payment = declared();

        assertThatThrownBy(() -> payment.confirm(UUID.randomUUID(), cashier, UUID.randomUUID(), NOW, "k2", HASH))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> payment.reject(UUID.randomUUID(), cashier,
                RejectionReason.of("El banco no registra esta transferencia"), NOW, "k2", HASH))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }

    @Test
    void aDecidedPaymentIsNotDecidedAgain() {
        Payment payment = declared();
        Payment confirmed = payment.decided(payment.confirm(UUID.randomUUID(), administrator, UUID.randomUUID(), NOW,
                "k2", HASH));
        Payment rejected = payment.decided(payment.reject(UUID.randomUUID(), administrator,
                RejectionReason.of("El banco no registra esta transferencia"), NOW, "k3", HASH));

        for (Payment settled : new Payment[] {confirmed, rejected}) {
            assertThatThrownBy(() -> settled.confirm(UUID.randomUUID(), administrator, UUID.randomUUID(), NOW, "k4", HASH))
                    .isInstanceOf(PaymentNotDeclaredException.class);
            assertThatThrownBy(() -> settled.reject(UUID.randomUUID(), administrator,
                    RejectionReason.of("El banco no registra esta transferencia"), NOW, "k5", HASH))
                    .isInstanceOf(PaymentNotDeclaredException.class);
        }
    }

    @Test
    void cashIsNeverDecided() {
        Payment cash = Payment.cash(UUID.randomUUID(), request("CASH"), cashier, NOW, "key", HASH, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> cash.confirm(UUID.randomUUID(), administrator, UUID.randomUUID(), NOW, "k", HASH))
                .isInstanceOf(PaymentNotDeclaredException.class);
    }

    @Test
    void aDecisionNeedsItsPostingExactlyWhenItConfirms() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new PaymentDecision(UUID.randomUUID(), id, DecisionKind.CONFIRMED, null, null,
                administrator, NOW, "k", HASH)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> new PaymentDecision(UUID.randomUUID(), id, DecisionKind.REJECTED, "x".repeat(20),
                UUID.randomUUID(), administrator, NOW, "k", HASH)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> new PaymentDecision(UUID.randomUUID(), id, DecisionKind.REJECTED, null, null,
                administrator, NOW, "k", HASH)).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void theHashDecidesWhetherARequestIsAReplay() {
        Payment payment = declared();

        assertThat(payment.answers(HASH)).isTrue();
        assertThat(payment.answers("b".repeat(64))).isFalse();
    }

    @Test
    void theLogShowsNoNoteNoReferenceAndNoReason() {
        PaymentRequest withNotes = PaymentRequest.of(student, null, "BANK_TRANSFER", "100.00", TODAY, TODAY, "BAC-123456",
                "BAC", "pago de la madre de Ana");
        Payment payment = Payment.declaredTransfer(UUID.randomUUID(), withNotes, cashier, NOW, "key", HASH);

        assertThat(payment.toString()).doesNotContain("Ana").doesNotContain("BAC-123456");
    }

    @Test
    void thePostingPurposesAreAssetsThatCarryNoStudentBalance() {
        assertThat(PostingPurpose.CASH.accepts("ASSET", false)).isTrue();
        assertThat(PostingPurpose.BANK.accepts("ASSET", false)).isTrue();
        assertThat(PostingPurpose.CASH.accepts("ASSET", true)).isFalse();
        assertThat(PostingPurpose.BANK.accepts("LIABILITY", false)).isFalse();
        assertThat(PostingPurpose.ofName("cash")).isEmpty();
        assertThatThrownBy(() -> PostingPurpose.parse("RECEIVABLE")).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aMapMissingAnyAccountIsIncompleteAndRefusesToPost() {
        UUID account = UUID.randomUUID();
        assertThat(new PostingAccounts(account, account, account).complete()).isTrue();
        assertThat(new PostingAccounts(account, account, account).debitFor(PaymentMethod.CASH)).isEqualTo(account);
        for (PostingAccounts incomplete : new PostingAccounts[] {new PostingAccounts(null, account, account),
                new PostingAccounts(account, null, account), new PostingAccounts(account, account, null)}) {
            assertThat(incomplete.complete()).isFalse();
            assertThatThrownBy(() -> incomplete.debitFor(PaymentMethod.CASH))
                    .isInstanceOf(PostingAccountsIncompleteException.class);
            assertThatThrownBy(incomplete::receivableAccount).isInstanceOf(PostingAccountsIncompleteException.class);
        }
        UUID cash = UUID.randomUUID();
        UUID bank = UUID.randomUUID();
        PostingAccounts both = new PostingAccounts(cash, bank, account);
        assertThat(both.debitFor(PaymentMethod.CASH)).isEqualTo(cash);
        assertThat(both.debitFor(PaymentMethod.BANK_TRANSFER)).isEqualTo(bank);
    }
}

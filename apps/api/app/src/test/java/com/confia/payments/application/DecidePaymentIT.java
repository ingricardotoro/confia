package com.confia.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.payments.PaymentsFixture;
import com.confia.payments.domain.DuplicateTransferReferenceException;
import com.confia.payments.domain.InvalidPaymentsValueException;
import com.confia.payments.domain.PaymentNotDeclaredException;
import com.confia.payments.domain.PostingAccountsIncompleteException;
import com.confia.payments.domain.ResourceNotFoundException;
import com.confia.payments.domain.SelfApprovalForbiddenException;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.support.StaffRoleFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The declared bank transfer against a real PostgreSQL (docs/cortes/payments-register.md): it touches no balance, no ledger and no
 * charge until a person authorized who is not the one who registered it confirms it; a rejection leaves no entry ever; a
 * confirmation and a rejection at the same time leave exactly one winner; the duplicate reference; the keys of the decisions; and
 * what is allocated when a pending charge was voided after the transfer was declared. Accounts, prices and people are fictional.
 */
class DecidePaymentIT extends PaymentsFixture {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private Pay payerWithTwoCharges() throws SQLException {
        Pay pay = seedPayments(1);
        accrueFebruary(pay.env(), "feb");
        accrue(pay.env(), "mar", MARCH);
        return pay;
    }

    private PaymentDetail declare(Pay pay, String amount, String reference) {
        return registerPayment().register(pay.cashier(), request(), "declare-" + UUID.randomUUID(),
                transfer(pay.student(0), amount, reference));
    }

    private long ledgerTransactions(Pay pay) throws SQLException {
        return scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ?",
                pay.institution());
    }

    private long allocations(Pay pay) throws SQLException {
        return scalar(pay.institution(), "select count(*) from payments_payment_allocation where institution_id = ?",
                pay.institution());
    }

    // --- Declared: no effect on the balance ---

    @Test
    void aDeclaredTransferTouchesNoBalanceNoLedgerNoChargeAndNoCash() throws SQLException {
        Pay pay = payerWithTwoCharges();
        long ledgerBefore = ledgerTransactions(pay);

        PaymentDetail declared = declare(pay, "2000.00", "BAC-000111");

        assertThat(declared.status()).isEqualTo("declared");
        assertThat(declared.method()).isEqualTo("BANK_TRANSFER");
        assertThat(declared.allocations()).isEmpty();
        assertThat(declared.allocated().isZero()).isTrue();
        assertThat(declared.unallocated().isZero()).isTrue();
        assertThat(declared.ledgerTransactionId()).isNull();
        assertThat(declared.cashSessionId()).isNull();
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("3300.00");
        assertThat(ledgerTransactions(pay)).isEqualTo(ledgerBefore);
        assertThat(allocations(pay)).isZero();
        assertThat(decimal(pay.institution(), "select count(*) from cashbox_movement where institution_id = ? and type = "
                + "'payment_in'", pay.institution())).isEqualByComparingTo("0");
    }

    @Test
    void aTransferDeclaredWithoutAnOpenSessionOrAMapIsStoredBecauseItNeedsNeither() throws SQLException {
        Pay pay = seedPayments(1, false);

        PaymentDetail declared = registerPayment().register(pay.otherCashier(), request(), "declare-1",
                transfer(pay.student(0), "10.00", "BAC-000999"));

        assertThat(declared.status()).isEqualTo("declared");
    }

    // --- Confirm ---

    @Test
    void anAdministratorConfirmsTheTransferAndThenItIsPostedToTheBankAndAppliedOldestFirst() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "2000.00", "BAC-000111");

        PaymentDetail confirmed = confirmPayment().confirm(pay.administrator(), request(), "confirm-1", declared.id());

        assertThat(confirmed.status()).isEqualTo("confirmed");
        assertThat(confirmed.allocations()).extracting(PaymentDetail.AllocationView::amount)
                .containsExactly(hnl("1650.00"), hnl("350.00"));
        assertThat(confirmed.unallocated().isZero()).isTrue();
        assertThat(confirmed.ledgerTransactionId()).isNotNull();
        assertThat(confirmed.decision().kind()).isEqualTo("confirmed");
        assertThat(confirmed.decision().decidedBy()).isEqualTo(pay.administrator().accountId());
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("1300.00");
        assertThat(decimal(pay.institution(), "select sum(amount) from ledger_entry where institution_id = ? and side = "
                + "'DEBIT' and account_id = ?", pay.institution(), pay.bankAccount())).isEqualByComparingTo("2000.00");
        assertThat(decimal(pay.institution(), "select count(*) from cashbox_movement where institution_id = ? and type = "
                + "'payment_in'", pay.institution())).as("a transfer never touches the cash").isEqualByComparingTo("0");
        assertThat(getPayment().get(pay.accountant(), request(), declared.id()).status()).isEqualTo("confirmed");
    }

    @Test
    void whoRegisteredTheTransferNeitherConfirmsNorRejectsItAndNothingIsWritten() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");
        long ledgerBefore = ledgerTransactions(pay);

        assertThatThrownBy(() -> confirmPayment().confirm(pay.cashier(), request(), "confirm-1", declared.id()))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> rejectPayment().reject(pay.cashier(), request(), "reject-1", declared.id(), NO_REASON))
                .isInstanceOf(SelfApprovalForbiddenException.class);

        assertThat(getPayment().get(pay.accountant(), request(), declared.id()).status()).isEqualTo("declared");
        assertThat(ledgerTransactions(pay)).isEqualTo(ledgerBefore);
    }

    @Test
    void aConfirmedTransferIsNotConfirmedOrRejectedAgainWhateverTheKey() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");
        confirmPayment().confirm(pay.administrator(), request(), "confirm-1", declared.id());

        assertThatThrownBy(() -> confirmPayment().confirm(pay.administrator(), request(), "confirm-2", declared.id()))
                .isInstanceOf(PaymentNotDeclaredException.class);
        assertThatThrownBy(() -> rejectPayment().reject(pay.administrator(), request(), "reject-1", declared.id(), NO_REASON))
                .isInstanceOf(PaymentNotDeclaredException.class);

        assertThat(scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ? and kind = "
                + "'PAYMENT'", pay.institution())).isEqualTo(1);
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("3200.00");
    }

    @Test
    void theSameConfirmationKeyAnswersTheSamePaymentAndWritesNothingMore() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");
        PaymentDetail first = confirmPayment().confirm(pay.administrator(), request(), "confirm-1", declared.id());
        long ledgerBefore = ledgerTransactions(pay);

        PaymentDetail again = confirmPayment().confirm(pay.administrator(), request(), "confirm-1", declared.id());

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.status()).isEqualTo("confirmed");
        assertThat(ledgerTransactions(pay)).isEqualTo(ledgerBefore);
        assertThat(allocations(pay)).isEqualTo(1);
    }

    @Test
    void aDecisionKeyUsedForAnotherPaymentOrByAnotherStaffMemberIsRefused() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail one = declare(pay, "100.00", "BAC-000111");
        PaymentDetail two = declare(pay, "100.00", "BAC-000222");
        confirmPayment().confirm(pay.administrator(), request(), "confirm-1", one.id());

        assertThatThrownBy(() -> confirmPayment().confirm(pay.administrator(), request(), "confirm-1", two.id()))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> confirmPayment().confirm(pay.accountant(), request(), "confirm-1", one.id()))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
    }

    @Test
    void cashIsNeverConfirmedNorRejected() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail cash = registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "100.00"));

        assertThatThrownBy(() -> confirmPayment().confirm(pay.administrator(), request(), "confirm-1", cash.id()))
                .isInstanceOf(PaymentNotDeclaredException.class);
    }

    @Test
    void anUnknownPaymentOrOneOfAnotherInstitutionIsNotFound() throws SQLException {
        Pay pay = payerWithTwoCharges();
        Pay other = seedPayments(1);
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");

        assertThatThrownBy(() -> confirmPayment().confirm(other.administrator(), request(), "confirm-1", declared.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> rejectPayment().reject(other.administrator(), request(), "reject-1", declared.id(),
                NO_REASON)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> confirmPayment().confirm(pay.administrator(), request(), "confirm-2", UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void withoutThePaymentsMapTheTransferCannotBeConfirmedAndStaysDeclared() throws SQLException {
        Pay pay = seedPayments(1, false);
        accrueFebruary(pay.env(), "feb");
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");

        assertThatThrownBy(() -> confirmPayment().confirm(pay.administrator(), request(), "confirm-1", declared.id()))
                .isInstanceOf(PostingAccountsIncompleteException.class);

        assertThat(getPayment().get(pay.administrator(), request(), declared.id()).status()).isEqualTo("declared");
        assertThat(allocations(pay)).isZero();
    }

    @Test
    void whenAPendingChargeWasVoidedAfterTheDeclarationTheConfirmationAppliesToWhatIsActiveNow() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "2000.00", "BAC-000111");
        List<UUID> charges = chargeIds(pay);
        voidChargeWithPayments().voidCharge(pay.env().actor(), request(), "void-march", charges.get(1), REASON);

        PaymentDetail confirmed = confirmPayment().confirm(pay.administrator(), request(), "confirm-1", declared.id());

        assertThat(confirmed.allocations()).singleElement().satisfies(line -> {
            assertThat(line.chargeId()).isEqualTo(charges.get(0));
            assertThat(line.amount()).isEqualTo(hnl("1650.00"));
        });
        assertThat(confirmed.unallocated()).isEqualTo(hnl("350.00"));
        assertThat(owed(pay, pay.student(0))).as("1,650.00 owed after the void, 2,000.00 paid")
                .isEqualByComparingTo("-350.00");
    }

    // --- Reject ---

    @Test
    void aRejectedTransferLeavesNoEntryAndTheBalanceNeverChanges() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "2000.00", "BAC-000111");
        long ledgerBefore = ledgerTransactions(pay);

        PaymentDetail rejected = rejectPayment().reject(pay.administrator(), request(), "reject-1", declared.id(),
                "  " + NO_REASON + "  ");

        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.decision().kind()).isEqualTo("rejected");
        assertThat(rejected.decision().reason()).as("stripped, kept only in the decision").isEqualTo(NO_REASON);
        assertThat(rejected.allocations()).isEmpty();
        assertThat(rejected.ledgerTransactionId()).isNull();
        assertThat(ledgerTransactions(pay)).isEqualTo(ledgerBefore);
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("3300.00");
        assertThat(allocations(pay)).isZero();
        assertThat(scalar(pay.institution(), "select count(*) from shared_audit_log where institution_id = ? and "
                + "action = 'payments.payment.rejected' and after_value::text not like '%banco%'", pay.institution()))
                .as("the reason is audited by its length only").isEqualTo(1);
        assertThat(scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ? and reason "
                + "like '%banco%'", pay.institution())).isZero();
    }

    @Test
    void theReasonOfARejectionIsTwentyToFiveHundredCharacters() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");

        assertThatThrownBy(() -> rejectPayment().reject(pay.administrator(), request(), "reject-1", declared.id(), "corto"))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> rejectPayment().reject(pay.administrator(), request(), "reject-2", declared.id(),
                "x".repeat(501))).isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(getPayment().get(pay.administrator(), request(), declared.id()).status()).isEqualTo("declared");
    }

    @Test
    void theSameRejectionKeyAnswersTheSamePaymentAndAnotherReasonIsRefused() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");
        rejectPayment().reject(pay.administrator(), request(), "reject-1", declared.id(), NO_REASON);

        PaymentDetail again = rejectPayment().reject(pay.administrator(), request(), "reject-1", declared.id(), NO_REASON);

        assertThat(again.status()).isEqualTo("rejected");
        assertThatThrownBy(() -> rejectPayment().reject(pay.administrator(), request(), "reject-1", declared.id(),
                "Otro motivo distinto del primero")).isInstanceOf(IdempotencyPayloadMismatchException.class);
    }

    // --- The reference of the transfer ---

    @Test
    void theSameBankAndReferenceIsNotDeclaredTwiceWhateverTheCapitalsUntilTheFirstIsRejected() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail first = declare(pay, "100.00", "BAC-000111");

        assertThatThrownBy(() -> declare(pay, "50.00", "BAC-000111")).isInstanceOf(DuplicateTransferReferenceException.class);
        assertThatThrownBy(() -> declare(pay, "50.00", "bac-000111")).isInstanceOf(DuplicateTransferReferenceException.class);
        assertThat(registerPayment().register(pay.cashier(), request(), "other-bank", new RegisterPayment.Command(
                pay.student(0), null, "BANK_TRANSFER", "50.00", today(), "BAC-000111", "Otro banco", null)).status())
                .as("the pair is bank and reference").isEqualTo("declared");

        rejectPayment().reject(pay.administrator(), request(), "reject-1", first.id(), NO_REASON);
        assertThat(declare(pay, "100.00", "BAC-000111").status()).as("a rejected reference is free again")
                .isEqualTo("declared");
    }

    @Test
    void aConfirmedTransferKeepsItsReferenceTaken() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail first = declare(pay, "100.00", "BAC-000111");
        confirmPayment().confirm(pay.administrator(), request(), "confirm-1", first.id());

        assertThatThrownBy(() -> declare(pay, "100.00", "BAC-000111")).isInstanceOf(DuplicateTransferReferenceException.class);
    }

    @Test
    void theSameReferenceDeclaredAtTheSameTimeForTwoStudentsLeavesOne() throws Exception {
        Pay pay = seedPayments(2);
        List<Boolean> outcomes = concurrently(
                () -> attempt(() -> registerPayment().register(pay.cashier(), request(), "race-1",
                        transfer(pay.student(0), "10.00", "BAC-RACE-01"))),
                () -> attempt(() -> registerPayment().register(pay.cashier(), request(), "race-2",
                        transfer(pay.student(1), "10.00", "BAC-RACE-01"))));

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(scalar(pay.institution(), "select count(*) from payments_payment where institution_id = ?",
                pay.institution())).isEqualTo(1);
    }

    // --- Race ---

    @Test
    void aConfirmationAndARejectionAtTheSameTimeLeaveExactlyOneWinner() throws Exception {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "2000.00", "BAC-000111");

        List<Boolean> outcomes = concurrently(
                () -> attempt(() -> confirmPayment().confirm(pay.administrator(), request(), "confirm-race",
                        declared.id())),
                () -> attempt(() -> rejectPayment().reject(pay.accountant(), request(), "reject-race", declared.id(),
                        NO_REASON)));

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        PaymentDetail settled = getPayment().get(pay.administrator(), request(), declared.id());
        if (settled.status().equals("confirmed")) {
            assertThat(scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ? and kind "
                    + "= 'PAYMENT'", pay.institution())).isEqualTo(1);
            assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("1300.00");
        } else {
            assertThat(settled.status()).isEqualTo("rejected");
            assertThat(scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ? and kind "
                    + "= 'PAYMENT'", pay.institution())).isZero();
            assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("3300.00");
        }
        assertThat(scalar(pay.institution(), "select count(*) from payments_payment_decision where institution_id = ?",
                pay.institution())).isEqualTo(1);
    }

    // --- The database refuses ---

    @Test
    void theDatabaseRefusesADecisionBySomeoneWhoRegisteredOrOnAPaymentThatIsNotADeclaredTransfer() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");
        PaymentDetail cash = registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "100.00"));

        // As the registrar of the transfer (the actor of the transaction), a rejection is refused by the table itself.
        assertThatThrownBy(() -> decideAs(pay, pay.cashier().accountId(), declared.id()))
                .hasMessageContaining("the one who registers a payment never decides it");
        assertThatThrownBy(() -> decideAs(pay, pay.administrator().accountId(), cash.id()))
                .hasMessageContaining("only a declared transfer is decided");
        assertThatThrownBy(() -> ownerExec(pay, "update payments_payment set status = 'confirmed' where institution_id = ? "
                + "and id = ?", pay.institution(), declared.id())).hasMessageContaining("with the decision");
    }

    @Test
    void aDecisionInsertedWithoutTheMoveOfTheStatusNeverCommits() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail declared = declare(pay, "100.00", "BAC-000111");

        // The administrator is not the registrar, so every check of the row itself passes: only the deferred check at COMMIT sees
        // that the payment is still declared.
        assertThatThrownBy(() -> decideAs(pay, pay.administrator().accountId(), declared.id()))
                .hasMessageContaining("status of its payment moved");

        assertThat(scalar(pay.institution(), "select count(*) from payments_payment_decision where institution_id = ? "
                + "and payment_id = ?", pay.institution(), declared.id())).isZero();
        assertThat(rejectPayment().reject(pay.administrator(), request(), "reject-after", declared.id(), NO_REASON).status())
                .as("the transfer can still be decided the proper way").isEqualTo("rejected");
    }

    @Test
    void theStatusOfAPaymentThatIsNotThereIsNeverAdvancedInSilence() throws SQLException {
        Pay pay = seedPayments(1);

        assertThatThrownBy(() -> transactionRunner().execute(pay.administrator().securityContext(request()), () -> {
            new com.confia.payments.infrastructure.JooqPaymentRepository(dsl).advanceStatus(
                    new com.confia.kernel.InstitutionId(pay.institution()), UUID.randomUUID(),
                    com.confia.payments.domain.PaymentStatus.CONFIRMED);
            return null;
        })).isInstanceOf(IllegalStateException.class);
    }

    // --- Helpers ---

    private void decideAs(Pay pay, UUID actor, UUID payment) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution())) {
            try (var context = c.prepareStatement("select set_config('app.actor_id', ?, true)")) {
                context.setString(1, actor.toString());
                context.execute();
            }
            exec(c, "insert into payments_payment_decision (institution_id, id, payment_id, decision, reason, decided_by, "
                    + "request_key, request_hash) values (?, ?, ?, 'rejected', ?, ?, ?, ?)", pay.institution(),
                    UUID.randomUUID(), payment, NO_REASON, actor, "db-" + UUID.randomUUID(), "a".repeat(64));
            c.commit();
        }
    }

    private void ownerExec(Pay pay, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution())) {
            exec(c, sql, parameters);
            c.commit();
        }
    }

    private List<UUID> chargeIds(Pay pay) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution());
                var s = c.prepareStatement("select id from charges_charge where institution_id = ? and student_id = ? "
                        + "order by period_start")) {
            s.setObject(1, pay.institution());
            s.setObject(2, pay.student(0));
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

    private static boolean attempt(Runnable call) {
        try {
            call.run();
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    @SafeVarargs
    private static List<Boolean> concurrently(Callable<Boolean>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

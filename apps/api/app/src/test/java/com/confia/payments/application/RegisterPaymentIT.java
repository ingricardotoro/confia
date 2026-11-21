package com.confia.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.cashbox.application.CloseSession;
import com.confia.cashbox.application.SessionDetails;
import com.confia.cashbox.infrastructure.JooqCashApprovalRepository;
import com.confia.cashbox.infrastructure.JooqCashCloseRepository;
import com.confia.cashbox.infrastructure.JooqCashMovementRepository;
import com.confia.cashbox.infrastructure.JooqCashSessionRepository;
import com.confia.charges.application.StudentChargeView;
import com.confia.charges.application.ChargeFilter;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.payments.PaymentsFixture;
import com.confia.payments.domain.CashSessionNotOpenException;
import com.confia.payments.domain.IdempotencyKeyRequiredException;
import com.confia.payments.domain.InvalidPaymentsValueException;
import com.confia.payments.domain.PostingAccountsIncompleteException;
import com.confia.payments.domain.ResourceNotFoundException;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.support.StaffRoleFixtures;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * The registration of cash payments against a real PostgreSQL (docs/cortes/payments-register.md): the acceptance example, the
 * allocation to the open charges oldest first, the credit that remains, the rule of the open cash session (INV-20), the incomplete
 * map of accounts, the idempotency of the key (INV-24), two payments of the same student at once (INV-17), the void of a charge
 * with payments, the isolation between institutions and the scope of the cashier. Accounts, prices, rates and people are fictional.
 */
class RegisterPaymentIT extends PaymentsFixture {

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    /** One student with two charges of 1,650.00 (February and March 2027): 3,300.00 owed. */
    private Pay payerWithTwoCharges() throws SQLException {
        Pay pay = seedPayments(1);
        accrueFebruary(pay.env(), "feb");
        accrue(pay.env(), "mar", MARCH);
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("3300.00");
        return pay;
    }

    private long payments(Pay pay) throws SQLException {
        return scalar(pay.institution(), "select count(*) from payments_payment where institution_id = ?",
                pay.institution());
    }

    private long paymentTransactions(Pay pay) throws SQLException {
        return scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ? "
                + "and kind = 'PAYMENT'", pay.institution());
    }

    private BigDecimal cashIn(Pay pay) throws SQLException {
        return decimal(pay.institution(), "select coalesce(sum(amount), 0) from cashbox_movement where institution_id = ? "
                + "and type = 'payment_in'", pay.institution());
    }

    private UUID uuid(UUID institution, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    // --- The acceptance flow ---

    @Test
    void aCashierWithAnOpenSessionCollectsInCashAndThePaymentCoversTheOldestChargeFirst() throws SQLException {
        Pay pay = payerWithTwoCharges();
        UUID student = pay.student(0);

        PaymentDetail payment = registerPayment().register(pay.cashier(), request(), "pay-1", cash(student, "2000.00"));

        assertThat(payment.status()).isEqualTo("confirmed");
        assertThat(payment.method()).isEqualTo("CASH");
        assertThat(payment.allocations()).extracting(PaymentDetail.AllocationView::periodLabel)
                .containsExactly("2027-02", "2027-03");
        assertThat(payment.allocations()).extracting(PaymentDetail.AllocationView::amount)
                .containsExactly(hnl("1650.00"), hnl("350.00"));
        assertThat(payment.allocations()).extracting(PaymentDetail.AllocationView::conceptCode).containsOnly("COL");
        assertThat(payment.allocated()).isEqualTo(hnl("2000.00"));
        assertThat(payment.unallocated().isZero()).isTrue();
        assertThat(payment.ledgerTransactionId()).isNotNull();
        assertThat(payment.cashSessionId()).isNotNull();
        assertThat(owed(pay, student)).as("the statement balance now").isEqualByComparingTo("1300.00");
        assertThat(cashIn(pay)).as("the expected cash of the session grew by the payment").isEqualByComparingTo("2000.00");
        assertThat(paymentTransactions(pay)).isEqualTo(1);
        assertThat(scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ? and kind = "
                + "'PAYMENT' and source_module = 'payments' and source_ref_id = ?", pay.institution(), payment.id()))
                .isEqualTo(1);
        // The cash account is debited and the receivable of the student credited, for the whole amount.
        assertThat(decimal(pay.institution(), "select sum(amount) from ledger_entry where institution_id = ? and side = "
                + "'DEBIT' and account_id = ?", pay.institution(), pay.cashAccount())).isEqualByComparingTo("2000.00");
    }

    @Test
    void theChargesOfTheStudentShowWhatIsPaidAndWhatIsOutstandingDerivedFromTheAllocations() throws SQLException {
        Pay pay = payerWithTwoCharges();
        UUID student = pay.student(0);
        registerPayment().register(pay.cashier(), request(), "pay-1", cash(student, "2000.00"));

        com.confia.charges.application.Page<StudentChargeView> page = listChargesWithPayments().list(pay.env().actor(),
                request(), student, new ChargeFilter(null, null, null), com.confia.charges.application.PageRequest.of(0, 50,
                        "periodStart,asc", ChargeFilter.SORT_FIELDS, "periodStart", false));

        assertThat(page.items()).extracting(StudentChargeView::paid)
                .containsExactly(hnl("1650.00"), hnl("350.00"));
        assertThat(page.items()).extracting(StudentChargeView::outstanding)
                .containsExactly(hnl("0.00"), hnl("1300.00"));
    }

    @Test
    void theOutstandingFilterKeepsOnlyWhatIsOwedInTheOrderOfAllocationAndPagesOnTheServer() throws SQLException {
        Pay pay = payerWithTwoCharges();
        accrue(pay.env(), "apr", java.time.LocalDate.parse("2027-04-01"));
        UUID student = pay.student(0);
        // 1,650.00 covers February exactly; March and April stay owed.
        registerPayment().register(pay.cashier(), request(), "pay-1", cash(student, "1650.00"));
        ChargeFilter outstanding = new ChargeFilter(null, null, null, true);
        com.confia.charges.application.PageRequest allocationOrder = new com.confia.charges.application.PageRequest(0, 50,
                com.confia.charges.application.ListStudentCharges.ALLOCATION_ORDER, true);

        var everyCharge = listChargesWithPayments().list(pay.env().actor(), request(), student, ChargeFilter.none(),
                com.confia.charges.application.PageRequest.of(0, 50, "periodStart,asc", ChargeFilter.SORT_FIELDS,
                        "periodStart", false));
        var owed = listChargesWithPayments().list(pay.env().actor(), request(), student, outstanding, allocationOrder);

        assertThat(everyCharge.items()).extracting(StudentChargeView::settled).containsExactly(true, false, false);
        assertThat(owed.total()).isEqualTo(2);
        assertThat(owed.items()).extracting(view -> view.charge().periodLabel()).containsExactly("2027-03", "2027-04");
        assertThat(owed.items()).extracting(StudentChargeView::settled).containsOnly(false);
        assertThat(owed.items()).extracting(StudentChargeView::outstanding).containsOnly(hnl("1650.00"));
        // The order of the list is the order in which the allocation plan covers the charges: due date, then period.
        assertThat(owed.items()).extracting(view -> view.charge().dueOn())
                .isSortedAccordingTo(java.util.Comparator.naturalOrder());

        var secondPage = listChargesWithPayments().list(pay.env().actor(), request(), student, outstanding,
                new com.confia.charges.application.PageRequest(1, 1,
                        com.confia.charges.application.ListStudentCharges.ALLOCATION_ORDER, true));
        assertThat(secondPage.total()).isEqualTo(2);
        assertThat(secondPage.items()).extracting(view -> view.charge().periodLabel()).containsExactly("2027-04");
        var beyond = listChargesWithPayments().list(pay.env().actor(), request(), student, outstanding,
                new com.confia.charges.application.PageRequest(5, 1,
                        com.confia.charges.application.ListStudentCharges.ALLOCATION_ORDER, true));
        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(2);

        // An explicit sort of the client is honoured and the filter still applies.
        var descending = listChargesWithPayments().list(pay.env().actor(), request(), student, outstanding,
                com.confia.charges.application.PageRequest.of(0, 50, "periodStart,desc", ChargeFilter.SORT_FIELDS,
                        "periodStart", false));
        assertThat(descending.items()).extracting(view -> view.charge().periodLabel()).containsExactly("2027-04", "2027-03");

        // The next payment goes to the first charge of the list: the list shows the order in which the payment covers them.
        PaymentDetail next = registerPayment().register(pay.cashier(), request(), "pay-1b", cash(student, "100.00"));
        assertThat(next.allocations()).extracting(PaymentDetail.AllocationView::chargeId)
                .containsExactly(owed.items().get(0).charge().id());

        // Once everything is paid nothing is outstanding.
        registerPayment().register(pay.cashier(), request(), "pay-2", cash(student, "3200.00"));
        assertThat(listChargesWithPayments().list(pay.env().actor(), request(), student, outstanding, allocationOrder)
                .items()).isEmpty();
    }

    @Test
    void anExactPaymentLeavesTheStudentWithNothingOwedAndNoCredit() throws SQLException {
        Pay pay = seedPayments(1);
        accrueFebruary(pay.env(), "feb");

        PaymentDetail payment = registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "1650.00"));

        assertThat(payment.unallocated().isZero()).isTrue();
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("0.00");
    }

    @Test
    void aPaymentLargerThanEverythingOwedLeavesTheRemainderAsACreditBalance() throws SQLException {
        Pay pay = payerWithTwoCharges();

        PaymentDetail payment = registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "4000.00"));

        assertThat(payment.allocated()).isEqualTo(hnl("3300.00"));
        assertThat(payment.unallocated()).isEqualTo(hnl("700.00"));
        assertThat(owed(pay, pay.student(0))).as("negative: credit balance").isEqualByComparingTo("-700.00");
        assertThat(cashIn(pay)).isEqualByComparingTo("4000.00");
    }

    @Test
    void aStudentWithNoChargesHasTheWholePaymentAsCredit() throws SQLException {
        Pay pay = seedPayments(1);

        PaymentDetail payment = registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"));

        assertThat(payment.allocations()).isEmpty();
        assertThat(payment.unallocated()).isEqualTo(hnl("100.00"));
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("-100.00");
    }

    @Test
    void aPartialPaymentLeavesAPendingOnTheChargeAndASecondPaymentFinishesIt() throws SQLException {
        Pay pay = seedPayments(1);
        accrueFebruary(pay.env(), "feb");

        registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "1000.00"));
        PaymentDetail second = registerPayment().register(pay.cashier(), request(), "pay-2",
                cash(pay.student(0), "1000.00"));

        assertThat(second.allocations()).singleElement().satisfies(line -> assertThat(line.amount())
                .as("only what was pending").isEqualTo(hnl("650.00")));
        assertThat(second.unallocated()).isEqualTo(hnl("350.00"));
    }

    // --- INV-20: the open session of the cashier ---

    @Test
    void cashWithoutAnOpenSessionIsRefusedAndNothingIsWritten() throws SQLException {
        Pay pay = payerWithTwoCharges();
        long ledgerBefore = scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ?",
                pay.institution());

        assertThatThrownBy(() -> registerPayment().register(pay.otherCashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"))).isInstanceOf(CashSessionNotOpenException.class);

        assertThat(payments(pay)).isZero();
        assertThat(cashIn(pay)).isEqualByComparingTo("0");
        assertThat(scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ?",
                pay.institution())).isEqualTo(ledgerBefore);
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("3300.00");
    }

    @Test
    void aSessionClosedBetweenTheViewAndTheSubmitRefusesTheCash() throws SQLException {
        Pay pay = payerWithTwoCharges();
        UUID session = uuid(pay.institution(), "select id from cashbox_session where institution_id = ? and cashier_id = ? "
                + "and status = 'open'", pay.institution(), pay.cashier().accountId());
        SessionDetails details = new SessionDetails(new JooqCashMovementRepository(dsl), new JooqCashCloseRepository(dsl),
                new JooqCashApprovalRepository(dsl));
        new CloseSession(transactionRunner(), new JooqCashSessionRepository(dsl), new JooqCashMovementRepository(dsl),
                new JooqCashCloseRepository(dsl), details, new JooqAuditLogWriter(dsl), NOW)
                .close(pay.cashier(), request(), "close-1", session, "500.00", null);

        assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"))).isInstanceOf(CashSessionNotOpenException.class);
        assertThat(payments(pay)).isZero();
    }

    @Test
    void theSessionOfAnotherCashierIsNeverUsed() throws SQLException {
        Pay pay = seedPayments(1);
        openSession(pay.otherCashier(), "0.00");
        accrueFebruary(pay.env(), "feb");

        PaymentDetail payment = registerPayment().register(pay.otherCashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"));

        UUID otherSession = uuid(pay.institution(), "select id from cashbox_session where institution_id = ? and cashier_id "
                + "= ?", pay.institution(), pay.otherCashier().accountId());
        assertThat(payment.cashSessionId()).isEqualTo(otherSession);
        assertThat(decimal(pay.institution(), "select coalesce(sum(amount), 0) from cashbox_movement m join cashbox_session s "
                + "on s.institution_id = m.institution_id and s.id = m.session_id where m.institution_id = ? and s.cashier_id "
                + "= ?", pay.institution(), pay.cashier().accountId())).isEqualByComparingTo("0");
    }

    // --- Accounts ---

    @Test
    void withoutThePaymentsMapCashIsRefusedAndNothingIsWritten() throws SQLException {
        Pay pay = seedPayments(1, false);
        accrueFebruary(pay.env(), "feb");

        assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"))).isInstanceOf(PostingAccountsIncompleteException.class);

        assertThat(payments(pay)).isZero();
        assertThat(cashIn(pay)).isEqualByComparingTo("0");
    }

    @Test
    void aMapWithOnlyTheCashAccountIsStillIncomplete() throws SQLException {
        Pay pay = seedPayments(1, false);
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution())) {
            exec(c, "insert into payments_posting_account (institution_id, id, purpose, ledger_account_id) values (?, ?, "
                    + "'CASH', ?)", pay.institution(), UUID.randomUUID(), pay.cashAccount());
            c.commit();
        }

        assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"))).isInstanceOf(PostingAccountsIncompleteException.class);
    }

    @Test
    void anInactiveCashAccountMakesTheMapUnusable() throws SQLException {
        Pay pay = seedPayments(1);
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution())) {
            exec(c, "update ledger_account set is_active = false where institution_id = ? and id = ?", pay.institution(),
                    pay.cashAccount());
            c.commit();
        }

        assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"))).isInstanceOf(PostingAccountsIncompleteException.class);
    }

    // --- INV-19 and the request ---

    @Test
    void anAmountThatIsNotPositiveOrHasTooManyDecimalsIsRefusedBeforeAnyEffect() throws SQLException {
        Pay pay = seedPayments(1);

        for (String amount : new String[] {"0.00", "-1.00", "10.001", "abc"}) {
            assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-" + amount,
                    cash(pay.student(0), amount))).as(amount).isInstanceOf(InvalidPaymentsValueException.class);
        }
        assertThat(payments(pay)).isZero();
        assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), null, cash(pay.student(0), "1.00")))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
    }

    @Test
    void aStudentOfAnotherInstitutionLooksLikeOneThatDoesNotExist() throws SQLException {
        Pay pay = seedPayments(1);
        Pay other = seedPayments(1);

        assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(other.student(0), "1.00"))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(payments(other)).isZero();
    }

    // --- INV-24: the key ---

    @Test
    void theSameKeyAndTheSameRequestAnswerTheSamePaymentAndWriteNothingMore() throws SQLException {
        Pay pay = payerWithTwoCharges();

        PaymentDetail first = registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "1650.00"));
        PaymentDetail again = registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "1650.00"));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.allocations()).hasSize(1);
        assertThat(payments(pay)).isEqualTo(1);
        assertThat(paymentTransactions(pay)).isEqualTo(1);
        assertThat(cashIn(pay)).isEqualByComparingTo("1650.00");
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("1650.00");
    }

    @Test
    void theSameKeyWithAnotherBodyOrAnotherStaffMemberIsRefused() throws SQLException {
        Pay pay = payerWithTwoCharges();
        registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "100.00"));
        openSession(pay.otherCashier(), "0.00");

        assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "100.01"))).isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> registerPayment().register(pay.otherCashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"))).isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThat(payments(pay)).isEqualTo(1);
    }

    @Test
    void aDoubleSubmitAtTheSameTimeProducesOnePayment() throws Exception {
        Pay pay = payerWithTwoCharges();

        List<PaymentDetail> results = concurrently(
                () -> registerPayment().register(pay.cashier(), request(), "pay-race", cash(pay.student(0), "500.00")),
                () -> registerPayment().register(pay.cashier(), request(), "pay-race", cash(pay.student(0), "500.00")));

        assertThat(results).extracting(PaymentDetail::id).containsOnly(results.get(0).id());
        assertThat(payments(pay)).isEqualTo(1);
        assertThat(cashIn(pay)).isEqualByComparingTo("500.00");
    }

    // --- INV-17: two payments of the same student at once ---

    @Test
    void twoSimultaneousPaymentsOfTheSameStudentNeverExceedWhatIsPending() throws Exception {
        Pay pay = payerWithTwoCharges();

        List<PaymentDetail> results = concurrently(
                () -> registerPayment().register(pay.cashier(), request(), "pay-a", cash(pay.student(0), "2000.00")),
                () -> registerPayment().register(pay.cashier(), request(), "pay-b", cash(pay.student(0), "2000.00")));

        BigDecimal allocated = decimal(pay.institution(), "select coalesce(sum(amount), 0) from payments_payment_allocation "
                + "where institution_id = ?", pay.institution());
        assertThat(allocated).as("everything pending was covered, once").isEqualByComparingTo("3300.00");
        assertThat(decimal(pay.institution(), "select coalesce(max(total), 0) from (select sum(amount) as total from "
                + "payments_payment_allocation where institution_id = ? group by charge_id) t", pay.institution()))
                .as("no charge received more than its total").isLessThanOrEqualTo(new BigDecimal("1650.00"));
        Money credit = results.get(0).unallocated().add(results.get(1).unallocated());
        assertThat(credit).isEqualTo(hnl("700.00"));
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("-700.00");
    }

    // --- Void of a charge with payments ---

    @Test
    void aChargeWithPaymentsCannotBeVoidedButOneWithoutCanAndNothingIsReversedByTheRefusal() throws SQLException {
        Pay pay = payerWithTwoCharges();
        registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "100.00"));
        List<UUID> charges = chargeIds(pay);

        assertThatThrownBy(() -> voidChargeWithPayments().voidCharge(pay.env().actor(), request(), "void-1",
                charges.get(0), REASON)).isInstanceOf(com.confia.charges.domain.ChargeHasPaymentsException.class);
        assertThat(scalar(pay.institution(), "select count(*) from ledger_transaction where institution_id = ? and kind = "
                + "'REVERSAL'", pay.institution())).isZero();

        voidChargeWithPayments().voidCharge(pay.env().actor(), request(), "void-2", charges.get(1), REASON);
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("1550.00");
    }

    @Test
    void theDatabaseItselfRefusesToVoidAChargeThatHasAllocations() throws SQLException {
        Pay pay = payerWithTwoCharges();
        registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "100.00"));
        UUID paid = chargeIds(pay).get(0);

        // The void use case without the port of the payments gets as far as the ledger; the guard of the database stops it.
        assertThatThrownBy(() -> voidCharge().voidCharge(pay.env().actor(), request(), "void-db", paid, REASON))
                .hasMessageContaining("a charge with payments is not voided");
    }

    // --- Isolation and scope ---

    @Test
    void anotherInstitutionNeitherSeesNorReadsTheRowsOfThePayments() throws SQLException {
        Pay pay = payerWithTwoCharges();
        Pay other = seedPayments(1);
        PaymentDetail payment = registerPayment().register(pay.cashier(), request(), "pay-1",
                cash(pay.student(0), "100.00"));

        for (String table : new String[] {"payments_payment", "payments_payment_allocation", "payments_posting_account"}) {
            assertThat(countVisibleAsApplication(pay.institution(), table)).as(table).isPositive();
            assertThat(countVisibleAsApplication(other.institution(), table)).as(table + " of another institution")
                    .isEqualTo(table.equals("payments_posting_account") ? 2 : 0);
        }
        assertThatThrownBy(() -> getPayment().get(other.administrator(), request(), payment.id()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aCashierReadsOnlyItsOwnPaymentsWhileTheAccountantReadsThemAll() throws SQLException {
        Pay pay = payerWithTwoCharges();
        openSession(pay.otherCashier(), "0.00");
        PaymentDetail mine = registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "100.00"));
        PaymentDetail theirs = registerPayment().register(pay.otherCashier(), request(), "pay-2",
                cash(pay.student(0), "200.00"));

        assertThat(getPayment().get(pay.cashier(), request(), mine.id()).id()).isEqualTo(mine.id());
        assertThatThrownBy(() -> getPayment().get(pay.cashier(), request(), theirs.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(getPayment().get(pay.accountant(), request(), theirs.id()).id()).isEqualTo(theirs.id());

        PaymentFilter everybody = new PaymentFilter(null, null, null, null, null, null);
        PageRequest page = PageRequest.of(0, 50, null, PaymentFilter.SORT_FIELDS, "registeredAt", false);
        assertThat(listPayments().list(pay.cashier(), request(), everybody, page).items())
                .extracting(PaymentSummary::id).containsExactly(mine.id());
        assertThat(listPayments().list(pay.cashier(), request(), new PaymentFilter(null, null, null, null, null,
                pay.otherCashier().accountId()), page).items()).as("it cannot ask for another cashier")
                        .extracting(PaymentSummary::id).containsExactly(mine.id());
        assertThat(listPayments().list(pay.accountant(), request(), everybody, page).items())
                .extracting(PaymentSummary::id).containsExactlyInAnyOrder(mine.id(), theirs.id());
    }

    @Test
    void theListIsPaginatedFilteredAndSortedByTheServer() throws SQLException {
        Pay pay = payerWithTwoCharges();
        for (int i = 1; i <= 3; i++) {
            registerPayment().register(pay.cashier(), request(), "pay-" + i, cash(pay.student(0), i * 10 + ".00"));
        }
        PaymentFilter everybody = new PaymentFilter(null, null, null, null, null, null);

        Page<PaymentSummary> first = listPayments().list(pay.accountant(), request(), everybody,
                PageRequest.of(0, 2, "amount,desc", PaymentFilter.SORT_FIELDS, "registeredAt", false));

        assertThat(first.total()).isEqualTo(3);
        assertThat(first.items()).extracting(row -> row.amount()).containsExactly(hnl("30.00"), hnl("20.00"));
        assertThat(listPayments().list(pay.accountant(), request(), new PaymentFilter(null, "declared", null, null, null,
                null), PageRequest.of(0, 50, null, PaymentFilter.SORT_FIELDS, "registeredAt", false)).items()).isEmpty();
        assertThatThrownBy(() -> PageRequest.of(0, 500, null, PaymentFilter.SORT_FIELDS, "registeredAt", false))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    // --- The audit trail ---

    @Test
    void theRegistrationIsAuditedWithoutTheNotes() throws SQLException {
        Pay pay = seedPayments(1);
        accrueFebruary(pay.env(), "feb");

        registerPayment().register(pay.cashier(), request(), "pay-1", new com.confia.payments.application.RegisterPayment
                .Command(pay.student(0), null, "CASH", "100.00", today(), null, null, "pago de la madre de Ana"));

        assertThat(scalar(pay.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = "
                + "'payments.payment.registered'", pay.institution())).isEqualTo(1);
        assertThat(scalar(pay.institution(), "select count(*) from shared_audit_log where institution_id = ? and "
                + "after_value::text like '%madre%'", pay.institution())).isZero();
    }

    // --- The database refuses ---

    @Test
    void aPaymentInMovementThatNoPaymentClaimsIsRefusedWhenTheTransactionCommits() throws SQLException {
        Pay pay = seedPayments(1);
        UUID session = uuid(pay.institution(), "select id from cashbox_session where institution_id = ? and cashier_id = ? "
                + "and status = 'open'", pay.institution(), pay.cashier().accountId());

        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            try (PreparedStatement context = c.prepareStatement("select set_config('app.institution_id', ?, true), "
                    + "set_config('app.actor_id', ?, true)")) {
                context.setString(1, pay.institution().toString());
                context.setString(2, pay.cashier().accountId().toString());
                context.execute();
            }
            try (PreparedStatement insert = c.prepareStatement("insert into cashbox_movement (institution_id, id, session_id, "
                    + "type, amount, currency, request_key, request_hash, created_by) values (?, ?, ?, 'payment_in', 5, "
                    + "'HNL', ?, ?, ?)")) {
                insert.setObject(1, pay.institution());
                insert.setObject(2, UUID.randomUUID());
                insert.setObject(3, session);
                insert.setString(4, "forged-" + UUID.randomUUID());
                insert.setString(5, "a".repeat(64));
                insert.setObject(6, pay.cashier().accountId());
                insert.executeUpdate();
            }
            assertThatThrownBy(c::commit).hasMessageContaining("claimed by a payment");
        }
        assertThat(cashIn(pay)).isEqualByComparingTo("0");
    }

    @Test
    void theDatabaseRefusesToEditOrDeleteAPaymentAndAnAllocationBeyondTheChargeOrThePayment() throws SQLException {
        Pay pay = payerWithTwoCharges();
        PaymentDetail first = registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "1650.00"));
        PaymentDetail second = registerPayment().register(pay.cashier(), request(), "pay-2", cash(pay.student(0), "100.00"));
        UUID february = chargeIds(pay).get(0);
        UUID march = chargeIds(pay).get(1);

        assertThatThrownBy(() -> ownerExec(pay, "update payments_payment set amount = 1 where institution_id = ? and id = ?",
                pay.institution(), first.id())).hasMessageContaining("only the status of a payment changes");
        assertThatThrownBy(() -> ownerExec(pay, "delete from payments_payment where institution_id = ? and id = ?",
                pay.institution(), first.id())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> ownerExec(pay, "update payments_payment_allocation set amount = 1 where institution_id = ?",
                pay.institution())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> ownerExec(pay, "insert into payments_payment_allocation (institution_id, id, payment_id, "
                + "charge_id, amount, currency) values (?, ?, ?, ?, 0.01, 'HNL')", pay.institution(), UUID.randomUUID(),
                second.id(), february)).hasMessageContaining("never exceed its total");
        assertThatThrownBy(() -> ownerExec(pay, "insert into payments_payment_allocation (institution_id, id, payment_id, "
                + "charge_id, amount, currency) values (?, ?, ?, ?, 100.01, 'HNL')", pay.institution(), UUID.randomUUID(),
                second.id(), march)).hasMessageContaining("never exceed");
        assertThatThrownBy(() -> ownerExec(pay, "update payments_payment set status = 'declared' where institution_id = ? "
                + "and id = ?", pay.institution(), first.id())).hasMessageContaining("moves forward from declared only");
    }

    // --- Review fixes: the cash day, the credit of the posting, the void guard ---

    @Test
    void cashIsOnlyReceivedOnTheDayOfTheSessionAndNothingIsWrittenOtherwise() throws SQLException {
        Pay pay = payerWithTwoCharges();

        for (java.time.LocalDate day : new java.time.LocalDate[] {today().minusDays(1), today().plusDays(1)}) {
            assertThatThrownBy(() -> registerPayment().register(pay.cashier(), request(), "pay-" + day,
                    new com.confia.payments.application.RegisterPayment.Command(pay.student(0), null, "CASH", "100.00", day,
                            null, null, null)))
                    .isInstanceOfSatisfying(com.confia.payments.application.RejectedPaymentFieldException.class,
                            e -> assertThat(e.field()).isEqualTo("receivedOn"));
        }

        assertThat(payments(pay)).isZero();
        assertThat(cashIn(pay)).isEqualByComparingTo("0");
        assertThat(owed(pay, pay.student(0))).isEqualByComparingTo("3300.00");
    }

    @Test
    void theDatabaseRefusesACashPaymentDatedAnotherDayThanTheSessionAndOneCreditingAnotherStudent() throws SQLException {
        Pay pay = seedPayments(2);
        accrueFebruary(pay.env(), "feb");

        forgeCashPayment(pay, pay.student(0), pay.student(0), today());

        assertThatThrownBy(() -> forgeCashPayment(pay, pay.student(0), pay.student(0), today().minusDays(1)))
                .hasMessageContaining("business day of the open session");
        assertThatThrownBy(() -> forgeCashPayment(pay, pay.student(0), pay.student(1), today()))
                .hasMessageContaining("credits the sub-account of the student of the payment");
        assertThat(payments(pay)).isEqualTo(1);
    }

    @Test
    void aDirectVoidWaitsForAnAllocationInFlightAndIsRefusedOnceItCommits() throws Exception {
        Pay pay = seedPayments(1);
        accrueFebruary(pay.env(), "feb");
        // 2,000.00 covers the whole of February (1,650.00) and leaves 350.00 unallocated.
        PaymentDetail first = registerPayment().register(pay.cashier(), request(), "pay-1", cash(pay.student(0), "2000.00"));
        accrue(pay.env(), "mar", MARCH);
        UUID march = chargeIds(pay).get(1);
        UUID transaction = uuid(pay.institution(), "select ledger_transaction_id from charges_charge where institution_id = ? "
                + "and id = ?", pay.institution(), march);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection allocation = StaffRoleFixtures.ownerTransaction(pay.institution())) {
            exec(allocation, "insert into payments_payment_allocation (institution_id, id, payment_id, charge_id, amount, "
                    + "currency) values (?, ?, ?, ?, 100.00, 'HNL')", pay.institution(), UUID.randomUUID(), first.id(),
                    march);
            // The void is inserted straight into the table, as any role with the grant could do. The own checks of the
            // charges module are switched off inside a transaction that is rolled back, so only the guard of payments is
            // under test: the lock of the sub-account that the allocation in flight holds.
            Future<Void> voided = pool.submit(() -> {
                try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution())) {
                    exec(c, "alter table charges_charge_void disable trigger charges_charge_void_before_insert_trg");
                    exec(c, "insert into charges_charge_void (institution_id, id, charge_id, reversal_transaction_id, reason, "
                            + "request_key, request_hash, voided_by) values (?, ?, ?, ?, ?, ?, ?, ?)", pay.institution(),
                            UUID.randomUUID(), march, transaction, "Anulacion directa de prueba", "direct-" + UUID.randomUUID(),
                            "a".repeat(64), pay.administrator().accountId());
                    c.rollback();
                    return null;
                }
            });

            assertThatThrownBy(() -> voided.get(2, TimeUnit.SECONDS)).as("the void waits for the lock of the sub-account")
                    .isInstanceOf(TimeoutException.class);
            allocation.commit();

            assertThatThrownBy(() -> voided.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasMessageContaining("a charge with payments is not voided");
        } finally {
            pool.shutdownNow();
        }

        assertThat(scalar(pay.institution(), "select count(*) from payments_payment_allocation where institution_id = ? "
                + "and charge_id = ?", pay.institution(), march)).isEqualTo(1);
        assertThat(scalar(pay.institution(), "select count(*) from charges_charge_void where institution_id = ? "
                + "and charge_id = ?", pay.institution(), march)).isZero();
    }

    // --- Helpers ---

    /**
     * A cash payment inserted by SQL with everything the database asks for (open session, payment_in movement, PAYMENT
     * posting), dated {@code receivedOn} and crediting the sub-account of {@code creditedStudent}.
     */
    private void forgeCashPayment(Pay pay, UUID student, UUID creditedStudent, java.time.LocalDate receivedOn)
            throws SQLException {
        UUID inst = pay.institution();
        UUID cashier = pay.cashier().accountId();
        UUID session = uuid(inst, "select id from cashbox_session where institution_id = ? and cashier_id = ? "
                + "and status = 'open'", inst, cashier);
        UUID credited = uuid(inst, "select id from ledger_student_account where institution_id = ? and student_id = ?", inst,
                creditedStudent);
        UUID receivable = uuid(inst, "select account_id from ledger_entry where institution_id = ? and student_account_id = ? "
                + "limit 1", inst, credited);
        UUID payment = UUID.randomUUID();
        UUID movement = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(inst)) {
            try (PreparedStatement actor = c.prepareStatement("select set_config('app.actor_id', ?, true)")) {
                actor.setString(1, cashier.toString());
                actor.execute();
            }
            exec(c, "insert into cashbox_movement (institution_id, id, session_id, type, amount, currency, request_key, "
                    + "request_hash, created_by) values (?, ?, ?, 'payment_in', 5, 'HNL', ?, ?, ?)", inst, movement, session,
                    "forged-" + UUID.randomUUID(), "a".repeat(64), cashier);
            exec(c, "insert into ledger_transaction (institution_id, id, kind, effective_on, posting_xid, source_module, "
                    + "source_ref_id, idempotency_key, payload_hash, created_by) values (?, ?, 'PAYMENT', ?, 0, 'payments', "
                    + "?, ?, ?, ?)", inst, transaction, receivedOn, payment, "forged:" + UUID.randomUUID(), "a".repeat(64),
                    cashier);
            exec(c, "insert into ledger_entry (institution_id, id, transaction_id, line_number, account_id, "
                    + "student_account_id, side, amount, currency) values (?, ?, ?, 1, ?, null, 'DEBIT', 5, 'HNL')", inst,
                    UUID.randomUUID(), transaction, pay.cashAccount());
            exec(c, "insert into ledger_entry (institution_id, id, transaction_id, line_number, account_id, "
                    + "student_account_id, side, amount, currency) values (?, ?, ?, 2, ?, ?, 'CREDIT', 5, 'HNL')", inst,
                    UUID.randomUUID(), transaction, receivable, credited);
            exec(c, "insert into payments_payment (institution_id, id, student_id, method, status, amount, currency, "
                    + "received_on, cash_session_id, cash_movement_id, ledger_transaction_id, registered_by, request_key, "
                    + "request_hash) values (?, ?, ?, 'CASH', 'confirmed', 5, 'HNL', ?, ?, ?, ?, ?, ?, ?)", inst, payment,
                    student, receivedOn, session, movement, transaction, cashier, "forged-" + UUID.randomUUID(),
                    "a".repeat(64));
            c.commit();
        }
    }

    private List<UUID> chargeIds(Pay pay) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution());
                PreparedStatement s = c.prepareStatement("select id from charges_charge where institution_id = ? and "
                        + "student_id = ? order by period_start")) {
            s.setObject(1, pay.institution());
            s.setObject(2, pay.student(0));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

    private void ownerExec(Pay pay, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution())) {
            exec(c, sql, parameters);
            c.commit();
        }
    }

    /** Runs both at once, released by the same latch, and answers what each returned (any failure fails the test). */
    @SafeVarargs
    private static <T> List<T> concurrently(Callable<T>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        try {
            CountDownLatch ready = new CountDownLatch(calls.length);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception cause ? cause : e;
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

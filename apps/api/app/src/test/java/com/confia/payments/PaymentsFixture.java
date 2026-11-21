package com.confia.payments;

import com.confia.cashbox.application.OpenSession;
import com.confia.cashbox.application.RecordPaymentIn;
import com.confia.cashbox.application.SessionDetails;
import com.confia.cashbox.application.api.CashPayments;
import com.confia.cashbox.infrastructure.JooqCashApprovalRepository;
import com.confia.cashbox.infrastructure.JooqCashCloseRepository;
import com.confia.cashbox.infrastructure.JooqCashMovementRepository;
import com.confia.cashbox.infrastructure.JooqCashSessionRepository;
import com.confia.catalog.infrastructure.JooqPaymentConceptDirectory;
import com.confia.charges.ChargesLedgerFixture;
import com.confia.charges.application.AccrualRunView;
import com.confia.charges.application.ExecuteAccrualRun;
import com.confia.charges.application.ListStudentCharges;
import com.confia.charges.application.PayableChargesService;
import com.confia.charges.application.VoidCharge;
import com.confia.charges.application.api.PayableCharges;
import com.confia.charges.infrastructure.JooqAccrualRunItemRepository;
import com.confia.charges.infrastructure.JooqAccrualRunRepository;
import com.confia.charges.infrastructure.JooqChargePlanItemRepository;
import com.confia.charges.infrastructure.JooqChargePlanRepository;
import com.confia.charges.infrastructure.JooqChargeRepository;
import com.confia.charges.infrastructure.JooqChargeVoidRepository;
import com.confia.identity.application.AccountOwnRecordPolicy;
import com.confia.identity.infrastructure.JooqStaffRoleAssignmentRepository;
import com.confia.payments.application.ConfirmPayment;
import com.confia.payments.application.GetPayment;
import com.confia.payments.application.ListPayments;
import com.confia.payments.application.ManagePostingAccounts;
import com.confia.payments.application.RegisterPayment;
import com.confia.payments.application.RejectPayment;
import com.confia.payments.infrastructure.AllocationChargePayments;
import com.confia.payments.infrastructure.JooqPaymentAllocationRepository;
import com.confia.payments.infrastructure.JooqPaymentDecisionRepository;
import com.confia.payments.infrastructure.JooqPaymentRepository;
import com.confia.payments.infrastructure.JooqPostingAccountRepository;
import com.confia.payments.infrastructure.RoleDirectoryPaymentScope;
import com.confia.organization.infrastructure.JooqPlacementDirectory;
import com.confia.catalog.infrastructure.JooqPricingDirectory;
import com.confia.students.infrastructure.JooqEnrollmentRoster;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.students.infrastructure.JooqStudentDirectory;
import com.confia.support.StaffRoleFixtures;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * What the integration tests of the payments share: the institution of {@link ChargesLedgerFixture} (an active monthly plan, fictional
 * prices of 1,500.00 with a fictional 10 % tax, so each charge is 1,650.00) with the cash and bank accounts of the chart, the payments
 * map, a cashier with an open cash session, another cashier, an accountant and an administrator, and the real use cases over the real
 * database. The payments use the system clock because the database refuses a payment, a movement or a session stamped with another
 * time than now. Accounts, prices, rates and people are fictional.
 */
public abstract class PaymentsFixture extends ChargesLedgerFixture {

    protected static final Clock NOW = Clock.systemUTC();
    protected static final LocalDate MARCH = LocalDate.parse("2027-03-01");
    protected static final String NO_REASON = "El banco no registra esta transferencia";

    /** What a payments test needs of the institution it seeded. */
    protected record Pay(Env env, UUID cashAccount, UUID bankAccount, AuthenticatedActor cashier,
            AuthenticatedActor otherCashier, AuthenticatedActor accountant, AuthenticatedActor administrator) {

        public UUID institution() {
            return env.institution();
        }

        public UUID student(int index) {
            return env.students().get(index);
        }
    }

    protected static LocalDate today() {
        return LocalDate.now(ZoneId.of("America/Tegucigalpa"));
    }

    protected static AuthenticatedActor actorOf(UUID institution, String roleCode) throws SQLException {
        AuthenticatedActor actor = new AuthenticatedActor(UUID.randomUUID(), institution, UUID.randomUUID(),
                UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD), NOW.instant(), NOW.instant().plusSeconds(900));
        StaffRoleFixtures.committed(institution, actor.accountId(), roleCode);
        return actor;
    }

    protected static String request() {
        return UUID.randomUUID().toString();
    }

    // --- The real services over the real database ---

    protected PayableCharges payable() {
        return new PayableChargesService(new JooqChargeRepository(dsl),
                new com.confia.charges.infrastructure.JooqPostingAccountRepository(dsl),
                new JooqPaymentConceptDirectory(dsl));
    }

    protected CashPayments cashPayments() {
        return new RecordPaymentIn(transactionRunner(), new JooqCashSessionRepository(dsl),
                new JooqCashMovementRepository(dsl), new JooqAuditLogWriter(dsl), NOW);
    }

    protected RegisterPayment registerPayment() {
        return new RegisterPayment(transactionRunner(), new JooqPaymentRepository(dsl),
                new JooqPaymentDecisionRepository(dsl), new JooqPaymentAllocationRepository(dsl),
                new JooqPostingAccountRepository(dsl), new JooqStudentDirectory(dsl), cashPayments(), ledger(), ledger(),
                payable(), new JooqAuditLogWriter(dsl), NOW);
    }

    protected ConfirmPayment confirmPayment() {
        return new ConfirmPayment(transactionRunner(), new JooqPaymentRepository(dsl),
                new JooqPaymentDecisionRepository(dsl), new JooqPaymentAllocationRepository(dsl),
                new JooqPostingAccountRepository(dsl), new JooqStudentDirectory(dsl), ledger(), ledger(), payable(), paymentScope(),
                new JooqAuditLogWriter(dsl), NOW);
    }

    protected RejectPayment rejectPayment() {
        return new RejectPayment(transactionRunner(), new JooqPaymentRepository(dsl),
                new JooqPaymentDecisionRepository(dsl), new JooqPaymentAllocationRepository(dsl),
                new JooqStudentDirectory(dsl), payable(), paymentScope(), new JooqAuditLogWriter(dsl), NOW);
    }

    /** The real chain: the scope of the role, read from the assignment stored in the database. */
    protected RoleDirectoryPaymentScope paymentScope() {
        return new RoleDirectoryPaymentScope(new AccountOwnRecordPolicy(new JooqStaffRoleAssignmentRepository(dsl)));
    }

    protected GetPayment getPayment() {
        return new GetPayment(transactionRunner(), new JooqPaymentRepository(dsl), new JooqPaymentDecisionRepository(dsl),
                new JooqPaymentAllocationRepository(dsl), new JooqStudentDirectory(dsl), payable(), paymentScope());
    }

    protected ListPayments listPayments() {
        return new ListPayments(transactionRunner(), new JooqPaymentRepository(dsl), new JooqStudentDirectory(dsl),
                paymentScope());
    }

    protected ManagePostingAccounts managePaymentAccounts() {
        return new ManagePostingAccounts(transactionRunner(), new JooqPostingAccountRepository(dsl), ledger(),
                new JooqAuditLogWriter(dsl), NOW);
    }

    /** The void of a charge with the real port that sees the allocations of the payments. */
    protected VoidCharge voidChargeWithPayments() {
        return new VoidCharge(transactionRunner(), new JooqChargeRepository(dsl), new JooqChargeVoidRepository(dsl),
                ledger(), new AllocationChargePayments(new JooqPaymentAllocationRepository(dsl)),
                new JooqAuditLogWriter(dsl), CLOCK);
    }

    protected ListStudentCharges listChargesWithPayments() {
        return new ListStudentCharges(transactionRunner(), new JooqStudentDirectory(dsl), new JooqChargeRepository(dsl),
                new JooqPaymentConceptDirectory(dsl), new AllocationChargePayments(new JooqPaymentAllocationRepository(dsl)),
                new JooqAuditLogWriter(dsl));
    }

    /**
     * Accrues one more period of the plan: one charge per student. The engine refuses a period that has not started, so it runs
     * with a clock set on the first day of the period.
     */
    protected AccrualRunView accrue(Env env, String key, LocalDate period) {
        AccrualRunView run = manageAccrual().request(env.actor(), request(), key, env.plan(), period, null);
        Clock onThePeriod = Clock.fixed(period.atTime(18, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        new ExecuteAccrualRun(transactionRunner(), new JooqChargePlanRepository(dsl),
                new JooqChargePlanItemRepository(dsl), new JooqAccrualRunRepository(dsl),
                new JooqAccrualRunItemRepository(dsl), new JooqChargeRepository(dsl), new JooqPlacementDirectory(dsl),
                new JooqEnrollmentRoster(dsl), new JooqStudentDirectory(dsl), new JooqPricingDirectory(dsl),
                new com.confia.charges.infrastructure.JooqPostingAccountRepository(dsl), ledger(), ledger(),
                new JooqAuditLogWriter(dsl), onThePeriod).execute(env.system(), run.id());
        return manageAccrual().get(env.actor(), request(), run.id(), 0, 200).run();
    }

    // --- Seeding ---

    /**
     * The institution of the charges with the cash and bank accounts, the payments map, the actors of its roles and the open
     * cash session (with a float of 500.00) of the cashier. The charges are not accrued: a test accrues what it needs.
     */
    protected Pay seedPayments(int students) throws SQLException {
        return seedPayments(students, true);
    }

    protected Pay seedPayments(int students, boolean withPaymentsMap) throws SQLException {
        Env env = seed(students);
        UUID cash = UUID.randomUUID();
        UUID bank = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "insert into ledger_account (institution_id, id, code, name, account_type, holds_student_balances) "
                    + "values (?, ?, '1101', 'Caja de prueba', 'ASSET', false)", env.institution(), cash);
            exec(c, "insert into ledger_account (institution_id, id, code, name, account_type, holds_student_balances) "
                    + "values (?, ?, '1102', 'Banco de prueba', 'ASSET', false)", env.institution(), bank);
            if (withPaymentsMap) {
                exec(c, "insert into payments_posting_account (institution_id, id, purpose, ledger_account_id) "
                        + "values (?, ?, 'CASH', ?)", env.institution(), UUID.randomUUID(), cash);
                exec(c, "insert into payments_posting_account (institution_id, id, purpose, ledger_account_id) "
                        + "values (?, ?, 'BANK', ?)", env.institution(), UUID.randomUUID(), bank);
            }
            c.commit();
        }
        Pay pay = new Pay(env, cash, bank, actorOf(env.institution(), "cashier"), actorOf(env.institution(), "cashier"),
                actorOf(env.institution(), "accounting"), actorOf(env.institution(), "administrator"));
        openSession(pay.cashier(), "500.00");
        return pay;
    }

    protected void openSession(AuthenticatedActor cashier, String openingFloat) {
        SessionDetails details = new SessionDetails(new JooqCashMovementRepository(dsl),
                new JooqCashCloseRepository(dsl), new JooqCashApprovalRepository(dsl));
        new OpenSession(transactionRunner(), new JooqCashSessionRepository(dsl), details, new JooqAuditLogWriter(dsl), NOW)
                .open(cashier, request(), "open-" + UUID.randomUUID(), openingFloat);
    }

    /** The debits minus the credits of the student receivable: what the student owes, negative for a credit balance. */
    protected static BigDecimal owed(Pay pay, UUID student) throws SQLException {
        return balanceOf(pay.env(), student);
    }

    protected static RegisterPayment.Command cash(UUID student, String amount) {
        return new RegisterPayment.Command(student, null, "CASH", amount, today(), null, null, null);
    }

    protected static RegisterPayment.Command transfer(UUID student, String amount, String reference) {
        return new RegisterPayment.Command(student, null, "BANK_TRANSFER", amount, today(), reference, "BAC Credomatic",
                null);
    }
}

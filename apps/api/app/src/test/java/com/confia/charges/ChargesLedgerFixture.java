package com.confia.charges;

import com.confia.catalog.infrastructure.JooqPaymentConceptDirectory;
import com.confia.catalog.infrastructure.JooqPricingDirectory;
import com.confia.charges.application.AccrualRunView;
import com.confia.charges.application.ExecuteAccrualRun;
import com.confia.charges.application.ListStudentCharges;
import com.confia.charges.application.ManageAccrual;
import com.confia.charges.application.RunChargesIntegrityCheck;
import com.confia.charges.application.ViewIntegrityRuns;
import com.confia.charges.application.VoidCharge;
import com.confia.charges.infrastructure.JobAccrualRunScheduler;
import com.confia.charges.infrastructure.JooqAccrualRunItemRepository;
import com.confia.charges.infrastructure.JooqAccrualRunRepository;
import com.confia.charges.infrastructure.JooqChargePlanItemRepository;
import com.confia.charges.infrastructure.JooqChargePlanRepository;
import com.confia.charges.infrastructure.JooqChargeRepository;
import com.confia.charges.infrastructure.JooqChargeVoidRepository;
import com.confia.charges.infrastructure.JooqIntegrityRunRepository;
import com.confia.charges.infrastructure.JooqPostingAccountRepository;
import com.confia.ledger.application.LedgerApi;
import com.confia.ledger.application.LedgerPosting;
import com.confia.ledger.infrastructure.JooqLedgerRepository;
import com.confia.scholarships.application.AwardBenefitsService;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.scholarships.application.DiscountBenefitsService;
import com.confia.scholarships.infrastructure.JooqDiscountsInForceRepository;
import com.confia.scholarships.infrastructure.JooqAwardsInForceRepository;
import com.confia.organization.infrastructure.JooqPlacementDirectory;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionalDataSource;
import com.confia.students.infrastructure.JooqEnrollmentRoster;
import com.confia.students.infrastructure.JooqStudentDirectory;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.StaffRoleFixtures;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the integration tests of the void of a charge and of the nightly integrity check share: an institution seeded as the
 * owner of the schema (an active plan of a grade, students, fictional prices and a fictional tax rate, and the map of
 * accounts), the real services over the real database, and the accrual of February 2027 that produces real charges with their
 * real ledger transactions. Accounts, prices and rates are fictional.
 */
public abstract class ChargesLedgerFixture extends CommittingPostgresIntegrationTest {

    protected static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");
    protected static final Clock CLOCK = Clock.fixed(Instant.parse("2027-02-10T18:00:00Z"), ZoneOffset.UTC);
    protected static final String REASON = "Cobro duplicado por error de la secretaria";

    /** What a test needs of the institution it seeded. */
    protected record Env(UUID institution, UUID plan, UUID concept, UUID receivable, UUID revenue, UUID taxAccount,
            List<UUID> students, UUID admin) {

        public AuthenticatedActor actor() {
            return new AuthenticatedActor(admin, institution, UUID.randomUUID(), UUID.randomUUID(),
                    Set.of(AuthenticationMethod.PASSWORD), CLOCK.instant(), CLOCK.instant().plusSeconds(900));
        }

        public SecurityContext system() {
            return new SecurityContext("", "system", institution.toString(), UUID.randomUUID().toString());
        }
    }

    // --- The real services over the real database ---

    protected LedgerApi ledger() {
        JooqLedgerRepository repository = new JooqLedgerRepository(dsl);
        return new LedgerApi(new LedgerPosting(transactionRunner(), repository, new JooqAuditLogWriter(dsl)), repository);
    }

    protected VoidCharge voidCharge() {
        return new VoidCharge(transactionRunner(), new JooqChargeRepository(dsl), new JooqChargeVoidRepository(dsl),
                ledger(), com.confia.charges.NoChargePayments.INSTANCE, new JooqAuditLogWriter(dsl), CLOCK);
    }

    protected ListStudentCharges listStudentCharges() {
        return new ListStudentCharges(transactionRunner(), new JooqStudentDirectory(dsl), new JooqChargeRepository(dsl),
                new JooqPaymentConceptDirectory(dsl), com.confia.charges.NoChargePayments.INSTANCE,
                new JooqAuditLogWriter(dsl));
    }

    protected ViewIntegrityRuns viewIntegrityRuns() {
        return new ViewIntegrityRuns(transactionRunner(), new JooqIntegrityRunRepository(dsl));
    }

    protected RunChargesIntegrityCheck integrityCheck() {
        return integrityCheck(CLOCK);
    }

    protected RunChargesIntegrityCheck integrityCheck(Clock clock) {
        return new RunChargesIntegrityCheck(transactionRunner(), new JooqChargeRepository(dsl), ledger(),
                new JooqIntegrityRunRepository(dsl), new JooqAuditLogWriter(dsl), new OperationalEventLog(), clock);
    }

    protected ManageAccrual manageAccrual() {
        return new ManageAccrual(transactionRunner(), new JooqChargePlanRepository(dsl),
                new JooqChargePlanItemRepository(dsl), new JooqAccrualRunRepository(dsl),
                new JooqAccrualRunItemRepository(dsl), new JooqChargeRepository(dsl), new JooqPlacementDirectory(dsl),
                new JooqEnrollmentRoster(dsl), new JooqStudentDirectory(dsl), new JooqPricingDirectory(dsl),
                new JooqPostingAccountRepository(dsl), ledger(), awardBenefits(), discountBenefits(),
                new JobAccrualRunScheduler(
                        new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()), new TracedJobSerializer()),
                        CLOCK),
                new JooqAuditLogWriter(dsl), CLOCK);
    }

    protected ExecuteAccrualRun executeAccrualRun() {
        return executeAccrualRun(awardBenefits());
    }

    /** The execution of a run with the scholarships given, to prove what the database refuses when the engine is wrong. */
    protected ExecuteAccrualRun executeAccrualRun(AwardBenefits awards) {
        return executeAccrualRun(awards, discountBenefits());
    }

    /** The same, with the discounts given too. */
    protected ExecuteAccrualRun executeAccrualRun(AwardBenefits awards, DiscountBenefits discounts) {
        return new ExecuteAccrualRun(transactionRunner(), new JooqChargePlanRepository(dsl),
                new JooqChargePlanItemRepository(dsl), new JooqAccrualRunRepository(dsl),
                new JooqAccrualRunItemRepository(dsl), new JooqChargeRepository(dsl), new JooqPlacementDirectory(dsl),
                new JooqEnrollmentRoster(dsl), new JooqStudentDirectory(dsl), new JooqPricingDirectory(dsl),
                new JooqPostingAccountRepository(dsl), ledger(), awards, discounts, ledger(), new JooqAuditLogWriter(dsl),
                CLOCK);
    }

    /** What the approved awards in force offer, read from the real database as the accrual of every process reads it. */
    protected AwardBenefits awardBenefits() {
        return new AwardBenefitsService(new JooqAwardsInForceRepository(dsl));
    }

    /** What the discounts in force offer, read from the real database as the accrual of every process reads it. */
    protected DiscountBenefits discountBenefits() {
        return new DiscountBenefitsService(new JooqDiscountsInForceRepository(dsl));
    }

    /** Requests and executes the accrual of February for the plan: one charge per student. */
    protected AccrualRunView accrueFebruary(Env env, String key) {
        AccrualRunView run = manageAccrual().request(env.actor(), UUID.randomUUID().toString(), key, env.plan(),
                FEBRUARY, null);
        executeAccrualRun().execute(env.system(), run.id());
        return manageAccrual().get(env.actor(), UUID.randomUUID().toString(), run.id(), 0, 200).run();
    }

    /** The charge of each student, by student, in the order the students were seeded. */
    protected Map<UUID, UUID> chargesByStudent(Env env) throws SQLException {
        Map<UUID, UUID> found = new LinkedHashMap<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution());
                PreparedStatement s = c.prepareStatement(
                        "select student_id, id from charges_charge where institution_id = ?")) {
            s.setObject(1, env.institution());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    found.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
                }
            }
        }
        Map<UUID, UUID> ordered = new LinkedHashMap<>();
        env.students().forEach(student -> {
            if (found.containsKey(student)) {
                ordered.put(student, found.get(student));
            }
        });
        return ordered;
    }

    // --- Seeding as the owner (bound by row-level security and by every trigger) ---

    protected static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    protected static long scalar(UUID institution, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    protected static BigDecimal decimal(UUID institution, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                BigDecimal value = rs.getBigDecimal(1);
                return value == null ? BigDecimal.ZERO : value;
            }
        }
    }

    /**
     * Counts ALL the rows of a table (no filter at all) as the least-privilege role of the application with the security
     * context of one institution: what row-level security lets that institution see. {@code table} is a literal of the tests.
     */
    protected static long countVisibleAsApplication(UUID institutionContext, String table) throws SQLException {
        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            try (PreparedStatement context = c.prepareStatement("select set_config('app.institution_id', ?, true)")) {
                context.setString(1, institutionContext.toString());
                context.execute();
            }
            try (PreparedStatement s = c.prepareStatement("select count(*) from " + table);
                    ResultSet rs = s.executeQuery()) {
                rs.next();
                long count = rs.getLong(1);
                c.rollback();
                return count;
            }
        }
    }

    /**
     * The rows of a table that a session of the application sees when its {@code app.institution_id} is as given: {@code null} means
     * the setting was never made and an empty text means it was made empty; neither names an institution, so neither sees a row.
     */
    protected static long countWithSetting(String setting, String table) throws SQLException {
        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            if (setting != null) {
                try (PreparedStatement context = c.prepareStatement("select set_config('app.institution_id', ?, true)")) {
                    context.setString(1, setting);
                    context.execute();
                }
            }
            try (PreparedStatement s = c.prepareStatement("select count(*) from " + table); ResultSet rs = s.executeQuery()) {
                rs.next();
                long count = rs.getLong(1);
                c.rollback();
                return count;
            }
        }
    }

    /**
     * Adds to the map of posting accounts the account of the benefits, of the type given (assumption S4: {@code INCOME} as a
     * contra-revenue or {@code EXPENSE}), and answers its identifier. Fictional account.
     */
    protected static UUID addBenefitsAccount(Env env, String accountType) throws SQLException {
        UUID account = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "insert into ledger_account (institution_id, id, code, name, account_type, holds_student_balances) "
                    + "values (?, ?, '5105', 'Becas y descuentos otorgados', ?, false)", env.institution(), account,
                    accountType);
            exec(c, "insert into charges_posting_account (institution_id, id, purpose, ledger_account_id) "
                    + "values (?, ?, 'BENEFITS', ?)", env.institution(), UUID.randomUUID(), account);
            c.commit();
        }
        return account;
    }

    /** The balance of a student in the ledger: what its receivable debits exceed its credits by. */
    protected static BigDecimal balanceOf(Env env, UUID student) throws SQLException {
        return decimal(env.institution(), "select coalesce(sum(case when e.side = 'DEBIT' then e.amount else -e.amount end), 0) "
                + "from ledger_entry e join ledger_student_account sa on sa.institution_id = e.institution_id "
                + "and sa.id = e.student_account_id where e.institution_id = ? and sa.student_id = ?",
                env.institution(), student);
    }

    /** The second payment concept of an institution seeded with {@link Concepts#TWO_IN_ONE_PLAN} or {@link Concepts#TWO_IN_TWO_PLANS}. */
    protected static UUID secondConcept(Env env) throws SQLException {
        return firstId(env.institution(), "select id from catalog_payment_concept where institution_id = ? and code = 'COL2'",
                env.institution());
    }

    /** The second plan of an institution seeded with {@link Concepts#TWO_IN_TWO_PLANS}. */
    protected static UUID secondPlan(Env env) throws SQLException {
        return firstId(env.institution(), "select id from charges_plan where institution_id = ? and name = 'Plan B'",
                env.institution());
    }

    protected static UUID firstId(UUID institution, String sql, Object... parameters) throws SQLException {
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

    /** An active monthly plan of one grade from February 2027, priced at a fictional 1,500.00 with a fictional 10 % tax. */
    protected Env seed(int students) throws SQLException {
        return seed(students, "1500.00", "10", "HNL");
    }

    /**
     * As {@link #seed(int)} with the price of the concept, the percentage of its fictional tax and the currency of the price given.
     */
    protected Env seed(int students, String price, String taxPercent, String currency) throws SQLException {
        return seed(students, price, taxPercent, currency, Concepts.ONE);
    }

    /** The concepts the seeded plans charge: one, or two (code {@code COL2}) in the same plan or each in its own plan. */
    protected enum Concepts {
        ONE, TWO_IN_ONE_PLAN, TWO_IN_TWO_PLANS
    }

    /**
     * As {@link #seed(int, String, String, String)} with a second payment concept, {@code COL2}, priced like the first and taxed like
     * it: in the plan of the first ({@link Concepts#TWO_IN_ONE_PLAN}) or alone in a second plan, {@code Plan B}, of the same grade
     * ({@link Concepts#TWO_IN_TWO_PLANS}). The identifiers of the second concept and plan are read with {@link #secondConcept} and
     * {@link #secondPlan}.
     */
    protected Env seed(int students, String price, String taxPercent, String currency, Concepts concepts) throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        UUID section = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID concept = UUID.randomUUID();
        UUID rate = UUID.randomUUID();
        UUID list = UUID.randomUUID();
        UUID receivable = UUID.randomUUID();
        UUID revenue = UUID.randomUUID();
        UUID taxAccount = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        List<UUID> seeded = new ArrayList<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(c, "insert into organization_institution (id, legal_name, rtn, address, default_currency, locale, "
                    + "timezone) values (?, 'Colegio de prueba', '1', 'Calle falsa', 'HNL', 'es-HN', "
                    + "'America/Tegucigalpa')", institution);
            exec(c, "insert into organization_academic_year (institution_id, id, name, start_date, end_date, status) "
                    + "values (?, ?, '2027', date '2027-01-01', date '2027-11-30', 'ACTIVE')", institution, year);
            exec(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'PRIM', 'Primaria')",
                    institution, modality);
            exec(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                    + "values (?, ?, ?, 'Primero', 1)", institution, grade, modality);
            exec(c, "insert into organization_section (institution_id, id, academic_year_id, grade_id, name) "
                    + "values (?, ?, ?, ?, 'A')", institution, section, year, grade);
            exec(c, "insert into catalog_tax_rate (institution_id, id, code, name) values (?, ?, 'TAXA', 'Tasa ficticia')",
                    institution, rate);
            exec(c, "insert into catalog_tax_rate_period (institution_id, id, tax_rate_id, rate_percent, valid_from, "
                    + "created_by) values (?, ?, ?, ?::numeric, date '2027-01-01', ?)", institution, UUID.randomUUID(), rate,
                    taxPercent, admin);
            exec(c, "insert into catalog_payment_concept (institution_id, id, code, name, category, is_taxable, "
                    + "is_recurring, tax_rate_id) values (?, ?, 'COL', 'Colegiatura', 'TUITION', true, true, ?)",
                    institution, concept, rate);
            exec(c, "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope) "
                    + "values (?, ?, ?, 'General 2027', 'INSTITUTION')", institution, list, year);
            exec(c, "insert into catalog_price_list_item (institution_id, id, price_list_id, payment_concept_id, "
                    + "amount, currency, valid_from, created_by) values (?, ?, ?, ?, ?::numeric, ?, date '2027-01-01', ?)",
                    institution, UUID.randomUUID(), list, concept, price, currency, admin);
            for (Object[] account : new Object[][] {{receivable, "1105", "ASSET", true},
                    {revenue, "4105", "INCOME", false}, {taxAccount, "2105", "LIABILITY", false}}) {
                exec(c, "insert into ledger_account (institution_id, id, code, name, account_type, "
                        + "holds_student_balances) values (?, ?, ?, 'Cuenta de prueba', ?, ?)", institution, account[0],
                        account[1], account[2], account[3]);
            }
            exec(c, "insert into charges_posting_account (institution_id, id, purpose, ledger_account_id) "
                    + "values (?, ?, 'RECEIVABLE', ?)", institution, UUID.randomUUID(), receivable);
            exec(c, "insert into charges_posting_account (institution_id, id, purpose, ledger_account_id) "
                    + "values (?, ?, 'REVENUE', ?)", institution, UUID.randomUUID(), revenue);
            exec(c, "insert into charges_posting_account (institution_id, id, purpose, ledger_account_id) "
                    + "values (?, ?, 'TAX_PAYABLE', ?)", institution, UUID.randomUUID(), taxAccount);
            exec(c, "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, target_ref_id, "
                    + "frequency, due_day_of_month) values (?, ?, ?, 'Primaria 2027', 'GRADE', ?, 'MONTHLY', 5)",
                    institution, plan, year, grade);
            exec(c, "insert into charges_plan_item (institution_id, id, plan_id, payment_concept_id, periods_count, "
                    + "first_period_on) values (?, ?, ?, ?, 3, date '2027-02-01')", institution, UUID.randomUUID(), plan,
                    concept);
            if (concepts != Concepts.ONE) {
                UUID second = UUID.randomUUID();
                exec(c, "insert into catalog_payment_concept (institution_id, id, code, name, category, is_taxable, "
                        + "is_recurring, tax_rate_id) values (?, ?, 'COL2', 'Colegiatura 2', 'TUITION', true, true, ?)",
                        institution, second, rate);
                exec(c, "insert into catalog_price_list_item (institution_id, id, price_list_id, payment_concept_id, "
                        + "amount, currency, valid_from, created_by) values (?, ?, ?, ?, ?::numeric, ?, date '2027-01-01', ?)",
                        institution, UUID.randomUUID(), list, second, price, currency, admin);
                UUID itemPlan = plan;
                if (concepts == Concepts.TWO_IN_TWO_PLANS) {
                    itemPlan = UUID.randomUUID();
                    exec(c, "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, target_ref_id, "
                            + "frequency, due_day_of_month) values (?, ?, ?, 'Plan B', 'GRADE', ?, 'MONTHLY', 5)",
                            institution, itemPlan, year, grade);
                }
                exec(c, "insert into charges_plan_item (institution_id, id, plan_id, payment_concept_id, periods_count, "
                        + "first_period_on) values (?, ?, ?, ?, 3, date '2027-02-01')", institution, UUID.randomUUID(),
                        itemPlan, second);
                if (concepts == Concepts.TWO_IN_TWO_PLANS) {
                    exec(c, "update charges_plan set status = 'ACTIVE' where institution_id = ? and id = ?", institution, itemPlan);
                }
            }
            exec(c, "update charges_plan set status = 'ACTIVE' where institution_id = ? and id = ?", institution, plan);
            for (int i = 0; i < students; i++) {
                UUID student = UUID.randomUUID();
                seeded.add(student);
                exec(c, "insert into students_student (institution_id, id, student_code, first_name, last_name, "
                        + "birth_date, sex) values (?, ?, ?, 'Ana', 'Prueba', date '2015-01-01', 'F')", institution,
                        student, "E-" + student.toString().substring(0, 8));
                exec(c, "insert into students_enrollment (institution_id, id, student_id, section_id, academic_year_id, "
                        + "enrolled_on) values (?, ?, ?, ?, ?, date '2027-01-10')", institution, UUID.randomUUID(),
                        student, section, year);
            }
            c.commit();
        }
        return new Env(institution, plan, concept, receivable, revenue, taxAccount, seeded, admin);
    }
}

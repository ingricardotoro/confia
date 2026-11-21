package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.catalog.infrastructure.JooqPricingDirectory;
import com.confia.charges.domain.AccrualRunInProgressException;
import com.confia.charges.infrastructure.JobAccrualRunScheduler;
import com.confia.charges.infrastructure.JooqAccrualRunItemRepository;
import com.confia.charges.infrastructure.JooqAccrualRunRepository;
import com.confia.charges.infrastructure.JooqChargePlanItemRepository;
import com.confia.charges.infrastructure.JooqChargePlanRepository;
import com.confia.charges.infrastructure.JooqChargeRepository;
import com.confia.charges.infrastructure.JooqPostingAccountRepository;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.ledger.application.LedgerApi;
import com.confia.ledger.application.LedgerPosting;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.ledger.infrastructure.JooqLedgerRepository;
import com.confia.organization.infrastructure.JooqPlacementDirectory;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The accrual engine against a real PostgreSQL (docs/cortes/charges-accrual.md): the preview, the request of a run with its
 * job, the execution that the worker performs, the idempotency of a second run, the partial failure of one student, the rules
 * that the database enforces by itself to every role (INV-43 to INV-48) and row-level security. Accounts, prices and tax rates
 * are fictional.
 */
class AccrualEngineIT extends CommittingPostgresIntegrationTest {

    private static final LocalDate FEBRUARY = LocalDate.parse("2027-02-01");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-02-01T18:00:00Z"), ZoneOffset.UTC);
    private static final String TASK_NAME = "charges.accrual-run";

    /** What a test needs of the institution it seeded. */
    private record Env(UUID institution, UUID year, UUID modality, UUID grade, UUID section, UUID plan, UUID planItem,
            UUID concept, UUID taxRate, UUID taxPeriod, UUID priceList, UUID priceItem, UUID receivable, UUID revenue,
            UUID taxAccount, List<UUID> students, UUID admin) {

        AuthenticatedActor actor() {
            return new AuthenticatedActor(admin, institution, UUID.randomUUID(), UUID.randomUUID(),
                    Set.of(AuthenticationMethod.PASSWORD), CLOCK.instant(), CLOCK.instant().plusSeconds(900));
        }

        SecurityContext system() {
            return new SecurityContext("", "system", institution.toString(), UUID.randomUUID().toString());
        }
    }

    /** The seed knobs: the students, whether the concept is taxable, whether it has a rate and a price. */
    private record Seed(int students, boolean taxable, boolean taxRate, boolean price, String amount, String percent) {

        static Seed standard(int students) {
            return new Seed(students, true, true, true, "1500.00", "10");
        }
    }

    // --- Wiring of the real services over the real database ---

    private LedgerPostingApi ledger() {
        JooqLedgerRepository repository = new JooqLedgerRepository(dsl);
        return new LedgerApi(new LedgerPosting(transactionRunner(), repository, new JooqAuditLogWriter(dsl)),
                repository);
    }

    private ManageAccrual manage() {
        return new ManageAccrual(transactionRunner(), new JooqChargePlanRepository(dsl),
                new JooqChargePlanItemRepository(dsl), new JooqAccrualRunRepository(dsl),
                new JooqAccrualRunItemRepository(dsl), new JooqChargeRepository(dsl), new JooqPlacementDirectory(dsl),
                new JooqEnrollmentRoster(dsl), new JooqStudentDirectory(dsl), new JooqPricingDirectory(dsl),
                new JooqPostingAccountRepository(dsl), (LedgerApiHolder.accounts(ledger())),
                new JobAccrualRunScheduler(
                        new DbSchedulerJobScheduler(new TransactionalDataSource(dataSource()), new TracedJobSerializer()),
                        CLOCK),
                new JooqAuditLogWriter(dsl), CLOCK);
    }

    private ExecuteAccrualRun execution(LedgerPostingApi ledger) {
        com.confia.ledger.application.api.LedgerAccountDirectory directory = LedgerApiHolder.accounts(ledger());
        return new ExecuteAccrualRun(transactionRunner(), new JooqChargePlanRepository(dsl),
                new JooqChargePlanItemRepository(dsl), new JooqAccrualRunRepository(dsl),
                new JooqAccrualRunItemRepository(dsl), new JooqChargeRepository(dsl), new JooqPlacementDirectory(dsl),
                new JooqEnrollmentRoster(dsl), new JooqStudentDirectory(dsl), new JooqPricingDirectory(dsl),
                new JooqPostingAccountRepository(dsl), directory, ledger,
                new JooqAuditLogWriter(dsl), CLOCK);
    }

    private ExecuteAccrualRun execution() {
        return execution(ledger());
    }

    /** The ledger service is both the posting API and the account directory. */
    private static final class LedgerApiHolder {

        static com.confia.ledger.application.api.LedgerAccountDirectory accounts(LedgerPostingApi ledger) {
            return (com.confia.ledger.application.api.LedgerAccountDirectory) ledger;
        }
    }

    private AccrualRunView request(Env env, String key, LocalDate period, String digest) {
        return manage().request(env.actor(), UUID.randomUUID().toString(), key, env.plan(), period, digest);
    }

    private AccrualRunView runAndExecute(Env env, String key) {
        AccrualRunView run = request(env, key, FEBRUARY, null);
        execution().execute(env.system(), run.id());
        return get(env, run.id());
    }

    private AccrualRunView get(Env env, UUID runId) {
        return manage().get(env.actor(), UUID.randomUUID().toString(), runId, 0, 200).run();
    }

    // --- Seeding as the owner (bound by row-level security and by every trigger) ---

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private static long scalar(UUID institution, String sql, Object... parameters) throws SQLException {
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

    private static BigDecimal decimal(UUID institution, String sql, Object... parameters) throws SQLException {
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

    private static void student(Connection c, UUID institution, UUID id) throws SQLException {
        exec(c, "insert into students_student (institution_id, id, student_code, first_name, last_name, birth_date, sex) "
                + "values (?, ?, ?, 'Ana', 'Prueba', date '2015-01-01', 'F')", institution, id,
                "E-" + id.toString().substring(0, 8));
    }

    private static void enroll(Connection c, Env env, UUID student, String enrolledOn) throws SQLException {
        exec(c, "insert into students_enrollment (institution_id, id, student_id, section_id, academic_year_id, "
                + "enrolled_on) values (?, ?, ?, ?, ?, ?::date)", env.institution(), UUID.randomUUID(), student,
                env.section(), env.year(), enrolledOn);
    }

    private static void account(Connection c, UUID institution, UUID id, String code, String type, boolean student)
            throws SQLException {
        exec(c, "insert into ledger_account (institution_id, id, code, name, account_type, holds_student_balances) "
                + "values (?, ?, ?, 'Cuenta de prueba', ?, ?)", institution, id, code, type, student);
    }

    private Env seed(Seed seed) throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        UUID section = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID planItem = UUID.randomUUID();
        UUID concept = UUID.randomUUID();
        UUID taxRate = UUID.randomUUID();
        UUID taxPeriod = UUID.randomUUID();
        UUID priceList = UUID.randomUUID();
        UUID priceItem = UUID.randomUUID();
        UUID receivable = UUID.randomUUID();
        UUID revenue = UUID.randomUUID();
        UUID taxAccount = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        List<UUID> students = new ArrayList<>();
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
            if (seed.taxRate()) {
                exec(c, "insert into catalog_tax_rate (institution_id, id, code, name) values (?, ?, 'TAXA', 'Tasa ficticia')",
                        institution, taxRate);
                exec(c, "insert into catalog_tax_rate_period (institution_id, id, tax_rate_id, rate_percent, valid_from, "
                        + "created_by) values (?, ?, ?, ?::numeric, date '2027-01-01', ?)", institution, taxPeriod,
                        taxRate, seed.percent(), admin);
            }
            exec(c, "insert into catalog_payment_concept (institution_id, id, code, name, category, is_taxable, "
                    + "is_recurring, tax_rate_id) values (?, ?, 'COL', 'Colegiatura', 'TUITION', ?, true, ?)",
                    institution, concept, seed.taxable(), seed.taxRate() ? taxRate : null);
            exec(c, "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope) "
                    + "values (?, ?, ?, 'General 2027', 'INSTITUTION')", institution, priceList, year);
            if (seed.price()) {
                exec(c, "insert into catalog_price_list_item (institution_id, id, price_list_id, payment_concept_id, "
                        + "amount, currency, valid_from, created_by) values (?, ?, ?, ?, ?::numeric, 'HNL', "
                        + "date '2027-01-01', ?)", institution, priceItem, priceList, concept, seed.amount(), admin);
            }
            account(c, institution, receivable, "1105", "ASSET", true);
            account(c, institution, revenue, "4105", "INCOME", false);
            account(c, institution, taxAccount, "2105", "LIABILITY", false);
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
                    + "first_period_on) values (?, ?, ?, ?, 3, date '2027-02-01')", institution, planItem, plan, concept);
            exec(c, "update charges_plan set status = 'ACTIVE' where institution_id = ? and id = ?", institution, plan);
            Env env = new Env(institution, year, modality, grade, section, plan, planItem, concept, taxRate, taxPeriod,
                    priceList, priceItem, receivable, revenue, taxAccount, students, admin);
            for (int i = 0; i < seed.students(); i++) {
                UUID id = UUID.randomUUID();
                students.add(id);
                student(c, institution, id);
                enroll(c, env, id, "2027-01-10");
            }
            c.commit();
            return env;
        }
    }

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private long charges(Env env) throws SQLException {
        return scalar(env.institution(), "select count(*) from charges_charge where institution_id = ?",
                env.institution());
    }

    private long items(Env env, UUID run, String outcome) throws SQLException {
        return scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and run_id = ? and outcome = ?", env.institution(), run, outcome);
    }

    // --- The acceptance criterion ---

    @Test
    void accruesThirtyStudentsRerunsWithZeroDuplicatesAndChargesTheLateEnrollmentOnTheThirdRun() throws SQLException {
        Env env = seed(Seed.standard(30));

        AccrualPreviewResult preview = manage().preview(env.actor(), UUID.randomUUID().toString(), env.plan(), FEBRUARY);
        assertThat(preview.studentsEvaluated()).isEqualTo(30);
        assertThat(preview.toCreate()).isEqualTo(30);
        assertThat(preview.blockers()).isEmpty();
        assertThat(preview.totals()).hasSize(1);
        assertThat(preview.totals().get(0).base()).isEqualTo(hnl("45000.00"));
        assertThat(preview.totals().get(0).tax()).isEqualTo(hnl("4500.00"));
        assertThat(preview.totals().get(0).total()).isEqualTo(hnl("49500.00"));
        assertThat(charges(env)).as("a preview writes nothing").isZero();

        AccrualRunView queued = request(env, "key-feb-1", FEBRUARY, preview.previewDigest());
        assertThat(queued.status()).isEqualTo("QUEUED");
        assertThat(scalar(env.institution(), "select count(*) from scheduled_tasks where task_name = ?::text "
                + "and task_instance is not null", TASK_NAME)).as("the job was scheduled with the run").isEqualTo(1);

        execution().execute(env.system(), queued.id());
        AccrualRunView first = get(env, queued.id());
        assertThat(first.status()).isEqualTo("COMPLETED");
        assertThat(first.studentsEvaluated()).isEqualTo(30);
        assertThat(first.chargesCreated()).isEqualTo(30);
        assertThat(first.chargesExisting()).isZero();
        assertThat(first.chargesFailed()).isZero();
        assertThat(charges(env)).isEqualTo(30);
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where institution_id = ? "
                + "and kind = 'CHARGE' and source_module = 'charges'", env.institution())).isEqualTo(30);
        assertThat(scalar(env.institution(), "select count(*) from ledger_entry where institution_id = ?",
                env.institution())).as("debit, revenue and tax credit per charge").isEqualTo(90);

        // The balance of each student, derived from the ledger, is the total of its charge: 1,650.00.
        assertThat(decimal(env.institution(), "select sum(amount) from ledger_entry where institution_id = ? "
                + "and account_id = ? and side = 'DEBIT'", env.institution(), env.receivable()))
                        .isEqualByComparingTo("49500.00");
        assertThat(decimal(env.institution(), "select sum(amount) from ledger_entry where institution_id = ? "
                + "and account_id = ? and side = 'CREDIT'", env.institution(), env.revenue()))
                        .isEqualByComparingTo("45000.00");
        assertThat(decimal(env.institution(), "select sum(amount) from ledger_entry where institution_id = ? "
                + "and account_id = ? and side = 'CREDIT'", env.institution(), env.taxAccount()))
                        .isEqualByComparingTo("4500.00");

        // A second run with another key creates nothing and finds all of them.
        AccrualRunView second = runAndExecute(env, "key-feb-2");
        assertThat(second.status()).isEqualTo("COMPLETED");
        assertThat(second.chargesCreated()).isZero();
        assertThat(second.chargesExisting()).isEqualTo(30);
        assertThat(charges(env)).as("zero duplicates").isEqualTo(30);
        assertThat(items(env, second.id(), "ALREADY_ACCRUED")).isEqualTo(30);

        // A student enrolled later is charged by the third run without touching the others.
        UUID late = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            student(c, env.institution(), late);
            enroll(c, env, late, "2027-02-15");
            c.commit();
        }
        AccrualRunView third = runAndExecute(env, "key-feb-3");
        assertThat(third.chargesCreated()).isEqualTo(1);
        assertThat(third.chargesExisting()).isEqualTo(30);
        assertThat(charges(env)).isEqualTo(31);
        assertThat(decimal(env.institution(), "select sum(total_amount) from charges_charge where institution_id = ? "
                + "and student_id = ?", env.institution(), late)).isEqualByComparingTo("1650.00");
    }

    @Test
    void theFullPeriodIsChargedToAStudentEnrolledAtTheEndOfTheMonthAndNotToOneWithdrawnBefore() throws SQLException {
        Env env = seed(Seed.standard(1));
        UUID lateInMonth = UUID.randomUUID();
        UUID leftBefore = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            student(c, env.institution(), lateInMonth);
            enroll(c, env, lateInMonth, "2027-02-28");
            student(c, env.institution(), leftBefore);
            enroll(c, env, leftBefore, "2027-01-10");
            exec(c, "update students_enrollment set status = 'WITHDRAWN', withdrawn_on = date '2027-01-31', "
                    + "withdrawal_reason = 'moved away' where institution_id = ? and student_id = ?",
                    env.institution(), leftBefore);
            c.commit();
        }

        AccrualRunView run = runAndExecute(env, "key-1");

        assertThat(run.chargesCreated()).isEqualTo(2);
        assertThat(decimal(env.institution(), "select total_amount from charges_charge where institution_id = ? "
                + "and student_id = ?", env.institution(), lateInMonth)).as("no proration").isEqualByComparingTo("1650.00");
        assertThat(scalar(env.institution(), "select count(*) from charges_charge where institution_id = ? "
                + "and student_id = ?", env.institution(), leftBefore)).isZero();
    }

    // --- Errors of the charge are recorded and never a silent default ---

    @Test
    void aConceptWithoutAPriceIsAnErrorOfTheChargeAndTheRunCompletesWithErrorsAndCreatesNothing() throws SQLException {
        Env env = seed(new Seed(2, true, true, false, "1500.00", "10"));

        AccrualRunView run = runAndExecute(env, "key-1");

        assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.chargesFailed()).isEqualTo(2);
        assertThat(charges(env)).isZero();
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and error_code = 'NO_PRICE'", env.institution())).isEqualTo(2);
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where institution_id = ?",
                env.institution())).as("nothing was posted").isZero();
    }

    @Test
    void aTaxableConceptWithoutATaxRateIsAnErrorAndNeverZeroPercent() throws SQLException {
        Env env = seed(new Seed(1, true, false, true, "1500.00", "10"));

        AccrualRunView run = runAndExecute(env, "key-1");

        assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(charges(env)).isZero();
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and error_code = 'TAX_RATE_MISSING'", env.institution())).isEqualTo(1);
    }

    @Test
    void twoActivePriceListsOfTheSameLevelAreAmbiguousAndNeverChosen() throws SQLException {
        Env env = seed(Seed.standard(1));
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            UUID otherList = UUID.randomUUID();
            exec(c, "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope) "
                    + "values (?, ?, ?, 'Otra 2027', 'INSTITUTION')", env.institution(), otherList, env.year());
            exec(c, "insert into catalog_price_list_item (institution_id, id, price_list_id, payment_concept_id, amount, "
                    + "currency, valid_from, created_by) values (?, ?, ?, ?, 1600.00, 'HNL', date '2027-01-01', ?)",
                    env.institution(), UUID.randomUUID(), otherList, env.concept(), env.admin());
            c.commit();
        }

        AccrualRunView run = runAndExecute(env, "key-1");

        assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and error_code = 'AMBIGUOUS_PRICE'", env.institution())).isEqualTo(1);
    }

    @Test
    void aPriceChangedInTheMiddleOfTheYearGivesEachPeriodItsOwnValidity() throws SQLException {
        Env env = seed(Seed.standard(1));
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "update catalog_price_list_item set valid_to = date '2027-02-28' where institution_id = ? and id = ?",
                    env.institution(), env.priceItem());
            exec(c, "insert into catalog_price_list_item (institution_id, id, price_list_id, payment_concept_id, amount, "
                    + "currency, valid_from, created_by) values (?, ?, ?, ?, 1800.00, 'HNL', date '2027-03-01', ?)",
                    env.institution(), UUID.randomUUID(), env.priceList(), env.concept(), env.admin());
            c.commit();
        }

        runAndExecute(env, "key-feb");
        AccrualRunView march = manage().request(env.actor(), UUID.randomUUID().toString(), "key-mar", env.plan(),
                LocalDate.parse("2027-03-01"), null);
        Clock march1 = Clock.fixed(Instant.parse("2027-03-01T18:00:00Z"), ZoneOffset.UTC);
        new ExecuteAccrualRun(transactionRunner(), new JooqChargePlanRepository(dsl),
                new JooqChargePlanItemRepository(dsl), new JooqAccrualRunRepository(dsl),
                new JooqAccrualRunItemRepository(dsl), new JooqChargeRepository(dsl), new JooqPlacementDirectory(dsl),
                new JooqEnrollmentRoster(dsl), new JooqStudentDirectory(dsl), new JooqPricingDirectory(dsl),
                new JooqPostingAccountRepository(dsl), LedgerApiHolder.accounts(ledger()), ledger(),
                new JooqAuditLogWriter(dsl), march1).execute(env.system(), march.id());

        assertThat(decimal(env.institution(), "select base_amount from charges_charge where institution_id = ? "
                + "and period_label = '2027-02'", env.institution())).isEqualByComparingTo("1500.00");
        assertThat(decimal(env.institution(), "select base_amount from charges_charge where institution_id = ? "
                + "and period_label = '2027-03'", env.institution())).isEqualByComparingTo("1800.00");
        assertThat(decimal(env.institution(), "select tax_amount from charges_charge where institution_id = ? "
                + "and period_label = '2027-03'", env.institution())).isEqualByComparingTo("180.00");
    }

    // --- Preconditions: nothing is written ---

    @Test
    void aPeriodInTheFutureInTheTimeZoneOfTheInstitutionIsRejectedWithNothingWritten() throws SQLException {
        Env env = seed(Seed.standard(2));
        AccrualRunView run = request(env, "key-1", LocalDate.parse("2027-03-01"), null);

        execution().execute(env.system(), run.id());

        AccrualRunView after = get(env, run.id());
        assertThat(after.status()).isEqualTo("REJECTED");
        assertThat(after.failureCode()).isEqualTo("period-in-future");
        assertThat(charges(env)).isZero();
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where institution_id = ?",
                env.institution())).isZero();
    }

    @Test
    void anInactiveTaxAccountMakesThePostingMapIncompleteAndTheRunIsRejectedWithNothingWritten() throws SQLException {
        Env env = seed(Seed.standard(1));
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "update ledger_account set is_active = false where institution_id = ? and id = ?",
                    env.institution(), env.taxAccount());
            c.commit();
        }
        AccrualRunView run = request(env, "key-1", FEBRUARY, null);

        execution().execute(env.system(), run.id());

        AccrualRunView after = get(env, run.id());
        assertThat(after.status()).isEqualTo("REJECTED");
        assertThat(after.failureCode()).isEqualTo("posting-accounts-incomplete");
        assertThat(charges(env)).isZero();
        AccrualPreviewResult preview = manage().preview(env.actor(), UUID.randomUUID().toString(), env.plan(), FEBRUARY);
        assertThat(preview.blockers()).extracting(blocker -> blocker.code()).contains("posting-accounts-incomplete");
    }

    @Test
    void aPreviewThatIsOutdatedWhenTheRunStartsIsRejectedWithNothingWritten() throws SQLException {
        Env env = seed(Seed.standard(2));
        AccrualPreviewResult preview = manage().preview(env.actor(), UUID.randomUUID().toString(), env.plan(), FEBRUARY);
        // A third student is enrolled between the preview and the confirmation: the calculation changed.
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            UUID extra = UUID.randomUUID();
            student(c, env.institution(), extra);
            enroll(c, env, extra, "2027-01-20");
            c.commit();
        }
        AccrualRunView run = request(env, "key-1", FEBRUARY, preview.previewDigest());

        execution().execute(env.system(), run.id());

        AccrualRunView after = get(env, run.id());
        assertThat(after.status()).isEqualTo("REJECTED");
        assertThat(after.failureCode()).isEqualTo("preview-outdated");
        assertThat(charges(env)).isZero();
    }

    // --- Idempotency, concurrency and partial failure ---

    @Test
    void theSameKeyAndBodyFindTheSameRunAndAnotherBodyIsRefused() throws SQLException {
        Env env = seed(Seed.standard(1));

        AccrualRunView first = request(env, "key-1", FEBRUARY, null);
        AccrualRunView replay = request(env, "key-1", FEBRUARY, null);

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run where institution_id = ?",
                env.institution())).isEqualTo(1);
        assertThat(scalar(env.institution(), "select count(*) from scheduled_tasks where task_name = ?::text",
                TASK_NAME)).isEqualTo(1);
        assertThatThrownBy(() -> request(env, "key-1", LocalDate.parse("2027-03-01"), null))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> request(env, "key-2", FEBRUARY, null)).isInstanceOf(AccrualRunInProgressException.class);
    }

    @Test
    void theKeyOfAFinishedRunStillReturnsItAfterwards() throws SQLException {
        Env env = seed(Seed.standard(1));
        AccrualRunView finished = runAndExecute(env, "key-1");

        AccrualRunView again = request(env, "key-1", FEBRUARY, null);

        assertThat(again.id()).isEqualTo(finished.id());
        assertThat(again.status()).isEqualTo("COMPLETED");
        assertThat(charges(env)).isEqualTo(1);
    }

    @Test
    void twoSimultaneousRequestsWithDifferentKeysQueueOneRunAndRefuseTheOther() throws Exception {
        Env env = seed(Seed.standard(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (String key : List.of("key-a", "key-b")) {
                Callable<Object> task = () -> {
                    start.await();
                    try {
                        return request(env, key, FEBRUARY, null);
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            assertThat(results.stream().filter(AccrualRunView.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(AccrualRunInProgressException.class::isInstance)).hasSize(1);
            assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run where institution_id = ?",
                    env.institution())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoSimultaneousRequestsWithTheSameKeyAnswerTheSameRun() throws Exception {
        Env env = seed(Seed.standard(1));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AccrualRunView>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return request(env, "same-key", FEBRUARY, null);
                }));
            }
            start.countDown();
            assertThat(futures.get(0).get().id()).isEqualTo(futures.get(1).get().id());
            assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run where institution_id = ?",
                    env.institution())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void executingTheSameRunTwiceChangesNothing() throws SQLException {
        Env env = seed(Seed.standard(3));
        AccrualRunView run = request(env, "key-1", FEBRUARY, null);

        execution().execute(env.system(), run.id());
        long ledgerEntries = scalar(env.institution(), "select count(*) from ledger_entry where institution_id = ?",
                env.institution());
        execution().execute(env.system(), run.id());

        assertThat(charges(env)).isEqualTo(3);
        assertThat(scalar(env.institution(), "select count(*) from ledger_entry where institution_id = ?",
                env.institution())).isEqualTo(ledgerEntries);
        assertThat(get(env, run.id()).chargesCreated()).isEqualTo(3);
    }

    @Test
    void aFailureOfOneStudentRollsBackThatStudentWholeAndTheNextRunCreatesOnlyWhatIsMissing() throws SQLException {
        Env env = seed(Seed.standard(3));
        LedgerPostingApi real = ledger();
        AtomicInteger calls = new AtomicInteger();
        LedgerPostingApi failingOnTheSecondPost = new LedgerPostingApi() {
            @Override
            public Posted postCharge(Actor actor, ChargePosting posting) {
                if (calls.incrementAndGet() == 2) {
                    // The ledger transaction of this student is posted and then the unit fails: both must disappear.
                    real.postCharge(actor, posting);
                    throw new IllegalStateException("simulated failure after the posting");
                }
                return real.postCharge(actor, posting);
            }

            @Override
            public Posted postPayment(Actor actor, PaymentPosting posting) {
                return real.postPayment(actor, posting);
            }

            @Override
            public List<LockedAccount> lockStudentAccounts(Actor actor, java.util.Collection<AccountKey> keys) {
                return real.lockStudentAccounts(actor, keys);
            }

            @Override
            public Posted reverseCharge(Actor actor, ChargeReversal reversal) {
                return real.reverseCharge(actor, reversal);
            }
        };
        AccrualRunView run = request(env, "key-1", FEBRUARY, null);

        execution(failingOnTheSecondPost).execute(env.system(), run.id());

        AccrualRunView after = get(env, run.id());
        assertThat(after.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(after.chargesCreated()).isEqualTo(2);
        assertThat(after.chargesFailed()).isEqualTo(1);
        assertThat(charges(env)).isEqualTo(2);
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where institution_id = ? "
                + "and kind = 'CHARGE'", env.institution())).as("the failed student left no ledger transaction")
                        .isEqualTo(2);
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and error_code = 'POSTING_FAILED'", env.institution())).isEqualTo(1);

        AccrualRunView repair = runAndExecute(env, "key-2");
        assertThat(repair.chargesCreated()).isEqualTo(1);
        assertThat(repair.chargesExisting()).isEqualTo(2);
        assertThat(charges(env)).isEqualTo(3);
    }

    @Test
    void aCrashedRunResumesFromTheStudentsItFinished() throws SQLException {
        Env env = seed(Seed.standard(3));
        LedgerPostingApi real = ledger();
        AtomicInteger calls = new AtomicInteger();
        LedgerPostingApi crashing = new LedgerPostingApi() {
            @Override
            public Posted postCharge(Actor actor, ChargePosting posting) {
                if (calls.incrementAndGet() == 2) {
                    throw new Error("simulated crash of the process");
                }
                return real.postCharge(actor, posting);
            }

            @Override
            public Posted postPayment(Actor actor, PaymentPosting posting) {
                return real.postPayment(actor, posting);
            }

            @Override
            public List<LockedAccount> lockStudentAccounts(Actor actor, java.util.Collection<AccountKey> keys) {
                return real.lockStudentAccounts(actor, keys);
            }

            @Override
            public Posted reverseCharge(Actor actor, ChargeReversal reversal) {
                return real.reverseCharge(actor, reversal);
            }
        };
        AccrualRunView run = request(env, "key-1", FEBRUARY, null);

        assertThatThrownBy(() -> execution(crashing).execute(env.system(), run.id())).isInstanceOf(Error.class);
        assertThat(get(env, run.id()).status()).isEqualTo("RUNNING");
        assertThat(charges(env)).isEqualTo(1);

        execution().execute(env.system(), run.id());

        AccrualRunView after = get(env, run.id());
        assertThat(after.status()).isEqualTo("COMPLETED");
        assertThat(after.chargesCreated()).isEqualTo(3);
        assertThat(charges(env)).isEqualTo(3);
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where institution_id = ? "
                + "and kind = 'CHARGE'", env.institution())).isEqualTo(3);
    }

    @Test
    void anAccountUnderReviewIsAnErrorOfTheChargeAndTheOthersAreCreated() throws SQLException {
        Env env = seed(Seed.standard(2));
        UUID flagged = env.students().get(0);
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "insert into ledger_student_account (institution_id, id, student_id, currency, status) "
                    + "values (?, ?, ?, 'HNL', 'UNDER_REVIEW')", env.institution(), UUID.randomUUID(), flagged);
            c.commit();
        }

        AccrualRunView run = runAndExecute(env, "key-1");

        assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(run.chargesCreated()).isEqualTo(1);
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and error_code = 'ACCOUNT_UNDER_REVIEW' and student_id = ?", env.institution(), flagged)).isEqualTo(1);
    }

    @Test
    void twoPlansOverTheSameStudentAndConceptChargeOnceAndTheSecondRunFindsTheFirstChargeOfTheOtherPlan()
            throws SQLException {
        Env env = seed(Seed.standard(1));
        UUID otherPlan = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, target_ref_id, "
                    + "frequency, due_day_of_month) values (?, ?, ?, 'Segundo plan', 'GRADE', ?, 'BIMONTHLY', 10)",
                    env.institution(), otherPlan, env.year(), env.grade());
            exec(c, "insert into charges_plan_item (institution_id, id, plan_id, payment_concept_id, periods_count, "
                    + "first_period_on) values (?, ?, ?, ?, 2, date '2027-02-01')", env.institution(), UUID.randomUUID(),
                    otherPlan, env.concept());
            exec(c, "update charges_plan set status = 'ACTIVE' where institution_id = ? and id = ?", env.institution(),
                    otherPlan);
            c.commit();
        }
        runAndExecute(env, "key-1");

        AccrualRunView overlapping = manage().request(env.actor(), UUID.randomUUID().toString(), "key-2", otherPlan,
                FEBRUARY, null);
        execution().execute(env.system(), overlapping.id());

        AccrualRunView after = get(env, overlapping.id());
        assertThat(after.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(after.chargesCreated()).isZero();
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and run_id = ? and error_code = 'OVERLAPPING_CHARGE'", env.institution(), overlapping.id()))
                        .as("another period that overlaps is an error, never a second charge").isEqualTo(1);
        assertThat(charges(env)).isEqualTo(1);
    }

    @Test
    void aSecondPlanWithTheSamePeriodFindsTheChargeOfTheFirstPlan() throws SQLException {
        Env env = seed(Seed.standard(1));
        UUID otherPlan = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, target_ref_id, "
                    + "frequency, due_day_of_month) values (?, ?, ?, 'Segundo plan', 'MODALITY', ?, 'MONTHLY', 10)",
                    env.institution(), otherPlan, env.year(), env.modality());
            exec(c, "insert into charges_plan_item (institution_id, id, plan_id, payment_concept_id, periods_count, "
                    + "first_period_on) values (?, ?, ?, ?, 1, date '2027-02-01')", env.institution(), UUID.randomUUID(),
                    otherPlan, env.concept());
            exec(c, "update charges_plan set status = 'ACTIVE' where institution_id = ? and id = ?", env.institution(),
                    otherPlan);
            c.commit();
        }
        runAndExecute(env, "key-1");

        AccrualRunView second = manage().request(env.actor(), UUID.randomUUID().toString(), "key-2", otherPlan,
                FEBRUARY, null);
        execution().execute(env.system(), second.id());

        assertThat(items(env, second.id(), "ALREADY_ACCRUED_OTHER_PLAN")).isEqualTo(1);
        assertThat(charges(env)).isEqualTo(1);
    }

    @Test
    void aVoidedOrDeactivatedConceptIsAnErrorOfTheCharge() throws SQLException {
        Env env = seed(Seed.standard(1));
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "update catalog_payment_concept set is_active = false where institution_id = ? and id = ?",
                    env.institution(), env.concept());
            c.commit();
        }

        AccrualRunView run = runAndExecute(env, "key-1");

        assertThat(run.status()).isEqualTo("COMPLETED_WITH_ERRORS");
        assertThat(scalar(env.institution(), "select count(*) from charges_accrual_run_item where institution_id = ? "
                + "and error_code = 'CONCEPT_INACTIVE'", env.institution())).isEqualTo(1);
    }

    // --- What the database refuses by itself, to every role ---

    private UUID oneCharge(Env env) throws SQLException {
        runAndExecute(env, "key-" + UUID.randomUUID());
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution());
                PreparedStatement s = c.prepareStatement("select id from charges_charge where institution_id = ? limit 1")) {
            s.setObject(1, env.institution());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1, UUID.class);
            }
        }
    }

    @Test
    void theDatabaseRefusesToUpdateDeleteOrTruncateAChargeToTheApplicationAndToTheOwner() throws SQLException {
        Env env = seed(Seed.standard(1));
        oneCharge(env);

        for (String role : new String[] {"confia_owner", "confia_admin_app"}) {
            for (String sql : new String[] {
                    "update charges_charge set base_amount = 1.00 where institution_id = ?",
                    "delete from charges_charge where institution_id = ?",
                    "update charges_accrual_run_item set error_code = 'X' where institution_id = ?",
                    "delete from charges_accrual_run_item where institution_id = ?"}) {
                try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs(role)) {
                    c.setAutoCommit(false);
                    StaffRoleFixtures.fixInstitution(c, env.institution());
                    assertThatThrownBy(() -> exec(c, sql, env.institution())).as(role + ": " + sql)
                            .isInstanceOf(SQLException.class);
                }
            }
            try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs(role)) {
                c.setAutoCommit(false);
                StaffRoleFixtures.fixInstitution(c, env.institution());
                assertThatThrownBy(() -> exec(c, "truncate table charges_charge")).as(role + ": truncate")
                        .isInstanceOf(SQLException.class);
            }
        }
        assertThat(charges(env)).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesASecondAutomaticChargeOfTheSameStudentConceptAndPeriod() throws SQLException {
        Env env = seed(Seed.standard(1));
        oneCharge(env);

        assertThatThrownBy(() -> rawCharge(env, env.students().get(0), "2027-02-01", "2027-02-28", "1500.00", "150.00",
                "1650.00", true)).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23P01"));
        // The same shape with another period that does not overlap is accepted by the database.
        rawCharge(env, env.students().get(0), "2027-03-01", "2027-03-31", "1500.00", "150.00", "1650.00", true);
        assertThat(charges(env)).isEqualTo(2);
        // And one that overlaps a part of an existing period is refused too, whatever the plan or the label.
        assertThatThrownBy(() -> rawCharge(env, env.students().get(0), "2027-02-01", "2027-03-31", "1500.00", "150.00",
                "1650.00", true)).isInstanceOf(SQLException.class)
                        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23P01"));
    }

    @Test
    void theDatabaseRefusesATaxThatIsNotTheSingleRoundingOfTheBaseTimesTheRate() throws SQLException {
        Env env = seed(new Seed(1, true, true, true, "0.05", "10"));

        // 0.05 at 10 % is 0.005: the single HALF_UP rounding is 0.01. 0.00 and 0.02 are refused, 0.01 is accepted.
        for (String wrong : new String[] {"0.00", "0.02"}) {
            assertThatThrownBy(() -> rawCharge(env, env.students().get(0), "2027-02-01", "2027-02-28", "0.05", wrong,
                    new BigDecimal("0.05").add(new BigDecimal(wrong)).toPlainString(), true))
                            .isInstanceOf(SQLException.class)
                            .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
        }
        rawCharge(env, env.students().get(0), "2027-02-01", "2027-02-28", "0.05", "0.01", "0.06", true);
        assertThat(charges(env)).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesAChargeWithoutTheLedgerTransactionThatMatchesIt() throws SQLException {
        Env env = seed(Seed.standard(1));

        // The posting debits less than the total of the charge.
        assertThatThrownBy(() -> rawChargeWithDebit(env, env.students().get(0), "1649.99"))
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
        assertThat(charges(env)).isZero();
    }

    @Test
    void thePriceThatAChargeReferencesCannotBeClosedBeforeItsPricingDate() throws SQLException {
        Env env = seed(Seed.standard(1));
        oneCharge(env);

        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            assertThatThrownBy(() -> exec(c, "update catalog_price_list_item set valid_to = date '2027-01-31' "
                    + "where institution_id = ? and id = ?", env.institution(), env.priceItem()))
                            .isInstanceOf(SQLException.class);
        }
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "update catalog_price_list_item set valid_to = date '2027-02-01' where institution_id = ? and id = ?",
                    env.institution(), env.priceItem());
            c.commit();
        }
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            assertThatThrownBy(() -> exec(c, "update catalog_tax_rate_period set valid_to = date '2027-01-31' "
                    + "where institution_id = ? and id = ?", env.institution(), env.taxPeriod()))
                            .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aTerminalRunNeverChangesAndTheStateMachineHasNoWayBack() throws SQLException {
        Env env = seed(Seed.standard(1));
        AccrualRunView finished = runAndExecute(env, "key-1");

        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(c, env.institution());
            assertThatThrownBy(() -> exec(c, "update charges_accrual_run set status = 'QUEUED' where institution_id = ? "
                    + "and id = ?", env.institution(), finished.id())).isInstanceOf(SQLException.class);
        }
        try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_owner")) {
            c.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(c, env.institution());
            assertThatThrownBy(() -> exec(c, "update charges_accrual_run set charges_created = 99 where "
                    + "institution_id = ? and id = ?", env.institution(), finished.id()))
                            .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> exec(c, "delete from charges_accrual_run where institution_id = ? and id = ?",
                    env.institution(), finished.id())).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void anotherInstitutionSeesNoRunNoChargeAndNoItem() throws SQLException {
        Env env = seed(Seed.standard(2));
        runAndExecute(env, "key-1");
        Env other = seed(Seed.standard(1));

        for (String table : new String[] {"charges_charge", "charges_accrual_run", "charges_accrual_run_item",
                "charges_posting_account"}) {
            try (Connection c = com.confia.support.SharedPostgresContainer.connectionAs("confia_admin_app")) {
                c.setAutoCommit(false);
                StaffRoleFixtures.fixInstitution(c, other.institution());
                try (PreparedStatement s = c.prepareStatement("select count(*) from " + table + " where institution_id = ?")) {
                    s.setObject(1, env.institution());
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        assertThat(rs.getLong(1)).as(table).isZero();
                    }
                }
            }
        }
        assertThat(manage().list(other.actor(), UUID.randomUUID().toString(), null, 0, 50).total()).isZero();
        assertThatThrownBy(() -> manage().get(other.actor(), UUID.randomUUID().toString(), UUID.randomUUID(), 0, 10))
                .isInstanceOf(com.confia.charges.domain.ResourceNotFoundException.class);
        assertThatThrownBy(() -> manage().preview(other.actor(), UUID.randomUUID().toString(), env.plan(), FEBRUARY))
                .isInstanceOf(com.confia.charges.domain.ResourceNotFoundException.class);
    }

    // --- Raw charges, for what only the database can say ---

    private void rawCharge(Env env, UUID student, String start, String end, String base, String tax, String total,
            boolean taxable) throws SQLException {
        rawChargeWithDebit(env, student, start, end, base, tax, total, taxable, total);
    }

    private void rawChargeWithDebit(Env env, UUID student, String debit) throws SQLException {
        rawChargeWithDebit(env, student, "2027-02-01", "2027-02-28", "1500.00", "150.00", "1650.00", true, debit);
    }

    /** One database transaction as the owner: the ledger transaction and then the charge, with the debit asked for. */
    private void rawChargeWithDebit(Env env, UUID student, String start, String end, String base, String tax,
            String total, boolean taxable, String debit) throws SQLException {
        UUID chargeId = UUID.randomUUID();
        UUID transaction = UUID.randomUUID();
        UUID studentAccount = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            // A charge belongs to a run: use the one that exists, or hold the place with a queued one of another period.
            exec(c, "insert into charges_accrual_run (institution_id, id, plan_id, period_start, request_key, request_hash, "
                    + "requested_by) select ?, ?, ?, date '2027-11-01', ?, ?, ? where not exists (select 1 from "
                    + "charges_accrual_run where institution_id = ?)", env.institution(), UUID.randomUUID(), env.plan(),
                    "raw-" + UUID.randomUUID(), "b".repeat(64), env.admin(), env.institution());
            exec(c, "insert into ledger_student_account (institution_id, id, student_id, currency) values (?, ?, ?, 'HNL') "
                    + "on conflict do nothing", env.institution(), studentAccount, student);
            UUID account;
            try (PreparedStatement s = c.prepareStatement("select id from ledger_student_account where institution_id = ? "
                    + "and student_id = ? and currency = 'HNL'")) {
                s.setObject(1, env.institution());
                s.setObject(2, student);
                try (ResultSet rs = s.executeQuery()) {
                    rs.next();
                    account = rs.getObject(1, UUID.class);
                }
            }
            exec(c, "insert into ledger_transaction (institution_id, id, kind, effective_on, posting_xid, source_module, "
                    + "source_ref_id, idempotency_key, payload_hash, created_by) values (?, ?, 'CHARGE', ?::date, 0, "
                    + "'charges', ?, ?, ?, ?)", env.institution(), transaction, start, chargeId,
                    "raw:" + chargeId, "a".repeat(64), env.admin());
            BigDecimal credits = new BigDecimal(debit);
            BigDecimal taxCredit = new BigDecimal(tax);
            exec(c, "insert into ledger_entry (institution_id, id, transaction_id, line_number, account_id, "
                    + "student_account_id, side, amount, currency) values (?, ?, ?, 1, ?, ?, 'DEBIT', ?::numeric, 'HNL')",
                    env.institution(), UUID.randomUUID(), transaction, env.receivable(), account, debit);
            exec(c, "insert into ledger_entry (institution_id, id, transaction_id, line_number, account_id, "
                    + "student_account_id, side, amount, currency) values (?, ?, ?, 2, ?, null, 'CREDIT', ?::numeric, 'HNL')",
                    env.institution(), UUID.randomUUID(), transaction, env.revenue(),
                    credits.subtract(taxCredit).toPlainString());
            if (taxCredit.signum() > 0) {
                exec(c, "insert into ledger_entry (institution_id, id, transaction_id, line_number, account_id, "
                        + "student_account_id, side, amount, currency) values (?, ?, ?, 3, ?, null, 'CREDIT', "
                        + "?::numeric, 'HNL')", env.institution(), UUID.randomUUID(), transaction, env.taxAccount(), tax);
            }
            exec(c, "insert into charges_charge (institution_id, id, student_id, enrollment_id, payment_concept_id, "
                    + "concept_category, plan_id, plan_item_id, accrual_run_id, origin, period_start, period_end, "
                    + "period_label, pricing_date, due_on, price_list_item_id, base_amount, base_currency, "
                    + "discount_amount, discount_currency, tax_amount, tax_currency, total_amount, total_currency, "
                    + "is_taxable, tax_rate_id, tax_rate_period_id, tax_rate_percent, ledger_transaction_id, created_by) "
                    + "values (?, ?, ?, (select id from students_enrollment where institution_id = ? and student_id = ? "
                    + "limit 1), ?, 'TUITION', ?, ?, (select id from charges_accrual_run where institution_id = ? limit 1), "
                    + "'AUTOMATIC', ?::date, ?::date, to_char(?::date, 'YYYY-MM'), ?::date, ?::date, ?, ?::numeric, 'HNL', "
                    + "0, 'HNL', ?::numeric, 'HNL', ?::numeric, 'HNL', ?, ?, ?, ?::numeric, ?, ?)",
                    env.institution(), chargeId, student, env.institution(), student, env.concept(), env.plan(),
                    env.planItem(), env.institution(), start, end, start, start, start, env.priceItem(), base, tax, total,
                    taxable, taxable ? env.taxRate() : null, taxable ? env.taxPeriod() : null,
                    taxable ? "10" : null, transaction, env.admin());
            c.commit();
        }
    }
}

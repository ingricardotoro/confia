package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.ChargesLedgerFixture;
import com.confia.charges.domain.IntegrityDiscrepanciesFoundException;
import com.confia.charges.domain.IntegrityKind;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.shared.jobs.RecurringRun;
import com.confia.support.StaffRoleFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The nightly integrity check against a real PostgreSQL (docs/cortes/charges-void-and-integrity.md): clean data stays OK, a
 * transaction of {@code charges} that no charge explains and a reversal that no void explains are found and named, the
 * result of each night is recorded and audited, a second execution changes nothing financial, one institution never sees or
 * affects another, and the database refuses to edit or delete a result. The charges are the real ones of the accrual engine;
 * the damage is injected through the ledger port, because the database itself refuses to create most of it.
 */
class ChargesIntegrityIT extends ChargesLedgerFixture {

    /** A clock that moves one minute every time it is read, so that the runs have distinct finishing instants. */
    private static final class TickingClock extends Clock {

        private Instant now = Instant.parse("2027-02-11T08:30:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public synchronized Instant instant() {
            now = now.plus(Duration.ofMinutes(1));
            return now;
        }
    }

    private final Clock ticking = new TickingClock();

    private void night(Env env) {
        integrityCheck(ticking).runFor(new InstitutionId(env.institution()),
                new RecurringRun(RunChargesIntegrityCheck.TASK_NAME, UUID.randomUUID(), ticking.instant()));
    }

    private ViewIntegrityRuns.Detail latest(Env env) {
        return viewIntegrityRuns().latest(env.actor(), UUID.randomUUID().toString());
    }

    private long ledgerTransactions(Env env) throws SQLException {
        return scalar(env.institution(), "select count(*) from ledger_transaction where institution_id = ?",
                env.institution());
    }

    @Test
    void cleanDataStaysOkBeforeAndAfterAVoidAndEachNightIsRecordedAndAudited() throws SQLException {
        Env env = seed(2);
        accrueFebruary(env, "accrual-1");

        night(env);
        ViewIntegrityRuns.Detail first = latest(env);
        assertThat(first.run().status()).isEqualTo("OK");
        assertThat(first.run().chargesChecked()).isEqualTo(2);
        assertThat(first.run().postingsChecked()).isEqualTo(2);
        assertThat(first.findings()).isEmpty();

        UUID charge = chargesByStudent(env).get(env.students().get(0));
        voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge, REASON);
        night(env);

        ViewIntegrityRuns.Detail second = latest(env);
        assertThat(second.run().id()).isNotEqualTo(first.run().id());
        assertThat(second.run().status()).isEqualTo("OK");
        assertThat(second.run().chargesChecked()).isEqualTo(2);
        assertThat(second.run().postingsChecked()).as("two charges and one reversal").isEqualTo(3);
        Page<IntegrityRunView> page = viewIntegrityRuns().list(env.actor(),
                UUID.randomUUID().toString(), 0, 10);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items().get(0).id()).as("most recent first").isEqualTo(second.run().id());
        assertThat(viewIntegrityRuns().get(env.actor(), UUID.randomUUID().toString(), first.run().id()).run())
                .isEqualTo(first.run());
        assertThat(scalar(env.institution(), "select count(*) from shared_audit_log where institution_id = ? "
                + "and action = 'charges.integrity_run.completed' and outcome = 'success'", env.institution()))
                        .isEqualTo(2);
    }

    @Test
    void anInstitutionThatHasNeverRunTheCheckHasNoLatestRun() throws SQLException {
        Env env = seed(1);

        assertThatThrownBy(() -> latest(env)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(viewIntegrityRuns().list(env.actor(), UUID.randomUUID().toString(), 0, 10).total()).isZero();
    }

    @Test
    void aTransactionOfChargesThatNoChargeExplainsIsFoundNamedAndRaisedAndTheNextCleanNightIsOkAgain()
            throws SQLException {
        Env env = seed(2);
        accrueFebruary(env, "accrual-1");
        night(env);
        UUID stray = UUID.randomUUID();
        UUID postedStray = postStrayCharge(env, stray, "10.00");

        assertThatThrownBy(() -> night(env)).isInstanceOfSatisfying(IntegrityDiscrepanciesFoundException.class,
                found -> assertThat(found.discrepancies()).isEqualTo(2));

        ViewIntegrityRuns.Detail bad = latest(env);
        assertThat(bad.run().status()).isEqualTo("DISCREPANCIES");
        assertThat(bad.run().discrepancyCount()).isEqualTo(2);
        assertThat(bad.findings()).extracting(IntegrityFindingView::kind)
                .containsExactly(IntegrityKind.ORPHAN_POSTING.name(), IntegrityKind.RECEIVABLE_MISMATCH.name());
        assertThat(bad.findings().get(0).ledgerTransactionId()).isEqualTo(postedStray);
        IntegrityFindingView receivable = bad.findings().get(1);
        assertThat(receivable.expected()).isEqualTo(Money.of("3300.00", CurrencyCode.HNL));
        assertThat(receivable.actual()).isEqualTo(Money.of("3310.00", CurrencyCode.HNL));
        assertThat(scalar(env.institution(), "select count(*) from shared_audit_log where institution_id = ? "
                + "and action = 'charges.integrity_run.completed' and outcome = 'error'", env.institution()))
                        .isEqualTo(1);
        assertThat(scalar(env.institution(), "select count(*) from charges_integrity_discrepancy where run_id = ?",
                bad.run().id())).isEqualTo(2);
    }

    @Test
    void aReversalThatNoVoidExplainsIsFoundAsReversalWithoutVoid() throws SQLException {
        Env env = seed(1);
        accrueFebruary(env, "accrual-1");
        UUID charge = chargesByStudent(env).get(env.students().get(0));
        UUID original = (UUID) scalarObject(env, "select ledger_transaction_id from charges_charge where id = ?", charge);
        transactionRunner().execute(env.system(), () -> ledger().reverseCharge(
                new LedgerPostingApi.Actor(env.institution(), env.admin(), "system", UUID.randomUUID()),
                new LedgerPostingApi.ChargeReversal(original, "charges", LocalDate.parse("2027-02-10"), REASON,
                        "outside-the-void:" + charge)));

        assertThatThrownBy(() -> night(env)).isInstanceOf(IntegrityDiscrepanciesFoundException.class);

        List<IntegrityFindingView> findings = latest(env).findings();
        assertThat(findings).extracting(IntegrityFindingView::kind).containsExactlyInAnyOrder(
                IntegrityKind.REVERSAL_WITHOUT_VOID.name(), IntegrityKind.RECEIVABLE_MISMATCH.name());
        assertThat(findings).filteredOn(finding -> finding.kind().equals(IntegrityKind.REVERSAL_WITHOUT_VOID.name()))
                .singleElement().satisfies(finding -> assertThat(finding.chargeId()).isEqualTo(charge));
    }

    @Test
    void executingTheNightTwiceRecordsTwoRunsAndChangesNothingFinancial() throws SQLException {
        Env env = seed(2);
        accrueFebruary(env, "accrual-1");
        long chargesBefore = scalar(env.institution(), "select count(*) from charges_charge where institution_id = ?",
                env.institution());
        long transactionsBefore = ledgerTransactions(env);

        night(env);
        night(env);

        assertThat(viewIntegrityRuns().list(env.actor(), UUID.randomUUID().toString(), 0, 10).total()).isEqualTo(2);
        assertThat(scalar(env.institution(), "select count(*) from charges_charge where institution_id = ?",
                env.institution())).isEqualTo(chargesBefore);
        assertThat(ledgerTransactions(env)).isEqualTo(transactionsBefore);
    }

    @Test
    void oneInstitutionNeverSeesNorAffectsTheResultsOfAnother() throws SQLException {
        Env mine = seed(1);
        accrueFebruary(mine, "accrual-1");
        Env theirs = seed(1);
        accrueFebruary(theirs, "accrual-1");
        postStrayCharge(mine, UUID.randomUUID(), "10.00");

        night(theirs);
        assertThatThrownBy(() -> night(mine)).isInstanceOf(IntegrityDiscrepanciesFoundException.class);

        assertThat(latest(theirs).run().status()).isEqualTo("OK");
        assertThat(latest(mine).run().status()).isEqualTo("DISCREPANCIES");
        assertThat(viewIntegrityRuns().list(theirs.actor(), UUID.randomUUID().toString(), 0, 10).total()).isEqualTo(1);
        UUID minesRun = latest(mine).run().id();
        assertThatThrownBy(() -> viewIntegrityRuns().get(theirs.actor(), UUID.randomUUID().toString(), minesRun))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(scalar(theirs.institution(), "select count(*) from charges_integrity_discrepancy")).isZero();
    }

    @Test
    void theDatabaseRefusesToEditOrDeleteAResultOfTheNight() throws SQLException {
        Env env = seed(1);
        accrueFebruary(env, "accrual-1");
        postStrayCharge(env, UUID.randomUUID(), "10.00");
        assertThatThrownBy(() -> night(env)).isInstanceOf(IntegrityDiscrepanciesFoundException.class);
        UUID run = latest(env).run().id();

        assertThat(refused(env, "update charges_integrity_run set status = 'OK', discrepancy_count = 0 where id = ?",
                run)).isEqualTo("23001");
        assertThat(refused(env, "delete from charges_integrity_run where id = ?", run)).isEqualTo("23001");
        assertThat(refused(env, "update charges_integrity_discrepancy set kind = 'ORPHAN_POSTING' where run_id = ?",
                run)).isEqualTo("23001");
        assertThat(refused(env, "delete from charges_integrity_discrepancy where run_id = ?", run)).isEqualTo("23001");
        assertThat(refused(env, "truncate table charges_integrity_discrepancy")).isEqualTo("23001");
        // Referenced by the discrepancies, PostgreSQL refuses the truncate itself (0A000) before the trigger would.
        assertThat(refused(env, "truncate table charges_integrity_run")).isIn("23001", "0A000");
        assertThat(refused(env, "insert into charges_integrity_run (institution_id, id, started_at, finished_at, status, "
                + "charges_checked, postings_checked, discrepancy_count) values (?, ?, now(), now(), 'OK', 0, 0, 3)",
                env.institution(), UUID.randomUUID())).as("OK with discrepancies").isEqualTo("23514");
    }

    // --- Helpers ---

    /** Posts, as the ledger would for a charge, a CHARGE transaction of {@code charges} that no charge row explains. */
    private UUID postStrayCharge(Env env, UUID sourceRef, String amount) {
        Money money = Money.of(amount, CurrencyCode.HNL);
        LedgerPostingApi.Actor actor = new LedgerPostingApi.Actor(env.institution(), env.admin(), "system",
                UUID.randomUUID());
        return transactionRunner().execute(env.system(), () -> ledger().postCharge(actor,
                new LedgerPostingApi.ChargePosting(LocalDate.parse("2027-02-01"), "charges", sourceRef,
                        "charges:accrual:" + sourceRef,
                        List.of(new LedgerPostingApi.Line(env.receivable(), env.students().get(0),
                                LedgerPostingApi.Side.DEBIT, money),
                                new LedgerPostingApi.Line(env.revenue(), null, LedgerPostingApi.Side.CREDIT, money)))))
                .transactionId();
    }

    private Object scalarObject(Env env, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution());
                var s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (var rs = s.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }

    /** The SQLState with which the database refused the statement as the owner of the schema, or {@code null} if it ran. */
    private String refused(Env env, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            try {
                exec(c, sql, parameters);
                c.rollback();
                return null;
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        }
    }
}

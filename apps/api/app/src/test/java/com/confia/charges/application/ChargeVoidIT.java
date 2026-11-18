package com.confia.charges.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.charges.ChargesLedgerFixture;
import com.confia.charges.domain.ChargeAlreadyVoidedException;
import com.confia.charges.domain.ChargeNotVoidableException;
import com.confia.charges.domain.IdempotencyKeyRequiredException;
import com.confia.charges.domain.InvalidChargesValueException;
import com.confia.charges.domain.ResourceNotFoundException;
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
 * The void of a charge against a real PostgreSQL (docs/cortes/charges-void-and-integrity.md): the exact mirror in the ledger,
 * the balance, the idempotency, the refusals that leave nothing behind, two simultaneous voids, the rule that a voided charge
 * is not accrued again, the list of the charges of a student and what the database refuses to every role. The charges are the
 * real ones of the accrual engine; accounts, prices and rates are fictional.
 */
class ChargeVoidIT extends ChargesLedgerFixture {

    private long voids(Env env) throws SQLException {
        return scalar(env.institution(), "select count(*) from charges_charge_void where institution_id = ?",
                env.institution());
    }

    private long reversals(Env env) throws SQLException {
        return scalar(env.institution(), "select count(*) from ledger_transaction where institution_id = ? "
                + "and kind = 'REVERSAL'", env.institution());
    }

    private long charges(Env env) throws SQLException {
        return scalar(env.institution(), "select count(*) from charges_charge where institution_id = ?",
                env.institution());
    }

    private long audits(Env env, String action) throws SQLException {
        return scalar(env.institution(), "select count(*) from shared_audit_log where institution_id = ? and action = ?",
                env.institution(), action);
    }

    /** Two accrued students: returns the charge of the first one. */
    private UUID accruedCharge(Env env) throws SQLException {
        assertThat(accrueFebruary(env, "accrual-1").chargesCreated()).isEqualTo(env.students().size());
        return chargesByStudent(env).get(env.students().get(0));
    }

    // --- The void ---

    @Test
    void voidsAChargeWithTheExactMirrorAndTheBalanceOfTheStudentGoesToZeroWhileTheOthersDoNotMove() throws SQLException {
        Env env = seed(2);
        UUID charge = accruedCharge(env);
        UUID first = env.students().get(0);
        UUID second = env.students().get(1);
        assertThat(balanceOf(env, first)).isEqualByComparingTo("1650.00");
        assertThat(balanceOf(env, second)).isEqualByComparingTo("1650.00");

        ChargeVoidView voided = voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge,
                REASON);

        assertThat(voided.chargeId()).isEqualTo(charge);
        assertThat(voided.voidedBy()).isEqualTo(env.admin());
        assertThat(balanceOf(env, first)).isEqualByComparingTo("0.00");
        assertThat(balanceOf(env, second)).as("the other student is untouched").isEqualByComparingTo("1650.00");
        assertThat(voids(env)).isEqualTo(1);
        assertThat(reversals(env)).isEqualTo(1);
        assertThat(charges(env)).as("a charge is never deleted nor added by a void").isEqualTo(2);
        assertThat(decimal(env.institution(), "select total_amount from charges_charge where id = ?", charge))
                .as("the charge itself did not change").isEqualByComparingTo("1650.00");

        UUID original = (UUID) objectOf(env, "select ledger_transaction_id from charges_charge where id = ?", charge);
        UUID reversal = voided.reversalTransactionId();
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where id = ? and kind = 'REVERSAL' "
                + "and source_module = 'charges' and reverses_transaction_id = ? and source_ref_id = ?", reversal,
                original, charge)).isEqualTo(1);
        assertThat(scalar(env.institution(), "select count(*) from ledger_entry o join ledger_entry r "
                + "on r.account_id = o.account_id and r.amount = o.amount and r.currency = o.currency "
                + "and r.side <> o.side where o.transaction_id = ? and r.transaction_id = ?", original, reversal))
                        .as("debit, revenue and tax lines each mirrored on the opposite side").isEqualTo(3);
        assertThat(decimal(env.institution(), "select coalesce(sum(case when side = 'DEBIT' then amount else -amount end), 0) "
                + "from ledger_entry where transaction_id = ?", reversal)).as("the reversal balances")
                        .isEqualByComparingTo("0.00");
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where id = ? "
                + "and effective_on = date '2027-02-10'", reversal)).as("dated the day of the void").isEqualTo(1);
        assertThat(audits(env, "charges.charge.voided")).isEqualTo(1);
        assertThat(audits(env, "ledger.transaction.reversed")).isEqualTo(1);
    }

    @Test
    void theSameKeyAndTheSameRequestAnswerTheSameVoidAndWriteNothingMore() throws SQLException {
        Env env = seed(1);
        UUID charge = accruedCharge(env);
        ChargeVoidView first = voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge, REASON);

        ChargeVoidView replay = voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge,
                REASON);

        assertThat(replay).isEqualTo(first);
        assertThat(voids(env)).isEqualTo(1);
        assertThat(reversals(env)).isEqualTo(1);
        assertThat(audits(env, "charges.charge.voided")).isEqualTo(1);
    }

    @Test
    void aChargeIsVoidedAtMostOnceAndTheRefusalsWriteNothing() throws SQLException {
        Env env = seed(2);
        UUID charge = accruedCharge(env);
        UUID other = chargesByStudent(env).get(env.students().get(1));
        voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge, REASON);

        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-2", charge,
                REASON)).isInstanceOf(ChargeAlreadyVoidedException.class);
        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge,
                "Otro motivo distinto del primero que se dio")).isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", other,
                REASON)).as("the key of one charge cannot void another").isInstanceOf(
                        IdempotencyPayloadMismatchException.class);

        assertThat(voids(env)).isEqualTo(1);
        assertThat(reversals(env)).isEqualTo(1);
        assertThat(balanceOf(env, env.students().get(1))).isEqualByComparingTo("1650.00");
    }

    @Test
    void anInvalidRequestAndAnUnknownChargeLeaveNothingBehind() throws SQLException {
        Env env = seed(1);
        UUID charge = accruedCharge(env);

        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), null, charge, REASON))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "k", charge,
                "corto")).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "k",
                UUID.randomUUID(), REASON)).isInstanceOf(ResourceNotFoundException.class);

        assertThat(voids(env)).isZero();
        assertThat(reversals(env)).isZero();
        assertThat(balanceOf(env, env.students().get(0))).isEqualByComparingTo("1650.00");
    }

    @Test
    void aChargeOfAnotherInstitutionLooksLikeOneThatDoesNotExistAndIsNotTouched() throws SQLException {
        Env mine = seed(1);
        UUID charge = accruedCharge(mine);
        Env theirs = seed(1);

        assertThatThrownBy(() -> voidCharge().voidCharge(theirs.actor(), UUID.randomUUID().toString(), "void-1", charge,
                REASON)).isInstanceOf(ResourceNotFoundException.class);

        assertThat(voids(mine)).isZero();
        assertThat(reversals(mine)).isZero();
        assertThat(scalar(theirs.institution(), "select count(*) from charges_charge_void where charge_id = ?", charge))
                .as("row-level security: the other institution sees no void of it").isZero();
    }

    @Test
    void anAccountUnderReviewRefusesTheReversalAndNothingIsWritten() throws SQLException {
        Env env = seed(1);
        UUID charge = accruedCharge(env);
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution())) {
            exec(c, "update ledger_student_account set status = 'UNDER_REVIEW' where institution_id = ?",
                    env.institution());
            c.commit();
        }

        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge,
                REASON)).isInstanceOf(ChargeNotVoidableException.class);

        assertThat(voids(env)).isZero();
        assertThat(reversals(env)).isZero();
        assertThat(audits(env, "charges.charge.voided")).isZero();
    }

    @Test
    void aReversalPostedOutsideAVoidIsNotRecordedAsOneAndTheLedgerKeepsIt() throws SQLException {
        Env env = seed(1);
        UUID charge = accruedCharge(env);
        UUID original = (UUID) objectOf(env, "select ledger_transaction_id from charges_charge where id = ?", charge);
        transactionRunner().execute(env.system(), () -> ledger().reverseCharge(
                new com.confia.ledger.application.api.LedgerPostingApi.Actor(env.institution(), env.admin(), "system",
                        UUID.randomUUID()),
                new com.confia.ledger.application.api.LedgerPostingApi.ChargeReversal(original, "charges",
                        java.time.LocalDate.parse("2027-02-10"), REASON, "someone-else:" + charge)));

        assertThatThrownBy(() -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge,
                REASON)).isInstanceOf(ChargeNotVoidableException.class);

        assertThat(voids(env)).isZero();
        assertThat(reversals(env)).as("only the one posted outside the void").isEqualTo(1);
    }

    @Test
    void twoSimultaneousVoidsOfTheSameChargeLeaveExactlyOneVoidAndOneReversal() throws Exception {
        Env env = seed(1);
        UUID charge = accruedCharge(env);

        List<Object> outcomes = race(2, index -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(),
                "void-" + index, charge, REASON));

        assertThat(outcomes).filteredOn(ChargeVoidView.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ChargeAlreadyVoidedException.class::isInstance).hasSize(1);
        assertThat(voids(env)).isEqualTo(1);
        assertThat(reversals(env)).isEqualTo(1);
        assertThat(balanceOf(env, env.students().get(0))).isEqualByComparingTo("0.00");
    }

    @Test
    void twoSimultaneousRequestsWithTheSameKeyBothAnswerTheSameVoid() throws Exception {
        Env env = seed(1);
        UUID charge = accruedCharge(env);

        List<Object> outcomes = race(2, index -> voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(),
                "same-key", charge, REASON));

        assertThat(outcomes).allMatch(ChargeVoidView.class::isInstance);
        assertThat(outcomes.stream().map(ChargeVoidView.class::cast).map(ChargeVoidView::id).distinct()).hasSize(1);
        assertThat(voids(env)).isEqualTo(1);
        assertThat(reversals(env)).isEqualTo(1);
    }

    @Test
    void aVoidedChargeIsNotAccruedAgainWhenTheSamePeriodIsRunAgain() throws SQLException {
        Env env = seed(2);
        UUID charge = accruedCharge(env);
        voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge, REASON);

        AccrualRunView again = accrueFebruary(env, "accrual-2");

        assertThat(again.status()).isEqualTo("COMPLETED");
        assertThat(again.chargesCreated()).isZero();
        assertThat(again.chargesExisting()).as("the voided charge still counts as accrued (INV-43)").isEqualTo(2);
        assertThat(charges(env)).isEqualTo(2);
        assertThat(balanceOf(env, env.students().get(0))).isEqualByComparingTo("0.00");
    }

    // --- The list of the charges of a student ---

    @Test
    void listsTheChargesOfAStudentWithTheirDerivedStatusFiltersSortAndPages() throws SQLException {
        Env env = seed(2);
        UUID charge = accruedCharge(env);
        UUID student = env.students().get(0);
        PageRequest page = new PageRequest(0, 50, "periodStart", false);

        Page<StudentChargeView> before = listStudentCharges().list(env.actor(), UUID.randomUUID().toString(), student,
                ChargeFilter.none(), page);
        assertThat(before.total()).isEqualTo(1);
        StudentChargeView row = before.items().get(0);
        assertThat(row.charge().id()).isEqualTo(charge);
        assertThat(row.conceptCode()).isEqualTo("COL");
        assertThat(row.conceptName()).isEqualTo("Colegiatura");
        assertThat(row.charge().total()).isEqualTo(com.confia.kernel.Money.of("1650.00", com.confia.kernel.CurrencyCode.HNL));
        assertThat(row.charge().status().name()).isEqualTo("ACTIVE");
        assertThat(row.charge().voided()).isNull();

        voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge, REASON);

        Page<StudentChargeView> voided = listStudentCharges().list(env.actor(), UUID.randomUUID().toString(), student,
                new ChargeFilter("VOIDED", null, null), page);
        assertThat(voided.items()).singleElement().satisfies(item -> {
            assertThat(item.charge().status().name()).isEqualTo("VOIDED");
            assertThat(item.charge().voided().reason()).isEqualTo(REASON);
            assertThat(item.charge().voided().reversalTransactionId()).isNotNull();
        });
        assertThat(listStudentCharges().list(env.actor(), UUID.randomUUID().toString(), student,
                new ChargeFilter("ACTIVE", null, null), page).total()).isZero();
        assertThat(listStudentCharges().list(env.actor(), UUID.randomUUID().toString(), student,
                new ChargeFilter(null, java.time.LocalDate.parse("2027-02-02"), null), page).total()).isZero();
        assertThat(listStudentCharges().list(env.actor(), UUID.randomUUID().toString(), student,
                new ChargeFilter(null, null, java.time.LocalDate.parse("2027-01-31")), page).total()).isZero();
        assertThat(listStudentCharges().list(env.actor(), UUID.randomUUID().toString(), student,
                new ChargeFilter(null, java.time.LocalDate.parse("2027-02-01"), java.time.LocalDate.parse("2027-02-01")),
                new PageRequest(0, 1, "total", true)).total()).isEqualTo(1);
        assertThat(listStudentCharges().list(env.actor(), UUID.randomUUID().toString(), student, ChargeFilter.none(),
                new PageRequest(1, 1, "dueOn", false)).items()).as("the second page of one").isEmpty();
        assertThat(audits(env, "charges.student_charges.viewed")).isEqualTo(7);
    }

    @Test
    void theListRefusesAStudentOfAnotherInstitutionAndAnInvalidPageOrSort() throws SQLException {
        Env mine = seed(1);
        accruedCharge(mine);
        Env theirs = seed(1);

        assertThatThrownBy(() -> listStudentCharges().list(theirs.actor(), UUID.randomUUID().toString(),
                mine.students().get(0), ChargeFilter.none(), new PageRequest(0, 50, "periodStart", false)))
                        .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 50, "password", ChargeFilter.SORT_FIELDS, "periodStart", false))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new PageRequest(0, 201, "periodStart", false))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new ChargeFilter("PAID", null, null)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> new ChargeFilter(null, java.time.LocalDate.parse("2027-03-01"),
                java.time.LocalDate.parse("2027-02-01"))).isInstanceOf(InvalidChargesValueException.class);
    }

    // --- What the database refuses to every role ---

    @Test
    void theDatabaseRefusesToEditDeleteOrEmptyAVoidAndToRecordOneWithoutItsReversal() throws SQLException {
        Env env = seed(1);
        UUID charge = accruedCharge(env);
        ChargeVoidView voided = voidCharge().voidCharge(env.actor(), UUID.randomUUID().toString(), "void-1", charge,
                REASON);

        assertThat(refused(env, "update charges_charge_void set reason = 'otro motivo cualquiera que se invente' "
                + "where id = ?", voided.id())).isEqualTo("23001");
        assertThat(refused(env, "delete from charges_charge_void where id = ?", voided.id())).isEqualTo("23001");
        assertThat(refused(env, "truncate table charges_charge_void")).isEqualTo("23001");
        // A void that names a transaction that is not the reversal of the charge posted by this database transaction.
        UUID original = (UUID) objectOf(env, "select ledger_transaction_id from charges_charge where id = ?", charge);
        assertThat(refusedInsertOfAVoid(env, charge, original)).isEqualTo("23514");
        assertThat(voids(env)).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesAReasonOutsideTheBoundsAndAKeyThatIsNotVisible() throws SQLException {
        Env env = seed(1);
        UUID charge = accruedCharge(env);
        UUID original = (UUID) objectOf(env, "select ledger_transaction_id from charges_charge where id = ?", charge);

        assertThat(refusedInsertOfAVoid(env, charge, original, "corto", "key")).isEqualTo("23514");
        assertThat(refusedInsertOfAVoid(env, charge, original, "x".repeat(501), "key")).isEqualTo("23514");
        assertThat(refusedInsertOfAVoid(env, charge, original, REASON, "has space")).isEqualTo("23514");
    }

    // --- Helpers ---

    private Object objectOf(Env env, String sql, Object... parameters) throws SQLException {
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

    private String refusedInsertOfAVoid(Env env, UUID charge, UUID reversal) throws SQLException {
        return refusedInsertOfAVoid(env, charge, reversal, REASON, "forged-key");
    }

    private String refusedInsertOfAVoid(Env env, UUID charge, UUID reversal, String reason, String key)
            throws SQLException {
        return refused(env, "insert into charges_charge_void (institution_id, id, charge_id, reversal_transaction_id, "
                + "reason, request_key, request_hash, voided_by) values (?, ?, ?, ?, ?, ?, ?, ?)", env.institution(),
                UUID.randomUUID(), charge, reversal, reason, key, "a".repeat(64), env.admin());
    }

    /** Runs the task on {@code threads} threads that start together; returns each result or the exception it threw. */
    private List<Object> race(int threads, java.util.function.IntFunction<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<Object> call = () -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.apply(index);
                    } catch (RuntimeException e) {
                        return e;
                    }
                };
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

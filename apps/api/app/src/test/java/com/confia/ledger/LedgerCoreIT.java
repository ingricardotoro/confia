package com.confia.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.EntryView;
import com.confia.ledger.application.LedgerActor;
import com.confia.ledger.application.LedgerPosting;
import com.confia.ledger.application.LedgerPosting.PostCommand;
import com.confia.ledger.application.LedgerPosting.Posted;
import com.confia.ledger.application.LedgerPosting.ReverseCommand;
import com.confia.ledger.application.LedgerQueries;
import com.confia.ledger.application.Page;
import com.confia.ledger.domain.EntrySide;
import com.confia.ledger.domain.InvalidLedgerEntryException;
import com.confia.ledger.domain.LedgerEntry;
import com.confia.ledger.domain.LedgerIdempotencyConflictException;
import com.confia.ledger.domain.LedgerResourceNotFoundException;
import com.confia.ledger.domain.ReversalNotAllowedException;
import com.confia.ledger.domain.StudentAccountUnderReviewException;
import com.confia.ledger.domain.TransactionAlreadyReversedException;
import com.confia.ledger.domain.TransactionKind;
import com.confia.ledger.infrastructure.JooqLedgerRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import com.confia.support.StaffRoleFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
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
 * The ledger core against a real PostgreSQL (docs/cortes/ledger-core.md): the services of application with the database
 * under them, the rules that the database enforces by itself to every role (raw SQL as the owner and as the application),
 * concurrency on one account, and row-level security. Accounts and amounts are fictional.
 */
class LedgerCoreIT extends CommittingPostgresIntegrationTest {

    private static final LocalDate DAY = LocalDate.of(2027, 2, 1);
    private static final String REASON = "duplicated by the first run of the day";
    private static final String HASH = "a".repeat(64);

    /** One institution with a student, a second student, a receivable account and an income account. */
    private record Ctx(UUID institution, UUID student, UUID otherStudent, UUID receivable, UUID income, UUID cash) {
    }

    private LedgerPosting posting() {
        return new LedgerPosting(transactionRunner(), new JooqLedgerRepository(dsl), new JooqAuditLogWriter(dsl));
    }

    private LedgerQueries queries() {
        return new LedgerQueries(transactionRunner(), new JooqLedgerRepository(dsl));
    }

    private static LedgerActor actor(Ctx ctx) {
        return new LedgerActor(ctx.institution(), UUID.randomUUID(), "staff", UUID.randomUUID());
    }

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private static void student(Connection c, UUID institution, UUID id) throws SQLException {
        exec(c, "insert into students_student (institution_id, id, student_code, first_name, last_name, birth_date, sex) "
                + "values (?, ?, ?, 'Ana', 'Prueba', date '2015-01-01', 'F')", institution, id, "E-" + id.toString().substring(0, 8));
    }

    private static void account(Connection c, UUID institution, UUID id, String code, String type, boolean student)
            throws SQLException {
        exec(c, "insert into ledger_account (institution_id, id, code, name, account_type, holds_student_balances) "
                + "values (?, ?, ?, 'Cuenta de prueba', ?, ?)", institution, id, code, type, student);
    }

    private Ctx seed() throws SQLException {
        UUID institution = UUID.randomUUID();
        Ctx ctx = new Ctx(institution, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID());
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            student(owner, institution, ctx.student());
            student(owner, institution, ctx.otherStudent());
            account(owner, institution, ctx.receivable(), "1105", "ASSET", true);
            account(owner, institution, ctx.income(), "4105", "INCOME", false);
            account(owner, institution, ctx.cash(), "1101", "ASSET", false);
            owner.commit();
        }
        return ctx;
    }

    private static PostCommand accrual(Ctx ctx, UUID student, String key, String amount) {
        return new PostCommand(TransactionKind.CHARGE, DAY, "charges", null, null, key,
                List.of(new LedgerEntry(ctx.receivable(), student, EntrySide.DEBIT, hnl(amount)),
                        new LedgerEntry(ctx.income(), null, EntrySide.CREDIT, hnl(amount))));
    }

    private static ReverseCommand reversalOf(UUID original, String key) {
        return new ReverseCommand(original, "charges", DAY, REASON, key);
    }

    private Money balance(Ctx ctx, UUID student) {
        return queries().balanceOf(actor(ctx), student, CurrencyCode.HNL, DAY.plusYears(1), null);
    }

    // --- The acceptance criterion ---

    @Test
    void accruesReplaysReversesAndLeavesTheBalanceAtZeroWithBothTransactionsVisible() throws SQLException {
        Ctx ctx = seed();
        LedgerActor actor = actor(ctx);

        Posted first = posting().post(actor, accrual(ctx, ctx.student(), "charges:accrual:1", "1500.00"));
        Posted replay = posting().post(actor, accrual(ctx, ctx.student(), "charges:accrual:1", "1500.00"));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.stored().transaction().id()).isEqualTo(first.stored().transaction().id());
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("1500.00"));
        assertThat(queries().entries(actor, ctx.student(), CurrencyCode.HNL, 0, 50).total()).isEqualTo(1L);

        Posted reversal = posting().reverse(actor, reversalOf(first.stored().transaction().id(), "charges:void:1"));
        Posted reversalReplay = posting().reverse(actor, reversalOf(first.stored().transaction().id(), "charges:void:1"));

        assertThat(reversal.created()).isTrue();
        assertThat(reversalReplay.created()).isFalse();
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("0.00"));
        Page<EntryView> history = queries().entries(actor, ctx.student(), CurrencyCode.HNL, 0, 50);
        assertThat(history.items()).extracting(EntryView::kind).containsExactly(TransactionKind.CHARGE,
                TransactionKind.REVERSAL);
        assertThat(history.items()).extracting(EntryView::side).containsExactly(EntrySide.DEBIT, EntrySide.CREDIT);
    }

    @Test
    void theSameKeyWithAnotherPayloadIsAConflictAndChangesNothing() throws SQLException {
        Ctx ctx = seed();
        posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "1500.00"));

        assertThatThrownBy(() -> posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "1500.01")))
                .isInstanceOf(LedgerIdempotencyConflictException.class);

        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("1500.00"));
    }

    @Test
    void aSecondReversalAndAReversalOfAReversalAreRefused() throws SQLException {
        Ctx ctx = seed();
        Posted original = posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "100.00"));
        Posted reversal = posting().reverse(actor(ctx), reversalOf(original.stored().transaction().id(), "r1"));

        assertThatThrownBy(() -> posting().reverse(actor(ctx), reversalOf(original.stored().transaction().id(), "r2")))
                .isInstanceOf(TransactionAlreadyReversedException.class);
        assertThatThrownBy(() -> posting().reverse(actor(ctx), reversalOf(reversal.stored().transaction().id(), "r3")))
                .isInstanceOf(ReversalNotAllowedException.class);
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("0.00"));
    }

    @Test
    void aForeignModuleAnotherInstitutionAndAnEarlierDateCannotReverse() throws SQLException {
        Ctx ctx = seed();
        Ctx stranger = seed();
        Posted original = posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "100.00"));
        UUID id = original.stored().transaction().id();

        assertThatThrownBy(() -> posting().reverse(actor(ctx), new ReverseCommand(id, "ledger", DAY, REASON, "r1")))
                .isInstanceOf(ReversalNotAllowedException.class);
        assertThatThrownBy(() -> posting().reverse(actor(ctx),
                new ReverseCommand(id, "charges", DAY.minusDays(1), REASON, "r2")))
                .isInstanceOf(ReversalNotAllowedException.class);
        assertThatThrownBy(() -> posting().reverse(actor(stranger), reversalOf(id, "r3")))
                .isInstanceOf(LedgerResourceNotFoundException.class);
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("100.00"));
    }

    @Test
    void refusesEntriesWithTheWrongStudentDimensionAndUnknownAccounts() throws SQLException {
        Ctx ctx = seed();
        PostCommand receivableWithoutStudent = new PostCommand(TransactionKind.CHARGE, DAY, "charges", null, null, "k1",
                List.of(new LedgerEntry(ctx.receivable(), null, EntrySide.DEBIT, hnl("10.00")),
                        new LedgerEntry(ctx.income(), null, EntrySide.CREDIT, hnl("10.00"))));
        PostCommand incomeWithStudent = new PostCommand(TransactionKind.CHARGE, DAY, "charges", null, null, "k2",
                List.of(new LedgerEntry(ctx.receivable(), ctx.student(), EntrySide.DEBIT, hnl("10.00")),
                        new LedgerEntry(ctx.income(), ctx.student(), EntrySide.CREDIT, hnl("10.00"))));
        PostCommand unknownAccount = new PostCommand(TransactionKind.CHARGE, DAY, "charges", null, null, "k3",
                List.of(new LedgerEntry(ctx.receivable(), ctx.student(), EntrySide.DEBIT, hnl("10.00")),
                        new LedgerEntry(UUID.randomUUID(), null, EntrySide.CREDIT, hnl("10.00"))));

        assertThatThrownBy(() -> posting().post(actor(ctx), receivableWithoutStudent))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> posting().post(actor(ctx), incomeWithStudent))
                .isInstanceOf(InvalidLedgerEntryException.class);
        assertThatThrownBy(() -> posting().post(actor(ctx), unknownAccount))
                .isInstanceOf(LedgerResourceNotFoundException.class);
    }

    @Test
    void anAccountUnderReviewRefusesWritesButKeepsItsBalanceReadable() throws SQLException {
        Ctx ctx = seed();
        posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "100.00"));
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            exec(owner, "update ledger_student_account set status = 'UNDER_REVIEW' where student_id = ?", ctx.student());
            owner.commit();
        }

        assertThatThrownBy(() -> posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:2", "5.00")))
                .isInstanceOf(StudentAccountUnderReviewException.class);
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("100.00"));
    }

    @Test
    void theBalanceIgnoresLaterDatesTheCreditBalanceIsNegativeAndTheKnownAtInstantIsHonoured() throws SQLException {
        Ctx ctx = seed();
        posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "100.00"));
        PostCommand payment = new PostCommand(TransactionKind.PAYMENT, DAY.plusDays(10), "payments", null, null, "pay:1",
                List.of(new LedgerEntry(ctx.cash(), null, EntrySide.DEBIT, hnl("250.00")),
                        new LedgerEntry(ctx.receivable(), ctx.student(), EntrySide.CREDIT, hnl("250.00"))));
        posting().post(actor(ctx), payment);

        assertThat(queries().balanceOf(actor(ctx), ctx.student(), CurrencyCode.HNL, DAY, null)).isEqualTo(hnl("100.00"));
        assertThat(queries().balanceOf(actor(ctx), ctx.student(), CurrencyCode.HNL, DAY.plusDays(10), null))
                .isEqualTo(hnl("-150.00"));
        assertThat(queries().balanceOf(actor(ctx), ctx.student(), CurrencyCode.HNL, DAY.plusDays(10),
                java.time.Instant.parse("2000-01-01T00:00:00Z"))).isEqualTo(hnl("0.00"));
        assertThat(queries().balanceOf(actor(ctx), ctx.student(), CurrencyCode.USD, DAY, null)).isEqualTo(
                Money.zero(CurrencyCode.USD));
    }

    // --- The rules the database enforces by itself ---

    private static void header(Connection c, Ctx ctx, UUID id, String kind, String key, UUID reverses, String reason)
            throws SQLException {
        exec(c, "insert into ledger_transaction (institution_id, id, kind, effective_on, posting_xid, source_module, "
                + "reverses_transaction_id, reason, idempotency_key, payload_hash, created_by) "
                + "values (?, ?, ?, date '2027-02-01', 0, 'charges', ?, ?, ?, ?, ?)", ctx.institution(), id, kind,
                reverses, reason, key, HASH, UUID.randomUUID());
    }

    private static UUID studentAccount(Connection c, Ctx ctx, UUID student, String currency) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "insert into ledger_student_account (institution_id, id, student_id, currency) values (?, ?, ?, ?)",
                ctx.institution(), id, student, currency);
        return id;
    }

    private static void entry(Connection c, Ctx ctx, UUID tx, int line, UUID account, UUID studentAccount, String side,
            String amount, String currency) throws SQLException {
        exec(c, "insert into ledger_entry (institution_id, id, transaction_id, line_number, account_id, "
                + "student_account_id, side, amount, currency) values (?, ?, ?, ?, ?, ?, ?, cast(? as numeric), ?)",
                ctx.institution(), UUID.randomUUID(), tx, line, account, studentAccount, side, amount, currency);
    }

    private interface Body {
        void run(Connection c, Ctx ctx, UUID studentAccount) throws SQLException;
    }

    /** Runs {@code body} in an owner transaction and expects the COMMIT (or a statement) to be refused. */
    private SQLException refused(Body body) throws SQLException {
        Ctx ctx = seed();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            UUID sa = studentAccount(owner, ctx, ctx.student(), "HNL");
            owner.commit();
            StaffRoleFixtures.fixInstitution(owner, ctx.institution());
            try {
                body.run(owner, ctx, sa);
                owner.commit();
            } catch (SQLException e) {
                owner.rollback();
                return e;
            }
        }
        throw new AssertionError("the database accepted what it had to refuse");
    }

    @Test
    void rejectsAnUnbalancedTransactionAtCommit() throws SQLException {
        SQLException e = refused((c, ctx, sa) -> {
            UUID tx = UUID.randomUUID();
            header(c, ctx, tx, "CHARGE", "k1", null, null);
            entry(c, ctx, tx, 1, ctx.receivable(), sa, "DEBIT", "10.01", "HNL");
            entry(c, ctx, tx, 2, ctx.income(), null, "CREDIT", "10.00", "HNL");
        });
        assertThat(e.getMessage()).contains("balances");
    }

    @Test
    void rejectsAHeaderWithoutEntriesAndOneWithASingleEntry() throws SQLException {
        SQLException none = refused((c, ctx, sa) -> header(c, ctx, UUID.randomUUID(), "CHARGE", "k1", null, null));
        SQLException one = refused((c, ctx, sa) -> {
            UUID tx = UUID.randomUUID();
            header(c, ctx, tx, "CHARGE", "k1", null, null);
            entry(c, ctx, tx, 1, ctx.income(), null, "CREDIT", "10.00", "HNL");
        });
        assertThat(none.getMessage()).contains("at least two entries");
        assertThat(one.getMessage()).contains("at least two entries");
    }

    @Test
    void rejectsZeroNegativeAndThreeDecimalEntries() throws SQLException {
        for (String amount : List.of("0", "-1.00", "10.005")) {
            SQLException e = refused((c, ctx, sa) -> {
                UUID tx = UUID.randomUUID();
                header(c, ctx, tx, "CHARGE", "k1", null, null);
                entry(c, ctx, tx, 1, ctx.income(), null, "CREDIT", amount, "HNL");
            });
            assertThat(e.getSQLState()).as(amount).isEqualTo("23514");
        }
    }

    @Test
    void rejectsMixedCurrenciesAndAnEntryInAnotherCurrencyThanItsStudentAccount() throws SQLException {
        SQLException mixed = refused((c, ctx, sa) -> {
            UUID tx = UUID.randomUUID();
            header(c, ctx, tx, "CHARGE", "k1", null, null);
            entry(c, ctx, tx, 1, ctx.receivable(), sa, "DEBIT", "10.00", "HNL");
            entry(c, ctx, tx, 2, ctx.income(), null, "CREDIT", "10.00", "USD");
        });
        SQLException otherCurrency = refused((c, ctx, sa) -> {
            UUID tx = UUID.randomUUID();
            header(c, ctx, tx, "CHARGE", "k1", null, null);
            entry(c, ctx, tx, 1, ctx.receivable(), sa, "DEBIT", "10.00", "USD");
        });
        assertThat(mixed.getMessage()).contains("single currency");
        assertThat(otherCurrency.getSQLState()).isEqualTo("23503");
    }

    @Test
    void rejectsAReceivableWithoutStudentAndAnIncomeWithStudentAndAnInactiveAccount() throws SQLException {
        SQLException receivable = refused((c, ctx, sa) -> {
            UUID tx = UUID.randomUUID();
            header(c, ctx, tx, "CHARGE", "k1", null, null);
            entry(c, ctx, tx, 1, ctx.receivable(), null, "DEBIT", "10.00", "HNL");
        });
        SQLException income = refused((c, ctx, sa) -> {
            UUID tx = UUID.randomUUID();
            header(c, ctx, tx, "CHARGE", "k1", null, null);
            entry(c, ctx, tx, 1, ctx.income(), sa, "CREDIT", "10.00", "HNL");
        });
        SQLException inactive = refused((c, ctx, sa) -> {
            exec(c, "update ledger_account set is_active = false where id = ?", ctx.income());
            UUID tx = UUID.randomUUID();
            header(c, ctx, tx, "CHARGE", "k1", null, null);
            entry(c, ctx, tx, 1, ctx.income(), null, "CREDIT", "10.00", "HNL");
        });
        assertThat(receivable.getMessage()).contains("student account");
        assertThat(income.getMessage()).contains("student account");
        assertThat(inactive.getMessage()).contains("not active");
    }

    @Test
    void rejectsAnEntryAppendedToAnAlreadyPostedTransactionAndIgnoresTheCallerPostingInstant() throws SQLException {
        Ctx ctx = seed();
        Posted posted = posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "10.00"));
        UUID tx = posted.stored().transaction().id();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            UUID sa = studentAccount(owner, ctx, ctx.otherStudent(), "HNL");
            SQLException late = null;
            try {
                entry(owner, ctx, tx, 3, ctx.receivable(), sa, "DEBIT", "5.00", "HNL");
            } catch (SQLException e) {
                late = e;
            }
            assertThat((Object) late).isNotNull();
            assertThat(late.getMessage()).contains("only added in the database transaction");
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution());
                PreparedStatement s = owner.prepareStatement(
                        "select posted_at > now() - interval '1 hour', posting_xid <> 0 from ledger_transaction where id = ?")) {
            s.setObject(1, tx);
            try (var rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getBoolean(2)).isTrue();
            }
        }
    }

    @Test
    void rejectsUpdateDeleteAndTruncateToTheApplicationAndToTheOwner() throws SQLException {
        Ctx ctx = seed();
        posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "10.00"));
        for (String role : List.of("confia_owner", "confia_admin_app")) {
            for (String table : List.of("ledger_transaction", "ledger_entry")) {
                for (String statement : List.of("update " + table + " set institution_id = institution_id",
                        "delete from " + table, "truncate table " + table)) {
                    try (Connection c = SharedPostgresContainer.connectionAs(role)) {
                        c.setAutoCommit(false);
                        StaffRoleFixtures.fixInstitution(c, ctx.institution());
                        assertThatThrownBy(() -> c.createStatement().execute(statement))
                                .as(role + ": " + statement).isInstanceOf(SQLException.class);
                    }
                }
            }
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            assertThatThrownBy(() -> exec(owner, "update ledger_account set code = 'X1' where id = ?", ctx.income()))
                    .hasMessageContaining("never change");
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            assertThatThrownBy(() -> exec(owner, "delete from ledger_account where id = ?", ctx.income()))
                    .hasMessageContaining("never deleted");
        }
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("10.00"));
    }

    @Test
    void rejectsAReversalThatIsNotTheMirrorAndAReversalOfAReversal() throws SQLException {
        Ctx ctx = seed();
        Posted original = posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "10.00"));
        Posted reversal = posting().reverse(actor(ctx), reversalOf(original.stored().transaction().id(), "r1"));
        Posted other = posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:2", "20.00"));
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            UUID sa = studentAccount(owner, ctx, ctx.otherStudent(), "HNL");
            UUID tx = UUID.randomUUID();
            header(owner, ctx, tx, "REVERSAL", "bad1", other.stored().transaction().id(), REASON);
            entry(owner, ctx, tx, 1, ctx.receivable(), sa, "CREDIT", "20.00", "HNL");
            entry(owner, ctx, tx, 2, ctx.income(), null, "DEBIT", "20.00", "HNL");
            assertThatThrownBy(() -> owner.commit()).hasMessageContaining("exact mirror");
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            assertThatThrownBy(() -> header(owner, ctx, UUID.randomUUID(), "REVERSAL", "bad2",
                    reversal.stored().transaction().id(), REASON)).hasMessageContaining("never reversed");
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution())) {
            assertThatThrownBy(() -> header(owner, ctx, UUID.randomUUID(), "REVERSAL", "bad3",
                    original.stored().transaction().id(), REASON)).hasMessageContaining("single_reversal_uq");
        }
    }

    @Test
    void noTableOfTheLedgerHasABalanceColumnAndEveryGuardTriggerIsEnabled() throws SQLException {
        try (Connection c = SharedPostgresContainer.connectionAs("confia_owner");
                PreparedStatement columns = c.prepareStatement("select count(*) from information_schema.columns "
                        + "where table_name like 'ledger\\_%' and column_name ~ 'balance|saldo' and column_name <> 'holds_student_balances'");
                PreparedStatement triggers = c.prepareStatement("select count(*) from pg_trigger t join pg_class r "
                        + "on r.oid = t.tgrelid where r.relname like 'ledger\\_%' and not t.tgisinternal "
                        + "and t.tgenabled = 'O'")) {
            try (var rs = columns.executeQuery()) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            try (var rs = triggers.executeQuery()) {
                rs.next();
                // four truncate, two append-only, two guards, two before insert, one deferred.
                assertThat(rs.getInt(1)).isEqualTo(11);
            }
        }
    }

    // --- Row-level security ---

    @Test
    void anotherInstitutionNeitherReadsNorInsertsTheRowsOfTheLedger() throws SQLException {
        Ctx ctx = seed();
        Ctx stranger = seed();
        Posted posted = posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "10.00"));

        assertThat(new JooqLedgerRepository(dsl).findByIdempotencyKey(new InstitutionId(stranger.institution()),
                "charges:accrual:1")).isEmpty();
        assertThat(balance(stranger, ctx.student())).isEqualTo(hnl("0.00"));
        try (Connection c = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(c, stranger.institution());
            try (var rs = c.createStatement().executeQuery("select count(*) from ledger_transaction where id = '"
                    + posted.stored().transaction().id() + "'")) {
                rs.next();
                assertThat(rs.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> exec(c, "insert into ledger_account (institution_id, id, code, name, account_type, "
                    + "holds_student_balances) values (?, ?, 'ZZ1', 'x', 'ASSET', false)", ctx.institution(),
                    UUID.randomUUID())).isInstanceOf(SQLException.class);
        }
    }

    // --- Concurrency ---

    private <T> List<T> inParallel(List<Callable<T>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> job : jobs) {
            futures.add(pool.submit(() -> {
                go.await();
                return job.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            try {
                results.add(future.get());
            } catch (java.util.concurrent.ExecutionException e) {
                results.add(null);
                lastFailures.add(e.getCause());
            }
        }
        pool.shutdown();
        return results;
    }

    private final List<Throwable> lastFailures = java.util.Collections.synchronizedList(new ArrayList<>());

    @Test
    void concurrentPostsOnTheSameAccountSumExactlyAndOpenOneSubAccount() throws Exception {
        Ctx ctx = seed();
        List<Callable<Posted>> jobs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = "charges:accrual:c" + i;
            jobs.add(() -> posting().post(actor(ctx), accrual(ctx, ctx.student(), key, "12.50")));
        }

        inParallel(jobs);

        assertThat(lastFailures).isEmpty();
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("100.00"));
        try (Connection owner = StaffRoleFixtures.ownerTransaction(ctx.institution());
                var rs = owner.createStatement().executeQuery("select count(*) from ledger_student_account "
                        + "where student_id = '" + ctx.student() + "'")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void theSameKeyAtOnceProducesOneEffect() throws Exception {
        Ctx ctx = seed();
        List<Callable<Posted>> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            jobs.add(() -> posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:same", "40.00")));
        }

        List<Posted> results = inParallel(jobs);

        assertThat(lastFailures).isEmpty();
        assertThat(results).filteredOn(p -> p.created()).hasSize(1);
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("40.00"));
    }

    @Test
    void concurrentReversalsOfTheSameOriginalSucceedOnce() throws Exception {
        Ctx ctx = seed();
        UUID original = posting().post(actor(ctx), accrual(ctx, ctx.student(), "charges:accrual:1", "40.00"))
                .stored().transaction().id();
        List<Callable<Posted>> jobs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String key = "charges:void:" + i;
            jobs.add(() -> posting().reverse(actor(ctx), reversalOf(original, key)));
        }

        List<Posted> results = inParallel(jobs);

        assertThat(results).filteredOn(p -> p != null).hasSize(1);
        assertThat(lastFailures).hasSize(3).allMatch(t -> t instanceof TransactionAlreadyReversedException);
        assertThat(balance(ctx, ctx.student())).isEqualTo(hnl("0.00"));
    }

    @Test
    void twoTransactionsTouchingTwoStudentsInOppositeOrderDoNotDeadlock() throws Exception {
        Ctx ctx = seed();
        List<Callable<Posted>> jobs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean forward = i % 2 == 0;
            String key = "transfer:" + i;
            UUID first = forward ? ctx.student() : ctx.otherStudent();
            UUID second = forward ? ctx.otherStudent() : ctx.student();
            jobs.add(() -> posting().post(actor(ctx), new PostCommand(TransactionKind.ADJUSTMENT, DAY, "ledger", null,
                    REASON, key, List.of(new LedgerEntry(ctx.receivable(), first, EntrySide.DEBIT, hnl("5.00")),
                            new LedgerEntry(ctx.receivable(), second, EntrySide.CREDIT, hnl("5.00"))))));
        }

        inParallel(jobs);

        assertThat(lastFailures).isEmpty();
        assertThat(balance(ctx, ctx.student()).add(balance(ctx, ctx.otherStudent()))).isEqualTo(hnl("0.00"));
    }
}

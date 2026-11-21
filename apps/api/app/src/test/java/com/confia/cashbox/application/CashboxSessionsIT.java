package com.confia.cashbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.cashbox.domain.CashApprovalBySameCashierException;
import com.confia.cashbox.domain.CashSessionAlreadyOpenException;
import com.confia.cashbox.domain.CashSessionNotAwaitingApprovalException;
import com.confia.cashbox.domain.CashSessionNotOpenException;
import com.confia.cashbox.domain.ResourceNotFoundException;
import com.confia.identity.application.AccountOwnRecordPolicy;
import com.confia.identity.infrastructure.JooqStaffRoleAssignmentRepository;
import com.confia.cashbox.infrastructure.JooqCashApprovalRepository;
import com.confia.cashbox.infrastructure.JooqCashCloseRepository;
import com.confia.cashbox.infrastructure.JooqCashMovementRepository;
import com.confia.cashbox.infrastructure.JooqCashSessionRepository;
import com.confia.cashbox.infrastructure.RoleDirectorySessionScope;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import com.confia.support.StaffRoleFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * The cash sessions against a real PostgreSQL (docs/cortes/cashbox-sessions.md): the acceptance flow with its audit trail, the
 * generated difference, INV-22 under two simultaneous openings, INV-21 through the API and through SQL, the idempotency, what
 * the database refuses to every role, and the row-level security proven as the least-privilege role of the application with
 * unfiltered counts. Institutions, accounts and amounts are fictional.
 */
class CashboxSessionsIT extends CommittingPostgresIntegrationTest {

    /** The database stamps a session with its own now (the trigger allows a minute), so the tests run on the real clock. */
    private static final Clock CLOCK = Clock.systemUTC();
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Tegucigalpa"));
    private static final String REASON = "Falto un billete de cien lempiras";
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RESTRICT_VIOLATION = "23001";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** One institution with a cashier, an accountant and another cashier, as the actors of its tokens. */
    private record Env(UUID institution, AuthenticatedActor cashier, AuthenticatedActor accountant,
            AuthenticatedActor otherCashier) {
    }

    private Env seed() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(c, "insert into organization_institution (id, legal_name, rtn, address, default_currency, locale, "
                    + "timezone) values (?, 'Colegio de prueba', '1', 'Calle falsa', 'HNL', 'es-HN', "
                    + "'America/Tegucigalpa')", institution);
            c.commit();
        }
        Env env = new Env(institution, actor(institution), actor(institution), actor(institution));
        StaffRoleFixtures.committed(institution, env.cashier().accountId(), "cashier");
        StaffRoleFixtures.committed(institution, env.accountant().accountId(), "accounting");
        StaffRoleFixtures.committed(institution, env.otherCashier().accountId(), "cashier");
        return env;
    }

    private static AuthenticatedActor actor(UUID institution) {
        return new AuthenticatedActor(UUID.randomUUID(), institution, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), CLOCK.instant(), CLOCK.instant().plusSeconds(900));
    }

    private static String request() {
        return UUID.randomUUID().toString();
    }

    private SessionDetails details() {
        return new SessionDetails(new JooqCashMovementRepository(dsl), new JooqCashCloseRepository(dsl),
                new JooqCashApprovalRepository(dsl));
    }

    private OpenSession openSession() {
        return new OpenSession(transactionRunner(), new JooqCashSessionRepository(dsl), details(),
                new JooqAuditLogWriter(dsl), CLOCK);
    }

    private RecordMovement recordMovement() {
        return new RecordMovement(transactionRunner(), new JooqCashSessionRepository(dsl),
                new JooqCashMovementRepository(dsl), new JooqAuditLogWriter(dsl), CLOCK);
    }

    private CloseSession closeSession() {
        return new CloseSession(transactionRunner(), new JooqCashSessionRepository(dsl),
                new JooqCashMovementRepository(dsl), new JooqCashCloseRepository(dsl), details(),
                new JooqAuditLogWriter(dsl), CLOCK);
    }

    private ApproveClose approveClose() {
        return new ApproveClose(transactionRunner(), new JooqCashSessionRepository(dsl),
                new JooqCashApprovalRepository(dsl), details(), new JooqAuditLogWriter(dsl), CLOCK);
    }

    /** The real chain: the scope of the role, read from the assignment stored in the database. */
    private RoleDirectorySessionScope sessionScope() {
        return new RoleDirectorySessionScope(new AccountOwnRecordPolicy(new JooqStaffRoleAssignmentRepository(dsl)));
    }

    private GetSession getSession() {
        return new GetSession(transactionRunner(), new JooqCashSessionRepository(dsl), details(),
                sessionScope());
    }

    private GetCurrentSession currentSession() {
        return new GetCurrentSession(transactionRunner(), new JooqCashSessionRepository(dsl), details());
    }

    private ListSessions listSessions() {
        return new ListSessions(transactionRunner(), new JooqCashSessionRepository(dsl),
                new JooqCashCloseRepository(dsl), new JooqCashApprovalRepository(dsl), sessionScope());
    }

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    /** The cashier opens with 500.00, deposits 100.00, withdraws 50.00 and closes declaring 540.00 (expected 550.00). */
    private UUID pendingApproval(Env env) {
        UUID session = openSession().open(env.cashier(), request(), "open-" + UUID.randomUUID(), "500.00").id();
        recordMovement().record(env.cashier(), request(), "dep-" + UUID.randomUUID(), session, "deposit", "100.00", null);
        recordMovement().record(env.cashier(), request(), "wd-" + UUID.randomUUID(), session, "withdrawal", "50.00",
                "Pago de proveedor");
        closeSession().close(env.cashier(), request(), "close-" + UUID.randomUUID(), session, "540.00", REASON);
        return session;
    }

    private UUID closedClean(Env env, AuthenticatedActor cashier) {
        UUID session = openSession().open(cashier, request(), "open-" + UUID.randomUUID(), "100.00").id();
        closeSession().close(cashier, request(), "close-" + UUID.randomUUID(), session, "100.00", null);
        return session;
    }

    // --- the acceptance criterion ---

    @Test
    void theAcceptanceFlowEndsClosedAfterAnotherPersonApprovesAndLeavesEverythingInTheTrail() throws SQLException {
        Env env = seed();

        UUID session = pendingApproval(env);

        SessionDetail pending = getSession().get(env.accountant(), request(), session);
        assertThat(pending.status()).isEqualTo("pending_approval");
        assertThat(pending.expected()).isEqualTo(hnl("550.00"));
        assertThat(pending.close().declaredAmount()).isEqualTo(hnl("540.00"));
        assertThat(pending.close().differenceAmount()).isEqualTo(hnl("-10.00"));
        assertThat(pending.movements()).hasSize(2);
        assertThat(decimal(env, "select difference_amount from cashbox_session_close where institution_id = ? "
                + "and session_id = ?", env.institution(), session)).isEqualByComparingTo("-10.00");

        assertThatThrownBy(() -> approveClose().approve(env.cashier(), request(), "approve-1", session, "mine"))
                .isInstanceOf(CashApprovalBySameCashierException.class);
        assertThat(count(env, "cashbox_session_approval")).isZero();

        SessionDetail approved = approveClose().approve(env.accountant(), request(), "approve-2", session,
                "Revisado con el cajero");
        assertThat(approved.status()).isEqualTo("closed");
        assertThat(approved.approval().approvedBy()).isEqualTo(env.accountant().accountId());
        assertThat(text(env, "select status from cashbox_session where institution_id = ? and id = ?", env.institution(),
                session)).isEqualTo("closed");

        for (String action : List.of("cashbox.session.opened", "cashbox.session.closed", "cashbox.session.approved")) {
            assertThat(auditCount(env, action)).as(action).isEqualTo(1);
        }
        assertThat(auditCount(env, "cashbox.movement.recorded")).isEqualTo(2);

        assertThatThrownBy(() -> approveClose().approve(env.accountant(), request(), "approve-3", session, "again"))
                .isInstanceOf(CashSessionNotAwaitingApprovalException.class);
        assertThat(auditCount(env, "cashbox.session.approved")).isEqualTo(1);
    }

    @Test
    void aCountThatMatchesClosesTheSessionAtOnceAndAnApprovalIsRefused() throws SQLException {
        Env env = seed();
        UUID session = closedClean(env, env.cashier());

        assertThat(getSession().get(env.accountant(), request(), session).status()).isEqualTo("closed");
        assertThatThrownBy(() -> approveClose().approve(env.accountant(), request(), "approve-1", session, "ok"))
                .isInstanceOf(CashSessionNotAwaitingApprovalException.class);
    }

    // --- INV-22 ---

    @Test
    void aSecondOpeningWhileOneIsOpenIsRefusedAndTheNextOneAfterTheCloseIsAllowed() throws SQLException {
        Env env = seed();
        UUID first = openSession().open(env.cashier(), request(), "open-1", "500.00").id();

        assertThatThrownBy(() -> openSession().open(env.cashier(), request(), "open-2", "500.00"))
                .isInstanceOf(CashSessionAlreadyOpenException.class);
        assertThat(currentSession().current(env.cashier(), request()).id()).isEqualTo(first);
        assertThat(count(env, "cashbox_session")).isEqualTo(1);

        closeSession().close(env.cashier(), request(), "close-1", first, "500.00", null);
        assertThatThrownBy(() -> currentSession().current(env.cashier(), request()))
                .isInstanceOf(ResourceNotFoundException.class);
        UUID second = openSession().open(env.cashier(), request(), "open-3", "20.00").id();
        assertThat(second).isNotEqualTo(first);
        assertThat(openSession().open(env.otherCashier(), request(), "open-4", "20.00")).isNotNull();
    }

    @Test
    void simultaneousOpeningsWithDifferentKeysCreateExactlyOneSession() throws Exception {
        Env env = seed();

        List<Object> results = race(4, index -> openSession().open(env.cashier(), request(), "race-" + index, "500.00"));

        assertThat(results.stream().filter(SessionDetail.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(CashSessionAlreadyOpenException.class::isInstance)).hasSize(3);
        assertThat(count(env, "cashbox_session")).isEqualTo(1);
        assertThat(auditCount(env, "cashbox.session.opened")).isEqualTo(1);
    }

    @Test
    void simultaneousOpeningsWithTheSameKeyAnswerTheSameSession() throws Exception {
        Env env = seed();

        List<Object> results = race(4, index -> openSession().open(env.cashier(), request(), "same-key", "500.00"));

        assertThat(results).allMatch(SessionDetail.class::isInstance);
        assertThat(results.stream().map(result -> ((SessionDetail) result).id()).distinct()).hasSize(1);
        assertThat(count(env, "cashbox_session")).isEqualTo(1);
        assertThat(auditCount(env, "cashbox.session.opened")).isEqualTo(1);
    }

    // --- idempotency ---

    @Test
    void theSameKeyReplaysAndAnotherBodyOrAnotherActorIsRefusedWithNothingWritten() throws SQLException {
        Env env = seed();
        SessionDetail first = openSession().open(env.cashier(), request(), "open-1", "500.00");

        assertThat(openSession().open(env.cashier(), request(), "open-1", "500.00").id()).isEqualTo(first.id());
        assertThatThrownBy(() -> openSession().open(env.cashier(), request(), "open-1", "501.00"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> openSession().open(env.otherCashier(), request(), "open-1", "500.00"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThat(count(env, "cashbox_session")).isEqualTo(1);
        assertThat(auditCount(env, "cashbox.session.opened")).isEqualTo(1);

        MovementResult deposit = recordMovement().record(env.cashier(), request(), "dep-1", first.id(), "deposit",
                "10.00", null);
        assertThat(recordMovement().record(env.cashier(), request(), "dep-1", first.id(), "deposit", "10.00", null)
                .movement().id()).isEqualTo(deposit.movement().id());
        assertThat(count(env, "cashbox_movement")).isEqualTo(1);
        assertThatThrownBy(() -> recordMovement().record(env.cashier(), request(), "dep-1", first.id(), "deposit",
                "11.00", null)).isInstanceOf(IdempotencyPayloadMismatchException.class);

        SessionDetail closed = closeSession().close(env.cashier(), request(), "close-1", first.id(), "510.00", null);
        assertThat(closeSession().close(env.cashier(), request(), "close-1", first.id(), "510.00", null)).isEqualTo(closed);
        assertThat(count(env, "cashbox_session_close")).isEqualTo(1);
    }

    @Test
    void twoSimultaneousApprovalsWithDifferentKeysApproveOnce() throws Exception {
        Env env = seed();
        UUID session = pendingApproval(env);
        AuthenticatedActor second = actor(env.institution());

        List<Object> results = race(2, index -> approveClose().approve(index == 0 ? env.accountant() : second, request(),
                "approve-" + index, session, "ok"));

        assertThat(results.stream().filter(SessionDetail.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(CashSessionNotAwaitingApprovalException.class::isInstance)).hasSize(1);
        assertThat(count(env, "cashbox_session_approval")).isEqualTo(1);
    }

    // --- INV-21 ---

    @Test
    void aClosedSessionAcceptsNoMovementThroughTheApiNorThroughSql() throws SQLException {
        Env env = seed();
        UUID session = closedClean(env, env.cashier());
        UUID pending = pendingApproval(actorEnv(env, env.otherCashier()));

        assertThatThrownBy(() -> recordMovement().record(env.cashier(), request(), "late-1", session, "deposit", "1.00",
                null)).isInstanceOf(CashSessionNotOpenException.class);
        assertThat(insertMovement(env, session, env.cashier().accountId(), "deposit", "1.00", "HNL"))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovement(env, pending, env.otherCashier().accountId(), "deposit", "1.00", "HNL"))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(count(env, "cashbox_movement")).isEqualTo(2);
    }

    private Env actorEnv(Env env, AuthenticatedActor cashier) {
        return new Env(env.institution(), cashier, env.accountant(), env.otherCashier());
    }

    // --- what the database refuses to every role ---

    @Test
    void theDatabaseRefusesWhatTheApplicationNeverDoes() throws SQLException {
        Env env = seed();
        UUID closed = closedClean(env, env.cashier());
        UUID pending = pendingApproval(actorEnv(env, env.otherCashier()));
        UUID open = openSession().open(env.accountant(), request(), "open-acc", "10.00").id();
        UUID cashier = env.cashier().accountId();
        UUID otherCashier = env.otherCashier().accountId();

        // Nothing is edited or deleted.
        assertThat(refused(env, "update cashbox_movement set amount = 1 where institution_id = ?", env.institution()))
                .isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "delete from cashbox_movement where institution_id = ?", env.institution()))
                .isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "update cashbox_session_close set declared_amount = 1 where institution_id = ?",
                env.institution())).isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "delete from cashbox_session_close where institution_id = ?", env.institution()))
                .isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "delete from cashbox_session where institution_id = ?", env.institution()))
                .isEqualTo(RESTRICT_VIOLATION);
        for (String table : List.of("cashbox_session", "cashbox_movement", "cashbox_session_close",
                "cashbox_session_approval")) {
            assertThat(refused(env, "truncate table " + table)).as("truncate " + table).isNotNull();
        }
        // Only the status of a session moves, forward, with the record behind it.
        assertThat(refused(env, "update cashbox_session set opening_float = 1 where institution_id = ? and id = ?",
                env.institution(), open)).isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "update cashbox_session set cashier_id = ? where institution_id = ? and id = ?",
                cashier, env.institution(), open)).isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "update cashbox_session set status = 'open' where institution_id = ? and id = ?",
                env.institution(), closed)).isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "update cashbox_session set status = 'closed' where institution_id = ? and id = ?",
                env.institution(), open)).as("closing without a close record").isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "update cashbox_session set status = 'pending_approval' where institution_id = ? and id = ?",
                env.institution(), open)).isEqualTo(RESTRICT_VIOLATION);
        assertThat(refused(env, "update cashbox_session set status = 'closed' where institution_id = ? and id = ?",
                env.institution(), pending)).as("closing without the approval").isEqualTo(RESTRICT_VIOLATION);
        // INV-22 by SQL.
        assertThat(insertSession(env, env.accountant().accountId(), env.accountant().accountId(), "open", "now()"))
                .isEqualTo(UNIQUE_VIOLATION);
        // INV-23 by SQL: the expected amount is recomputed, the reason is required, the closer is the cashier.
        assertThat(insertClose(env, open, env.accountant().accountId(), "10.00", "999.00", null))
                .as("a forged expected amount").isEqualTo(CHECK_VIOLATION);
        assertThat(insertClose(env, open, env.accountant().accountId(), "5.00", "10.00", null))
                .as("a difference without a reason").isEqualTo(CHECK_VIOLATION);
        assertThat(insertClose(env, open, env.accountant().accountId(), "5.00", "10.00", "short"))
                .as("a reason too short").isEqualTo(CHECK_VIOLATION);
        assertThat(insertClose(env, open, cashier, "10.00", "10.00", null)).as("closed by another person")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertClose(env, closed, env.cashier().accountId(), "100.00", "100.00", null))
                .as("a second close").isNotNull();
        // Segregation of duties by SQL.
        assertThat(insertApproval(env, pending, otherCashier)).as("the cashier approves its own close")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertApproval(env, open, cashier)).as("approving an open session").isEqualTo(CHECK_VIOLATION);
        assertThat(insertApproval(env, closed, cashier)).as("approving a closed session").isEqualTo(CHECK_VIOLATION);
        // The movements.
        UUID owner = env.accountant().accountId();
        assertThat(insertMovement(env, open, owner, "deposit", "0", "HNL")).isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovement(env, open, owner, "withdrawal", "-5", "HNL")).isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovement(env, open, owner, "adjustment", "0", "HNL")).isEqualTo(CHECK_VIOLATION);
        // payment_in is a valid type since the payments cut: its claim by a payment is proven in RegisterPaymentIT.
        assertThat(insertMovement(env, open, owner, "deposit", "5", "USD")).as("another currency")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(applicationActing(env, owner, "insert into cashbox_movement (institution_id, id, session_id, type, "
                + "amount, currency, notes, request_key, request_hash, created_by) values (?, ?, ?, 'adjustment', 5, "
                + "'HNL', 'corta', 'forged-adj', ?, ?)", env.institution(), UUID.randomUUID(), open, "a".repeat(64),
                owner)).as("an adjustment without a proper reason").isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovement(env, open, owner, "deposit", "5", "HNL")).as("the valid one is not refused").isNull();
    }

    // --- SECURITY: who writes (app.actor_id) and the accumulated expected amount, by SQL as the application role ---

    @Test
    void theDatabaseRefusesASessionOpenedByAnotherActorNotOpenOrNotStampedNow() throws SQLException {
        Env env = seed();
        UUID cashier = env.cashier().accountId();
        UUID other = env.otherCashier().accountId();

        assertThat(insertSession(env, other, other, "open", "now()")).as("the valid one is not refused").isNull();
        assertThat(insertSession(env, null, other, "open", "now()")).as("no actor in the transaction")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertSession(env, cashier, other, "open", "now()")).as("opened on behalf of another person")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertSession(env, other, other, "closed", "now()")).as("born closed").isEqualTo(CHECK_VIOLATION);
        assertThat(insertSession(env, other, other, "pending_approval", "now()")).as("born pending")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertSession(env, other, other, "open", "now() - interval '1 day'")).as("backdated")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertSession(env, other, other, "open", "now() + interval '1 day'")).as("postdated")
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void theDatabaseRefusesAMovementACloseAndAnApprovalWrittenByAnyoneButTheActor() throws SQLException {
        Env env = seed();
        UUID cashier = env.cashier().accountId();
        UUID other = env.otherCashier().accountId();
        UUID accountant = env.accountant().accountId();
        UUID open = openSession().open(env.cashier(), request(), "open-1", "100.00").id();
        UUID pending = pendingApproval(actorEnv(env, env.otherCashier()));

        assertThat(insertMovementFor(env, open, cashier, cashier, "deposit", "5", "HNL")).as("valid").isNull();
        assertThat(insertMovementFor(env, open, cashier, null, "deposit", "5", "HNL")).as("no actor")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovementFor(env, open, cashier, other, "deposit", "5", "HNL")).as("created_by is not the actor")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovementFor(env, open, other, other, "deposit", "5", "HNL"))
                .as("not the cashier of the session").isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovementFor(env, open, accountant, accountant, "deposit", "5", "HNL"))
                .as("not the cashier of the session").isEqualTo(CHECK_VIOLATION);

        assertThat(insertClose(env, open, cashier, cashier, "100.00", "100.00", null)).as("valid").isNull();
        assertThat(insertClose(env, open, cashier, null, "100.00", "100.00", null)).as("no actor")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertClose(env, open, cashier, other, "100.00", "100.00", null)).as("closed_by is not the actor")
                .isEqualTo(CHECK_VIOLATION);

        assertThat(insertApprovalFor(env, pending, accountant, accountant)).as("valid").isNull();
        assertThat(insertApprovalFor(env, pending, accountant, null)).as("no actor").isEqualTo(CHECK_VIOLATION);
        assertThat(insertApprovalFor(env, pending, accountant, cashier)).as("approved_by is not the actor")
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void theDatabaseRefusesAMovementThatWouldLeaveTheExpectedCashBelowZero() throws SQLException {
        Env env = seed();
        UUID cashier = env.cashier().accountId();
        UUID session = openSession().open(env.cashier(), request(), "open-1", "100.00").id();
        recordMovement().record(env.cashier(), request(), "wd-1", session, "withdrawal", "60.00", null);

        assertThat(insertMovement(env, session, cashier, "withdrawal", "40.01", "HNL")).as("40.01 of the 40.00 left")
                .isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovementFor(env, session, cashier, cashier, "adjustment", "-40.01", "HNL"))
                .as("an adjustment is no way around it").isEqualTo(CHECK_VIOLATION);
        assertThat(insertMovement(env, session, cashier, "withdrawal", "40.00", "HNL")).as("exactly what is left")
                .isNull();
    }

    // --- concurrency ---

    @Test
    void simultaneousWithdrawalsBeyondTheExpectedCashOnlyThoseThatFitSucceed() throws Exception {
        Env env = seed();
        UUID session = openSession().open(env.cashier(), request(), "open-1", "500.00").id();

        List<Object> results = race(8, index -> recordMovement().record(env.cashier(), request(), "wd-" + index, session,
                "withdrawal", "100.00", null));

        assertThat(results.stream().filter(MovementResult.class::isInstance)).hasSize(5);
        assertThat(results.stream().filter(com.confia.cashbox.domain.CashExpectedNegativeException.class::isInstance))
                .hasSize(3);
        assertThat(count(env, "cashbox_movement")).isEqualTo(5);
        assertThat(getSession().get(env.accountant(), request(), session).expected()).isEqualTo(hnl("0.00"));
        assertThat(decimal(env, "select min(expected_after) from cashbox_movement where institution_id = ?",
                env.institution())).isEqualByComparingTo("0.00");
        assertThat(auditCount(env, "cashbox.movement.recorded")).isEqualTo(5);
    }

    @Test
    void simultaneousRawWithdrawalsCannotOverdrawTheSessionEvenWithoutTheApplicationLock() throws Exception {
        Env env = seed();
        UUID cashier = env.cashier().accountId();
        UUID session = openSession().open(env.cashier(), request(), "open-1", "100.00").id();

        List<Object> results = race(4, index -> commitRawWithdrawal(env, session, cashier, "60.00", "raw-" + index));

        assertThat(results.stream().filter("ok"::equals)).hasSize(1);
        assertThat(results.stream().filter(CHECK_VIOLATION::equals)).hasSize(3);
        assertThat(decimal(env, "select min(expected_after) from cashbox_movement where institution_id = ?",
                env.institution())).isEqualByComparingTo("40.00");
    }

    @Test
    void aMovementRacingTheCloseIsEitherInTheExpectedAmountOrRefusedAndNeverAfterTheClose() throws Exception {
        for (int round = 0; round < 5; round++) {
            Env env = seed();
            UUID session = openSession().open(env.cashier(), request(), "open-1", "500.00").id();

            List<Object> results = race(7, index -> index == 0
                    ? closeSession().close(env.cashier(), request(), "close-1", session, "500.00", REASON)
                    : recordMovement().record(env.cashier(), request(), "dep-" + index, session, "deposit", "10.00",
                            null));

            assertThat(results.get(0)).as("the close").isInstanceOf(SessionDetail.class);
            List<Object> movementResults = results.subList(1, results.size());
            assertThat(movementResults).allMatch(result -> result instanceof MovementResult
                    || result instanceof CashSessionNotOpenException);
            long accepted = movementResults.stream().filter(MovementResult.class::isInstance).count();
            assertThat(count(env, "cashbox_movement")).isEqualTo(accepted);
            // Every accepted movement is inside the expected amount of the close; none came after it.
            assertThat(decimal(env, "select expected_amount from cashbox_session_close where institution_id = ?",
                    env.institution())).isEqualByComparingTo(new java.math.BigDecimal(500 + 10 * accepted));
            assertThat(decimal(env, "select 500 + coalesce(sum(amount), 0) from cashbox_movement where institution_id = ?",
                    env.institution())).isEqualByComparingTo(new java.math.BigDecimal(500 + 10 * accepted));
            assertThat(auditCount(env, "cashbox.movement.recorded")).isEqualTo(accepted);
        }
    }

    // --- idempotent replay of a movement ---

    @Test
    void theReplayOfAnEarlierMovementAnswersTheExpectedCashOfThatCallNotOfToday() throws SQLException {
        Env env = seed();
        UUID session = openSession().open(env.cashier(), request(), "open-1", "500.00").id();
        MovementResult a = recordMovement().record(env.cashier(), request(), "mov-a", session, "deposit", "100.00", null);
        MovementResult b = recordMovement().record(env.cashier(), request(), "mov-b", session, "withdrawal", "30.00",
                null);

        MovementResult replayOfA = recordMovement().record(env.cashier(), request(), "mov-a", session, "deposit",
                "100.00", null);
        MovementResult replayOfB = recordMovement().record(env.cashier(), request(), "mov-b", session, "withdrawal",
                "30.00", null);

        assertThat(a.expectedAfter()).isEqualTo(hnl("600.00"));
        assertThat(b.expectedAfter()).isEqualTo(hnl("570.00"));
        assertThat(replayOfA.movement().id()).isEqualTo(a.movement().id());
        assertThat(replayOfA.expectedAfter()).isEqualTo(hnl("600.00"));
        assertThat(replayOfB.expectedAfter()).isEqualTo(hnl("570.00"));
        assertThat(count(env, "cashbox_movement")).isEqualTo(2);
    }

    @Test
    void theApplicationRoleCannotUpdateAnythingButTheStatusNorDeleteNorTruncate() throws SQLException {
        Env env = seed();
        UUID open = openSession().open(env.cashier(), request(), "open-1", "100.00").id();

        assertThat(asApplication(env.institution(), "update cashbox_session set opening_float = 1 where id = ?", open))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(asApplication(env.institution(), "delete from cashbox_session where id = ?", open))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(asApplication(env.institution(), "update cashbox_movement set amount = 1 where session_id = ?", open))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(asApplication(env.institution(), "delete from cashbox_session_close where session_id = ?", open))
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
        assertThat(asApplication(env.institution(), "truncate table cashbox_session")).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // --- row-level security, as the least-privilege role of the application, with unfiltered counts ---

    @Test
    void oneInstitutionSeesNothingOfAnotherThroughRowLevelSecurity() throws SQLException {
        Env a = seed();
        Env b = seed();
        UUID sessionA = pendingApproval(a);
        pendingApproval(b);
        closedClean(b, b.otherCashier());
        approveClose().approve(a.accountant(), request(), "approve-a", sessionA, "ok");

        // A has 1 session, 2 movements, 1 close and 1 approval; B has 2 sessions, 2 movements, 1 pending close and 1 clean.
        assertThat(visibleAsApplication(a.institution(), "cashbox_session")).isEqualTo(1);
        assertThat(visibleAsApplication(a.institution(), "cashbox_movement")).isEqualTo(2);
        assertThat(visibleAsApplication(a.institution(), "cashbox_session_close")).isEqualTo(1);
        assertThat(visibleAsApplication(a.institution(), "cashbox_session_approval")).isEqualTo(1);
        assertThat(visibleAsApplication(b.institution(), "cashbox_session")).isEqualTo(2);
        assertThat(visibleAsApplication(b.institution(), "cashbox_movement")).isEqualTo(2);
        assertThat(visibleAsApplication(b.institution(), "cashbox_session_close")).isEqualTo(2);
        assertThat(visibleAsApplication(b.institution(), "cashbox_session_approval")).isZero();
        assertThat(visibleAsApplication(UUID.randomUUID(), "cashbox_session")).as("another institution").isZero();
        assertThat(visibleAsApplicationWithoutContext("cashbox_session")).as("no context at all").isZero();

        // The same through the use cases: the other institution's session is a 404 and its sessions are not listed.
        assertThatThrownBy(() -> getSession().get(b.accountant(), request(), sessionA))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> approveClose().approve(b.accountant(), request(), "approve-x", sessionA, "ok"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> recordMovement().record(b.cashier(), request(), "move-x", sessionA, "deposit", "1.00",
                null)).isInstanceOf(ResourceNotFoundException.class);
        Page<SessionSummary> listedByB = listSessions().list(b.accountant(), request(),
                new SessionFilter(null, null, null, null),
                PageRequest.of(0, 50, null, SessionFilter.SORT_FIELDS, "openedAt", false));
        assertThat(listedByB.total()).isEqualTo(2);
        assertThat(listedByB.items()).noneMatch(row -> row.id().equals(sessionA));
    }

    @Test
    void aCashierReadsOnlyItsOwnSessionWhileAccountingSeesBoth() throws SQLException {
        Env env = seed();
        AuthenticatedActor cashierA = env.cashier();
        AuthenticatedActor cashierB = env.otherCashier();
        UUID sessionA = closedClean(env, cashierA);
        UUID sessionB = openSession().open(cashierB, request(), "open-b", "100.00").id();
        UUID currentA = openSession().open(cashierA, request(), "open-a2", "50.00").id();

        SessionFilter everyone = new SessionFilter(null, null, null, null);
        SessionFilter askingForB = new SessionFilter(null, cashierB.accountId(), null, null);
        PageRequest page = PageRequest.of(0, 50, null, SessionFilter.SORT_FIELDS, "openedAt", false);

        assertThat(listSessions().list(cashierA, request(), everyone, page).items()).extracting(SessionSummary::id)
                .containsExactlyInAnyOrder(sessionA, currentA);
        assertThat(listSessions().list(cashierA, request(), askingForB, page).items()).as("filtering by B's cashier id")
                .extracting(SessionSummary::id).containsExactlyInAnyOrder(sessionA, currentA).doesNotContain(sessionB);
        assertThat(listSessions().list(cashierA, request(), askingForB, page).total()).isEqualTo(2);

        assertThatThrownBy(() -> getSession().get(cashierA, request(), sessionB))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(getSession().get(cashierA, request(), sessionA).id()).isEqualTo(sessionA);
        assertThat(currentSession().current(cashierA, request()).id()).isEqualTo(currentA);
        assertThat(currentSession().current(cashierB, request()).id()).isEqualTo(sessionB);

        assertThat(listSessions().list(env.accountant(), request(), everyone, page).items())
                .extracting(SessionSummary::id).containsExactlyInAnyOrder(sessionA, sessionB, currentA);
        assertThat(getSession().get(env.accountant(), request(), sessionB).id()).isEqualTo(sessionB);
    }

    @Test
    void anAccountWithoutARoleAssignmentReadsOnlyItsOwnSessions() throws SQLException {
        Env env = seed();
        AuthenticatedActor unassigned = actor(env.institution());
        StaffRoleFixtures.committed(env.institution(), unassigned.accountId(), null);
        UUID session = closedClean(env, env.cashier());

        assertThatThrownBy(() -> getSession().get(unassigned, request(), session))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(listSessions().list(unassigned, request(), new SessionFilter(null, null, null, null),
                PageRequest.of(0, 50, null, SessionFilter.SORT_FIELDS, "openedAt", false)).total()).isZero();
    }

    @Test
    void theApplicationRoleCannotWriteARowOfAnotherInstitution() throws SQLException {
        Env a = seed();
        Env b = seed();

        // The actor is the cashier, so the triggers pass and it is the row-level security that refuses.
        UUID cashier = UUID.randomUUID();
        assertThat(applicationActing(a, cashier, "insert into cashbox_session (institution_id, id, cashier_id, "
                + "currency, opening_float, request_key, request_hash) values (?, ?, ?, 'HNL', 1, 'cross-1', ?)",
                b.institution(), UUID.randomUUID(), cashier, "a".repeat(64))).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    // --- the lists ---

    @Test
    void theListFiltersByStatusAndCashierAndPaginates() throws SQLException {
        Env env = seed();
        UUID pending = pendingApproval(env);
        UUID clean = closedClean(env, env.otherCashier());
        UUID open = openSession().open(env.accountant(), request(), "open-acc", "10.00").id();
        PageRequest page = PageRequest.of(0, 50, null, SessionFilter.SORT_FIELDS, "openedAt", false);

        assertThat(ids(listSessions().list(env.accountant(), request(), new SessionFilter(null, null, null, null), page)))
                .containsExactlyInAnyOrder(pending, clean, open);
        assertThat(ids(listSessions().list(env.accountant(), request(),
                new SessionFilter("pending_approval", null, null, null), page))).containsExactly(pending);
        assertThat(ids(listSessions().list(env.accountant(), request(),
                new SessionFilter(null, env.otherCashier().accountId(), null, null), page))).containsExactly(clean);
        assertThat(ids(listSessions().list(env.accountant(), request(), new SessionFilter("open", null,
                TODAY, TODAY), page)))
                .containsExactly(open);
        assertThat(ids(listSessions().list(env.accountant(), request(), new SessionFilter("open", null,
                TODAY.plusDays(1), null), page))).isEmpty();
        Page<SessionSummary> firstPage = listSessions().list(env.accountant(), request(),
                new SessionFilter(null, null, null, null),
                PageRequest.of(0, 2, "status,asc", SessionFilter.SORT_FIELDS, "openedAt", false));
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.total()).isEqualTo(3);
    }

    private static List<UUID> ids(Page<SessionSummary> page) {
        return page.items().stream().map(SessionSummary::id).toList();
    }

    // --- helpers ---

    private List<Object> race(int threads, IntFunction<Object> task) throws Exception {
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

    /** A movement written by {@code by}, who is also the actor of the transaction. */
    private String insertMovement(Env env, UUID session, UUID by, String type, String amount, String currency)
            throws SQLException {
        return insertMovementFor(env, session, by, by, type, amount, currency);
    }

    private String insertMovementFor(Env env, UUID session, UUID createdBy, UUID actor, String type, String amount,
            String currency) throws SQLException {
        return applicationActing(env, actor, "insert into cashbox_movement (institution_id, id, session_id, type, "
                + "amount, currency, request_key, request_hash, created_by) values (?, ?, ?, ?, ?::numeric, ?, ?, ?, ?)",
                env.institution(), UUID.randomUUID(), session, type, amount, currency, "forged-" + UUID.randomUUID(),
                "a".repeat(64), createdBy);
    }

    private String insertClose(Env env, UUID session, UUID closedBy, String declared, String expected, String reason)
            throws SQLException {
        return insertClose(env, session, closedBy, closedBy, declared, expected, reason);
    }

    private String insertClose(Env env, UUID session, UUID closedBy, UUID actor, String declared, String expected,
            String reason) throws SQLException {
        return applicationActing(env, actor, "insert into cashbox_session_close (institution_id, id, session_id, "
                + "currency, declared_amount, expected_amount, difference_reason, closed_by, request_key, request_hash) "
                + "values (?, ?, ?, 'HNL', ?::numeric, ?::numeric, ?, ?, ?, ?)", env.institution(), UUID.randomUUID(),
                session, declared, expected, reason, closedBy, "forged-" + UUID.randomUUID(), "a".repeat(64));
    }

    private String insertApproval(Env env, UUID session, UUID approvedBy) throws SQLException {
        return insertApprovalFor(env, session, approvedBy, approvedBy);
    }

    private String insertApprovalFor(Env env, UUID session, UUID approvedBy, UUID actor) throws SQLException {
        return applicationActing(env, actor, "insert into cashbox_session_approval (institution_id, id, session_id, "
                + "approved_by, note, request_key, request_hash) values (?, ?, ?, ?, 'forged', ?, ?)",
                env.institution(), UUID.randomUUID(), session, approvedBy, "forged-" + UUID.randomUUID(),
                "a".repeat(64));
    }

    /** {@code openedAt} is a SQL expression written by the test itself, never an input. */
    private String insertSession(Env env, UUID actor, UUID cashier, String status, String openedAt)
            throws SQLException {
        return applicationActing(env, actor, "insert into cashbox_session (institution_id, id, cashier_id, currency, "
                + "opening_float, status, opened_at, request_key, request_hash) values (?, ?, ?, 'HNL', 1, ?, "
                + openedAt + ", ?, ?)", env.institution(), UUID.randomUUID(), cashier, status,
                "forged-" + UUID.randomUUID(), "a".repeat(64));
    }

    /**
     * Runs the statement as the least-privilege role of the application, with the institution and, when given, the actor
     * of the security context (app.actor_id, as the transaction runner sets it), and rolls back; returns the SQLState or null.
     */
    private String applicationActing(Env env, UUID actor, String sql, Object... parameters) throws SQLException {
        try (Connection c = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(c, env.institution());
            if (actor != null) {
                exec(c, "select set_config('app.actor_id', ?, true)", actor.toString());
            }
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

    /** A withdrawal straight into the table, as the application role, committed: "ok" or the SQLState of the refusal. */
    private String commitRawWithdrawal(Env env, UUID session, UUID cashier, String amount, String key) {
        try (Connection c = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(c, env.institution());
            exec(c, "select set_config('app.actor_id', ?, true)", cashier.toString());
            try {
                exec(c, "insert into cashbox_movement (institution_id, id, session_id, type, amount, currency, "
                        + "request_key, request_hash, created_by) values (?, ?, ?, 'withdrawal', ?::numeric, 'HNL', ?, ?, ?)",
                        env.institution(), UUID.randomUUID(), session, amount, key, "a".repeat(64), cashier);
                c.commit();
                return "ok";
            } catch (SQLException e) {
                c.rollback();
                return e.getSQLState();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("the raw withdrawal could not run", e);
        }
    }

    /** Runs the statement as the owner of the schema (bound by every trigger) and rolls back; returns the SQLState or null. */
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

    /** Runs the statement as the least-privilege role of the application with the context of an institution. */
    private static String asApplication(UUID institutionContext, String sql, Object... parameters) throws SQLException {
        try (Connection c = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(c, institutionContext);
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

    /** Counts ALL the rows of a table, with no filter at all, as the application role with an institution context. */
    private static long visibleAsApplication(UUID institutionContext, String table) throws SQLException {
        try (Connection c = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(c, institutionContext);
            return unfilteredCount(c, table);
        }
    }

    private static long visibleAsApplicationWithoutContext(String table) throws SQLException {
        try (Connection c = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            c.setAutoCommit(false);
            return unfilteredCount(c, table);
        }
    }

    private static long unfilteredCount(Connection c, String table) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("select count(*) from " + table); ResultSet rs = s.executeQuery()) {
            rs.next();
            long count = rs.getLong(1);
            c.rollback();
            return count;
        }
    }

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.execute();
        }
    }

    private long count(Env env, String table) throws SQLException {
        return decimal(env, "select count(*) from " + table + " where institution_id = ?", env.institution()).longValue();
    }

    private long auditCount(Env env, String action) throws SQLException {
        return decimal(env, "select count(*) from shared_audit_log where institution_id = ? and action = ?",
                env.institution(), action).longValue();
    }

    private java.math.BigDecimal decimal(Env env, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution());
                PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private String text(Env env, String sql, Object... parameters) throws SQLException {
        try (Connection c = StaffRoleFixtures.ownerTransaction(env.institution());
                PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}

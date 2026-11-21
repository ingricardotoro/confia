package com.confia.cashbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.cashbox.domain.CashApproval;
import com.confia.cashbox.domain.CashApprovalBySameCashierException;
import com.confia.cashbox.domain.CashClose;
import com.confia.cashbox.domain.CashDifferenceReasonRequiredException;
import com.confia.cashbox.domain.CashExpectedNegativeException;
import com.confia.cashbox.domain.CashMovement;
import com.confia.cashbox.domain.CashSession;
import com.confia.cashbox.domain.CashSessionAlreadyOpenException;
import com.confia.cashbox.domain.CashSessionNotAwaitingApprovalException;
import com.confia.cashbox.domain.CashSessionNotOpenException;
import com.confia.cashbox.domain.CashSessionStatus;
import com.confia.cashbox.domain.IdempotencyKeyRequiredException;
import com.confia.cashbox.domain.InvalidCashboxValueException;
import com.confia.cashbox.domain.ResourceNotFoundException;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.support.JdbcStubTransactions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The seven use cases of the cash sessions with the ports replaced by in-memory fakes: the idempotency by key and by content
 * (and by actor), the ownership of the session, what a refusal leaves behind (nothing, and a rolled back transaction), the
 * audit entries and the scope of the reads. The SQL, the triggers, the row-level security and the concurrency are proven against
 * PostgreSQL in {@code CashboxSessionsIT}.
 */
class CashboxUseCasesTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final String REASON = "Falto un billete de cien lempiras";

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audit = new ArrayList<>();
    private final AuditLogWriter auditWriter = audit::add;
    private final UUID institutionUuid = UUID.randomUUID();
    private final InstitutionId institution = new InstitutionId(institutionUuid);
    private final AuthenticatedActor cashier = actor();
    private final AuthenticatedActor accountant = actor();
    private final String requestId = UUID.randomUUID().toString();
    private boolean readsEverySession = true;

    private final FakeSessions sessions = new FakeSessions();
    private final FakeMovements movements = new FakeMovements();
    private final FakeCloses closes = new FakeCloses();
    private final FakeApprovals approvals = new FakeApprovals();
    private final SessionDetails details = new SessionDetails(movements, closes, approvals);
    private final SessionScope scope = actor -> readsEverySession;
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private AuthenticatedActor actor() {
        return new AuthenticatedActor(UUID.randomUUID(), institutionUuid, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), NOW, NOW.plusSeconds(900));
    }

    private OpenSession openUseCase() {
        return new OpenSession(JdbcStubTransactions.runner(transactionCalls), sessions, details, auditWriter, clock);
    }

    private RecordMovement moveUseCase() {
        return new RecordMovement(JdbcStubTransactions.runner(transactionCalls), sessions, movements, auditWriter, clock);
    }

    private CloseSession closeUseCase() {
        return new CloseSession(JdbcStubTransactions.runner(transactionCalls), sessions, movements, closes, details,
                auditWriter, clock);
    }

    private ApproveClose approveUseCase() {
        return new ApproveClose(JdbcStubTransactions.runner(transactionCalls), sessions, approvals, details, auditWriter,
                clock);
    }

    private SessionDetail opened(String openingFloat) {
        return openUseCase().open(cashier, requestId, "open-" + UUID.randomUUID(), openingFloat);
    }

    // --- opening ---

    @Test
    void anOpeningStoresTheSessionOfTheCashierAuditsItAndCommits() {
        SessionDetail session = openUseCase().open(cashier, requestId, "open-1", "500.00");

        assertThat(session.status()).isEqualTo("open");
        assertThat(session.cashierId()).isEqualTo(cashier.accountId());
        assertThat(session.openingFloat()).isEqualTo(Money.of("500.00", CurrencyCode.HNL));
        assertThat(session.expected()).isEqualTo(Money.of("500.00", CurrencyCode.HNL));
        assertThat(session.movements()).isEmpty();
        assertThat(transactionCalls).containsExactly("commit");
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("cashbox.session.opened");
            assertThat(entry.entityId()).isEqualTo(session.id().toString());
            assertThat(entry.actorId()).isEqualTo(cashier.accountId());
            assertThat(entry.afterValue()).contains("\"openingFloat\":\"500.0000\"");
        });
    }

    @Test
    void aZeroFloatIsAllowedAndANegativeOrMalformedOneIsRefused() {
        assertThat(openUseCase().open(cashier, requestId, "k0", "0.00").expected().isZero()).isTrue();
        for (String bad : new String[] {"-1.00", "10.005", "abc", null}) {
            assertThatThrownBy(() -> openUseCase().open(actor(), requestId, "k-" + bad, bad))
                    .as(String.valueOf(bad)).isInstanceOf(InvalidCashboxValueException.class);
        }
    }

    @Test
    void theKeyIsRequiredAndMustBeOneToHundredTwentyEightVisibleCharacters() {
        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, null, "1.00"))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, "  ", "1.00"))
                .isInstanceOf(IdempotencyKeyRequiredException.class);
        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, "with space", "1.00"))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, "k".repeat(129), "1.00"))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThat(sessions.rows).isEmpty();
    }

    @Test
    void aSecondOpeningWithAnotherKeyIsRefusedWhetherOrNotTheBodyIsTheSame() {
        openUseCase().open(cashier, requestId, "open-1", "500.00");
        audit.clear();

        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, "open-2", "500.00"))
                .isInstanceOf(CashSessionAlreadyOpenException.class);
        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, "open-3", "10.00"))
                .isInstanceOf(CashSessionAlreadyOpenException.class);

        assertThat(sessions.rows).hasSize(1);
        assertThat(audit).isEmpty();
        assertThat(transactionCalls).endsWith("rollback", "rollback");
    }

    @Test
    void theSameKeyAndTheSameBodyAnswerTheSameSessionAndWriteNothing() {
        SessionDetail first = openUseCase().open(cashier, requestId, "open-1", "500.00");
        audit.clear();

        SessionDetail again = openUseCase().open(cashier, requestId, "open-1", "500.00");

        assertThat(again).isEqualTo(first);
        assertThat(audit).isEmpty();
        assertThat(sessions.inserts).isEqualTo(1);
    }

    @Test
    void theSameKeyWithAnotherFloatOrFromAnotherCashierIsRefused() {
        openUseCase().open(cashier, requestId, "open-1", "500.00");

        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, "open-1", "600.00"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> openUseCase().open(accountant, requestId, "open-1", "500.00"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThat(sessions.rows).hasSize(1);
    }

    @Test
    void theReplayOfAnOpeningAfterTheCloseAnswersTheSessionAsItIsNow() {
        SessionDetail first = openUseCase().open(cashier, requestId, "open-1", "500.00");
        closeUseCase().close(cashier, requestId, "close-1", first.id(), "500.00", null);

        SessionDetail again = openUseCase().open(cashier, requestId, "open-1", "500.00");

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.status()).isEqualTo("closed");
        assertThat(sessions.rows).hasSize(1);
    }

    @Test
    void theLoserOfTheRaceStartsAgainAndFindsTheWinner() {
        CashSession winner = openedByOtherRequest("open-1", "500.00");
        sessions.failNextInsertAfterOtherRequestWins = winner;

        SessionDetail answered = openUseCase().open(cashier, requestId, "open-1", "500.00");

        assertThat(answered.id()).isEqualTo(winner.id());
        assertThat(sessions.rows).hasSize(1);
    }

    @Test
    void aLoserThatLosesToADifferentKeyEndsAsAnAlreadyOpenSession() {
        sessions.failNextInsertAfterOtherRequestWins = openedByOtherRequest("open-other", "500.00");

        assertThatThrownBy(() -> openUseCase().open(cashier, requestId, "open-1", "500.00"))
                .isInstanceOf(CashSessionAlreadyOpenException.class);
    }

    private CashSession openedByOtherRequest(String key, String openingFloat) {
        return CashSession.open(UUID.randomUUID(), cashier.accountId(), Money.of(openingFloat, CurrencyCode.HNL), NOW, key,
                CashboxRequests.hash("open", cashier.accountId(), Money.of(openingFloat, CurrencyCode.HNL).toPlainString()));
    }

    // --- movements ---

    // The payment_in movement of a cash payment (docs/cortes/payments-register.md), written inside the transaction of the payment.

    private RecordPaymentIn paymentInUseCase(com.confia.shared.security.TransactionRunner runner) {
        return new RecordPaymentIn(runner, sessions, movements, auditWriter, clock);
    }

    @Test
    void aCashPaymentLeavesAPaymentInMovementInTheOpenSessionOfTheCashierThatSumsIntoTheExpectedCash() {
        UUID session = opened("500.00").id();
        UUID payment = UUID.randomUUID();
        com.confia.shared.security.TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);
        audit.clear();

        Optional<com.confia.cashbox.application.api.CashPayments.PaymentIn> in = runner.execute(
                cashier.securityContext(requestId), () -> paymentInUseCase(runner).recordPaymentIn(cashier,
                        UUID.fromString(requestId), payment, Money.of("1500.00", CurrencyCode.HNL)));

        assertThat(in).isPresent();
        assertThat(in.get().sessionId()).isEqualTo(session);
        assertThat(movements.rows).singleElement().satisfies(movement -> {
            assertThat(movement.type().code()).isEqualTo("payment_in");
            assertThat(movement.requestKey()).isEqualTo("payment-in:" + payment);
            assertThat(movement.createdBy()).isEqualTo(cashier.accountId());
        });
        assertThat(sessions.rows.get(session).expected(movements.rows)).isEqualTo(Money.of("2000.00", CurrencyCode.HNL));
        assertThat(audit).singleElement().satisfies(entry -> assertThat(entry.action()).isEqualTo("cashbox.movement.recorded"));
    }

    @Test
    void aCashierWithoutAnOpenSessionGetsNothingAndAnotherCashiersSessionIsNeverUsed() {
        opened("500.00");
        com.confia.shared.security.TransactionRunner runner = JdbcStubTransactions.runner(transactionCalls);

        Optional<com.confia.cashbox.application.api.CashPayments.PaymentIn> in = runner.execute(
                accountant.securityContext(requestId), () -> paymentInUseCase(runner).recordPaymentIn(accountant,
                        UUID.fromString(requestId), UUID.randomUUID(), Money.of("10.00", CurrencyCode.HNL)));

        assertThat(in).isEmpty();
        assertThat(movements.rows).isEmpty();
    }

    @Test
    void aPaymentInOutsideATransactionIsADefect() {
        opened("500.00");

        assertThatThrownBy(() -> paymentInUseCase(JdbcStubTransactions.runner(transactionCalls)).recordPaymentIn(cashier,
                UUID.fromString(requestId), UUID.randomUUID(), Money.of("10.00", CurrencyCode.HNL)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aCashierCannotTypeAPaymentInMovementThroughTheRoutesOfTheMovements() {
        UUID session = opened("500.00").id();

        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-pi", session, "payment_in", "10.00", null))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThat(movements.rows).isEmpty();
    }

    @Test
    void theReplayOfAnEarlierMovementAnswersTheExpectedCashOfThatCallNotOfToday() {
        UUID session = opened("500.00").id();
        MovementResult first = moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "100.00", null);
        moveUseCase().record(cashier, requestId, "m-2", session, "withdrawal", "50.00", "Pago de proveedor");
        audit.clear();

        MovementResult replay = moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "100.00", null);

        assertThat(replay.expectedAfter()).isEqualTo(Money.of("600.00", CurrencyCode.HNL))
                .isEqualTo(first.expectedAfter());
        assertThat(replay.movement().id()).isEqualTo(first.movement().id());
        assertThat(audit).isEmpty();
    }

    @Test
    void aMovementIsRecordedWithTheExpectedCashAfterItAndAudited() {
        UUID session = opened("500.00").id();
        audit.clear();

        MovementResult deposit = moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "100.00", null);
        MovementResult withdrawal = moveUseCase().record(cashier, requestId, "m-2", session, "withdrawal", "50.00",
                "Pago de proveedor");

        assertThat(deposit.expectedAfter()).isEqualTo(Money.of("600.00", CurrencyCode.HNL));
        assertThat(withdrawal.expectedAfter()).isEqualTo(Money.of("550.00", CurrencyCode.HNL));
        assertThat(withdrawal.movement().type()).isEqualTo("withdrawal");
        assertThat(audit).hasSize(2).allSatisfy(entry -> assertThat(entry.action()).isEqualTo("cashbox.movement.recorded"));
        assertThat(audit.get(1).afterValue()).contains("\"type\":\"withdrawal\"", "\"notesLength\":17")
                .doesNotContain("Pago de proveedor");
    }

    @Test
    void aWithdrawalLargerThanTheExpectedCashIsRefusedAndWritesNothing() {
        UUID session = opened("100.00").id();
        audit.clear();

        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-1", session, "withdrawal", "100.01", null))
                .isInstanceOf(CashExpectedNegativeException.class);

        assertThat(movements.rows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void aMovementOfAnUnknownTypeOrAmountIsRefused() {
        UUID session = opened("100.00").id();
        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-1", session, "refund_out", "1.00", null))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-2", session, "deposit", "0.00", null))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-3", session, "deposit", "1.005", null))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThat(movements.rows).isEmpty();
    }

    @Test
    void aMovementOnAnotherCashiersSessionOrAnUnknownOneLooksTheSame() {
        UUID session = opened("100.00").id();

        assertThatThrownBy(() -> moveUseCase().record(accountant, requestId, "m-1", session, "deposit", "1.00", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-2", UUID.randomUUID(), "deposit", "1.00", null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(movements.rows).isEmpty();
    }

    @Test
    void aMovementOnAClosedSessionIsRefused() {
        UUID session = opened("100.00").id();
        closeUseCase().close(cashier, requestId, "c-1", session, "100.00", null);

        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "1.00", null))
                .isInstanceOf(CashSessionNotOpenException.class);
    }

    @Test
    void theSameMovementKeyAnswersTheSameMovementAndAnotherBodyIsRefused() {
        UUID session = opened("100.00").id();
        MovementResult first = moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "10.00", null);
        audit.clear();

        MovementResult again = moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "10.00", null);

        assertThat(again.movement().id()).isEqualTo(first.movement().id());
        assertThat(movements.rows).hasSize(1);
        assertThat(audit).isEmpty();
        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "11.00", null))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> moveUseCase().record(cashier, requestId, "m-1", session, "withdrawal", "10.00", null))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
    }

    // --- close ---

    @Test
    void theAcceptanceFlowClosesWithADifferenceOfMinusTenPendingApprovalAndAuditsEverything() {
        UUID session = opened("500.00").id();
        moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "100.00", null);
        moveUseCase().record(cashier, requestId, "m-2", session, "withdrawal", "50.00", null);
        audit.clear();

        SessionDetail closed = closeUseCase().close(cashier, requestId, "c-1", session, "540.00", REASON);

        assertThat(closed.status()).isEqualTo("pending_approval");
        assertThat(closed.expected()).isEqualTo(Money.of("550.00", CurrencyCode.HNL));
        assertThat(closed.close().differenceAmount()).isEqualTo(Money.of("-10.00", CurrencyCode.HNL));
        assertThat(closed.close().differenceReason()).isEqualTo(REASON);
        assertThat(sessions.rows.get(session).status()).isEqualTo(CashSessionStatus.PENDING_APPROVAL);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("cashbox.session.closed");
            assertThat(entry.afterValue()).contains("\"declaredAmount\":\"540.0000\"", "\"expectedAmount\":\"550.0000\"",
                    "\"differenceAmount\":\"-10.0000\"", "\"status\":\"pending_approval\"",
                    "\"differenceReasonLength\":" + REASON.length()).doesNotContain("billete");
        });
    }

    @Test
    void aCountThatMatchesClosesTheSession() {
        UUID session = opened("500.00").id();

        SessionDetail closed = closeUseCase().close(cashier, requestId, "c-1", session, "500.00", null);

        assertThat(closed.status()).isEqualTo("closed");
        assertThat(closed.close().differenceAmount().isZero()).isTrue();
        assertThat(closed.close().differenceReason()).isNull();
    }

    @Test
    void aDifferenceWithoutAReasonLeavesTheSessionOpen() {
        UUID session = opened("500.00").id();

        assertThatThrownBy(() -> closeUseCase().close(cashier, requestId, "c-1", session, "400.00", null))
                .isInstanceOf(CashDifferenceReasonRequiredException.class);

        assertThat(sessions.rows.get(session).status()).isEqualTo(CashSessionStatus.OPEN);
        assertThat(closes.rows).isEmpty();
    }

    @Test
    void anotherCashierCannotCloseAndASecondCloseIsRefused() {
        UUID session = opened("500.00").id();

        assertThatThrownBy(() -> closeUseCase().close(accountant, requestId, "c-1", session, "500.00", null))
                .isInstanceOf(ResourceNotFoundException.class);
        closeUseCase().close(cashier, requestId, "c-2", session, "500.00", null);
        assertThatThrownBy(() -> closeUseCase().close(cashier, requestId, "c-3", session, "500.00", null))
                .isInstanceOf(CashSessionNotOpenException.class);
    }

    @Test
    void theSameCloseKeyAnswersTheSameCloseAndAnotherBodyIsRefused() {
        UUID session = opened("500.00").id();
        SessionDetail first = closeUseCase().close(cashier, requestId, "c-1", session, "490.00", REASON);
        audit.clear();

        SessionDetail again = closeUseCase().close(cashier, requestId, "c-1", session, "490.00", REASON);

        assertThat(again).isEqualTo(first);
        assertThat(audit).isEmpty();
        assertThatThrownBy(() -> closeUseCase().close(cashier, requestId, "c-1", session, "491.00", REASON))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> closeUseCase().close(cashier, requestId, "c-1", session, "490.00", REASON + " mas"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
    }

    // --- approval ---

    @Test
    void anotherPersonApprovesAndTheSessionIsClosedWithTheApproverInTheTrail() {
        UUID session = opened("500.00").id();
        closeUseCase().close(cashier, requestId, "c-1", session, "490.00", REASON);
        audit.clear();

        SessionDetail approved = approveUseCase().approve(accountant, requestId, "a-1", session, "Revisado con el cajero");

        assertThat(approved.status()).isEqualTo("closed");
        assertThat(approved.approval().approvedBy()).isEqualTo(accountant.accountId());
        assertThat(sessions.rows.get(session).status()).isEqualTo(CashSessionStatus.CLOSED);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("cashbox.session.approved");
            assertThat(entry.actorId()).isEqualTo(accountant.accountId());
            assertThat(entry.approverId()).isEqualTo(accountant.accountId());
            assertThat(entry.afterValue()).doesNotContain("Revisado");
        });
    }

    @Test
    void theCashierCannotApproveItsOwnCloseAndNothingIsWritten() {
        UUID session = opened("500.00").id();
        closeUseCase().close(cashier, requestId, "c-1", session, "490.00", REASON);
        audit.clear();

        assertThatThrownBy(() -> approveUseCase().approve(cashier, requestId, "a-1", session, "mine"))
                .isInstanceOf(CashApprovalBySameCashierException.class);

        assertThat(approvals.rows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(sessions.rows.get(session).status()).isEqualTo(CashSessionStatus.PENDING_APPROVAL);
    }

    @Test
    void aSessionWithoutDifferenceAndASecondApprovalAreRefused() {
        UUID clean = opened("500.00").id();
        closeUseCase().close(cashier, requestId, "c-1", clean, "500.00", null);
        assertThatThrownBy(() -> approveUseCase().approve(accountant, requestId, "a-1", clean, "ok"))
                .isInstanceOf(CashSessionNotAwaitingApprovalException.class);

        AuthenticatedActor other = actor();
        UUID differing = openUseCase().open(other, requestId, "open-x", "500.00").id();
        closeUseCase().close(other, requestId, "c-2", differing, "400.00", REASON);
        approveUseCase().approve(accountant, requestId, "a-2", differing, "ok");
        assertThatThrownBy(() -> approveUseCase().approve(accountant, requestId, "a-3", differing, "again"))
                .isInstanceOf(CashSessionNotAwaitingApprovalException.class);
    }

    @Test
    void theApprovalKeyIsIdempotentPerApproverAndBody() {
        UUID session = opened("500.00").id();
        closeUseCase().close(cashier, requestId, "c-1", session, "490.00", REASON);
        SessionDetail first = approveUseCase().approve(accountant, requestId, "a-1", session, "ok");
        audit.clear();

        assertThat(approveUseCase().approve(accountant, requestId, "a-1", session, "ok")).isEqualTo(first);
        assertThat(audit).isEmpty();
        assertThatThrownBy(() -> approveUseCase().approve(accountant, requestId, "a-1", session, "other note"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
        assertThatThrownBy(() -> approveUseCase().approve(actor(), requestId, "a-1", session, "ok"))
                .isInstanceOf(IdempotencyPayloadMismatchException.class);
    }

    @Test
    void approvingAnUnknownSessionIs404() {
        assertThatThrownBy(() -> approveUseCase().approve(accountant, requestId, "a-1", UUID.randomUUID(), "ok"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- reads ---

    @Test
    void theCurrentSessionIsTheOpenOneOfTheCashierOr404() {
        GetCurrentSession current = new GetCurrentSession(JdbcStubTransactions.runner(transactionCalls), sessions, details);
        assertThatThrownBy(() -> current.current(cashier, requestId)).isInstanceOf(ResourceNotFoundException.class);

        SessionDetail session = opened("500.00");
        moveUseCase().record(cashier, requestId, "m-1", session.id(), "deposit", "10.00", null);

        SessionDetail found = current.current(cashier, requestId);
        assertThat(found.id()).isEqualTo(session.id());
        assertThat(found.movements()).hasSize(1);
        assertThat(found.expected()).isEqualTo(Money.of("510.00", CurrencyCode.HNL));
        assertThatThrownBy(() -> current.current(accountant, requestId)).isInstanceOf(ResourceNotFoundException.class);

        closeUseCase().close(cashier, requestId, "c-1", session.id(), "510.00", null);
        assertThatThrownBy(() -> current.current(cashier, requestId)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSessionIsReadWithItsMovementsCloseAndApproval() {
        GetSession get = new GetSession(JdbcStubTransactions.runner(transactionCalls), sessions, details, scope);
        UUID session = opened("500.00").id();
        moveUseCase().record(cashier, requestId, "m-1", session, "deposit", "10.00", null);
        closeUseCase().close(cashier, requestId, "c-1", session, "500.00", REASON);
        approveUseCase().approve(accountant, requestId, "a-1", session, "ok");

        SessionDetail detail = get.get(accountant, requestId, session);

        assertThat(detail.status()).isEqualTo("closed");
        assertThat(detail.movements()).hasSize(1);
        assertThat(detail.close().differenceAmount()).isEqualTo(Money.of("-10.00", CurrencyCode.HNL));
        assertThat(detail.approval()).isNotNull();
        assertThatThrownBy(() -> get.get(accountant, requestId, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anActorThatReadsOnlyItsOwnSessionsDoesNotSeeAnotherCashiers() {
        GetSession get = new GetSession(JdbcStubTransactions.runner(transactionCalls), sessions, details, scope);
        UUID session = opened("500.00").id();
        readsEverySession = false;

        assertThat(get.get(cashier, requestId, session).id()).isEqualTo(session);
        assertThatThrownBy(() -> get.get(accountant, requestId, session)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theListIsRestrictedToTheOwnSessionsWhenTheScopeSaysSoWhateverCashierWasAskedFor() {
        ListSessions list = new ListSessions(JdbcStubTransactions.runner(transactionCalls), sessions, closes, approvals,
                scope);
        opened("500.00");
        openUseCase().open(accountant, requestId, "open-2", "100.00");
        PageRequest page = PageRequest.of(0, 50, null, SessionFilter.SORT_FIELDS, "openedAt", false);

        assertThat(list.list(accountant, requestId, new SessionFilter(null, null, null, null), page).items()).hasSize(2);
        readsEverySession = false;
        Page<SessionSummary> mine = list.list(cashier, requestId, new SessionFilter(null, accountant.accountId(), null,
                null), page);

        assertThat(mine.items()).singleElement().satisfies(row -> assertThat(row.cashierId()).isEqualTo(cashier.accountId()));
    }

    @Test
    void theListCarriesTheCloseAmountsAndRefusesBadFilters() {
        ListSessions list = new ListSessions(JdbcStubTransactions.runner(transactionCalls), sessions, closes, approvals,
                scope);
        UUID session = opened("500.00").id();
        closeUseCase().close(cashier, requestId, "c-1", session, "490.00", REASON);
        PageRequest page = PageRequest.of(0, 50, null, SessionFilter.SORT_FIELDS, "openedAt", false);

        Page<SessionSummary> found = list.list(accountant, requestId, new SessionFilter("pending_approval", null, null,
                null), page);

        assertThat(found.items()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("pending_approval");
            assertThat(row.differenceAmount()).isEqualTo(Money.of("-10.00", CurrencyCode.HNL));
            assertThat(row.approvedAt()).isNull();
        });
        assertThatThrownBy(() -> list.list(accountant, requestId, new SessionFilter("bogus", null, null, null), page))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThatThrownBy(() -> list.list(accountant, requestId, new SessionFilter(null, null,
                LocalDate.parse("2027-02-10"), LocalDate.parse("2027-02-09")), page))
                .isInstanceOf(InvalidCashboxValueException.class);
    }

    @Test
    void thePageIsBoundedByTheServer() {
        assertThatThrownBy(() -> PageRequest.of(0, PageRequest.MAX_SIZE + 1, null, SessionFilter.SORT_FIELDS, "openedAt",
                false)).isInstanceOf(InvalidCashboxValueException.class);
        assertThatThrownBy(() -> PageRequest.of(0, 10, "password", SessionFilter.SORT_FIELDS, "openedAt", false))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThat(PageRequest.of(2, 10, "status,asc", SessionFilter.SORT_FIELDS, "openedAt", false).offset())
                .isEqualTo(20);
    }

    // --- fakes ---

    private final class FakeSessions implements CashSessionRepository {
        final Map<UUID, CashSession> rows = new LinkedHashMap<>();
        int inserts;
        CashSession failNextInsertAfterOtherRequestWins;

        @Override
        public void insert(InstitutionId inst, CashSession session) {
            if (failNextInsertAfterOtherRequestWins != null) {
                CashSession winner = failNextInsertAfterOtherRequestWins;
                failNextInsertAfterOtherRequestWins = null;
                rows.put(winner.id(), winner);
                throw new RequestKeyTakenException();
            }
            inserts++;
            rows.put(session.id(), session);
        }

        @Override
        public Optional<CashSession> findByRequestKey(InstitutionId inst, String key) {
            return rows.values().stream().filter(row -> row.requestKey().equals(key)).findFirst();
        }

        @Override
        public Optional<CashSession> findById(InstitutionId inst, UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<CashSession> findForUpdate(InstitutionId inst, UUID id) {
            return findById(inst, id);
        }

        @Override
        public Optional<CashSession> findOpenByCashier(InstitutionId inst, UUID cashierId) {
            return rows.values().stream().filter(row -> row.cashierId().equals(cashierId) && row.isOpen()).findFirst();
        }

        @Override
        public void advanceStatus(InstitutionId inst, UUID id, CashSessionStatus to) {
            CashSession row = rows.get(id);
            rows.put(id, new CashSession(row.id(), row.cashierId(), row.openingFloat(), to, row.openedAt(),
                    row.requestKey(), row.requestHash()));
        }

        @Override
        public Page<CashSession> list(InstitutionId inst, SessionFilter filter, PageRequest page) {
            List<CashSession> matching = rows.values().stream()
                    .filter(row -> filter.status() == null || row.status().code().equals(filter.status()))
                    .filter(row -> filter.cashier() == null || row.cashierId().equals(filter.cashier()))
                    .sorted(Comparator.comparing(CashSession::openedAt)).toList();
            return new Page<>(matching, matching.size(), page.page(), page.size());
        }
    }

    private final class FakeMovements implements CashMovementRepository {
        final List<CashMovement> rows = new ArrayList<>();

        @Override
        public void insert(InstitutionId inst, CashMovement movement) {
            rows.add(movement);
        }

        @Override
        public Optional<CashMovement> findByRequestKey(InstitutionId inst, String key) {
            return rows.stream().filter(row -> row.requestKey().equals(key)).findFirst();
        }

        /** What the database trigger stores: the float plus the movements of the session up to and including this one. */
        @Override
        public Money expectedAfter(InstitutionId inst, UUID movementId) {
            CashMovement target = rows.stream().filter(row -> row.id().equals(movementId)).findFirst().orElseThrow();
            List<CashMovement> upTo = rows.stream().filter(row -> row.sessionId().equals(target.sessionId())).toList();
            return sessions.rows.get(target.sessionId()).expected(upTo.subList(0, upTo.indexOf(target) + 1));
        }

        @Override
        public List<CashMovement> findBySession(InstitutionId inst, UUID sessionId) {
            return rows.stream().filter(row -> row.sessionId().equals(sessionId)).toList();
        }
    }

    private static final class FakeCloses implements CashCloseRepository {
        final Map<UUID, CashClose> rows = new HashMap<>();

        @Override
        public void insert(InstitutionId inst, CashClose close) {
            rows.put(close.sessionId(), close);
        }

        @Override
        public Optional<CashClose> findByRequestKey(InstitutionId inst, String key) {
            return rows.values().stream().filter(row -> row.requestKey().equals(key)).findFirst();
        }

        @Override
        public Optional<CashClose> findBySession(InstitutionId inst, UUID sessionId) {
            return Optional.ofNullable(rows.get(sessionId));
        }

        @Override
        public Map<UUID, CashClose> findBySessions(InstitutionId inst, Collection<UUID> ids) {
            Map<UUID, CashClose> found = new HashMap<>(rows);
            found.keySet().retainAll(ids);
            return found;
        }
    }

    private static final class FakeApprovals implements CashApprovalRepository {
        final Map<UUID, CashApproval> rows = new HashMap<>();

        @Override
        public void insert(InstitutionId inst, CashApproval approval) {
            rows.put(approval.sessionId(), approval);
        }

        @Override
        public Optional<CashApproval> findByRequestKey(InstitutionId inst, String key) {
            return rows.values().stream().filter(row -> row.requestKey().equals(key)).findFirst();
        }

        @Override
        public Optional<CashApproval> findBySession(InstitutionId inst, UUID sessionId) {
            return Optional.ofNullable(rows.get(sessionId));
        }

        @Override
        public Map<UUID, CashApproval> findBySessions(InstitutionId inst, Collection<UUID> ids) {
            Map<UUID, CashApproval> found = new HashMap<>(rows);
            found.keySet().retainAll(ids);
            return found;
        }
    }
}

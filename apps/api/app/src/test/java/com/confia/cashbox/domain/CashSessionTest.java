package com.confia.cashbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * INV-21, INV-22 (the rule of the domain: one session per open), INV-23 and the segregation of duties, on {@link Money} and
 * without a database: the expected cash, the difference in every direction, the movements a session refuses and the approval.
 */
class CashSessionTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final String REASON = "Falto un billete de cien lempiras";
    private static final String NOTES = "Correccion del conteo del turno de la manana";
    private static final String HASH = "a".repeat(64);

    private final UUID cashier = UUID.randomUUID();
    private final UUID approver = UUID.randomUUID();

    private static Money hnl(String amount) {
        return Money.of(amount, CurrencyCode.HNL);
    }

    private CashSession open(String openingFloat) {
        return CashSession.open(UUID.randomUUID(), cashier, hnl(openingFloat), NOW, "open-key", HASH);
    }

    private CashMovement move(CashSession session, MovementType type, String amount, List<CashMovement> existing) {
        String notes = type == MovementType.ADJUSTMENT ? NOTES : null;
        return session.recordMovement(UUID.randomUUID(), type, hnl(amount), notes, existing, cashier, NOW,
                "key-" + existing.size(), HASH);
    }

    private CashClose close(CashSession session, String declared, String reason, List<CashMovement> movements) {
        return session.close(UUID.randomUUID(), hnl(declared), reason, movements, cashier, NOW, "close-key", HASH);
    }

    // --- opening ---

    @Test
    void anOpeningFloatOfZeroIsAllowedAndANegativeOneIsNot() {
        assertThat(open("0.00").openingFloat().isZero()).isTrue();
        assertThat(open("500.00").status()).isEqualTo(CashSessionStatus.OPEN);
        assertThatThrownBy(() -> open("-0.01")).isInstanceOf(InvalidCashboxValueException.class);
    }

    @Test
    void aSessionIsInTheCurrencyOfTheInstitution() {
        assertThatThrownBy(() -> CashSession.open(UUID.randomUUID(), cashier, Money.of("1.00", CurrencyCode.USD), NOW,
                "k", HASH)).isInstanceOf(InvalidCashboxValueException.class);
    }

    // --- the expected cash (INV-23) ---

    @Test
    void theExpectedCashIsTheFloatPlusDepositsMinusWithdrawalsPlusSignedAdjustments() {
        CashSession session = open("500.00");
        List<CashMovement> movements = new ArrayList<>();
        movements.add(move(session, MovementType.DEPOSIT, "100.00", movements));
        movements.add(move(session, MovementType.WITHDRAWAL, "50.00", movements));
        assertThat(session.expected(movements)).isEqualTo(hnl("550.00"));

        movements.add(session.recordMovement(UUID.randomUUID(), MovementType.ADJUSTMENT, hnl("-5.25"), NOTES, movements,
                cashier, NOW, "key-adj", HASH));
        movements.add(session.recordMovement(UUID.randomUUID(), MovementType.ADJUSTMENT, hnl("1.00"), NOTES, movements,
                cashier, NOW, "key-adj-2", HASH));
        assertThat(session.expected(movements)).isEqualTo(hnl("545.75"));
    }

    @Test
    void theExpectedCashOfASessionWithoutMovementsIsItsFloat() {
        assertThat(open("0.00").expected(List.of())).isEqualTo(hnl("0.00"));
    }

    // --- the movements (INV-21) ---

    @Test
    void aWithdrawalOfExactlyWhatTheSessionHoldsIsAllowedAndOneCentMoreIsRefused() {
        CashSession session = open("100.00");

        CashMovement all = move(session, MovementType.WITHDRAWAL, "100.00", List.of());
        assertThat(session.expected(List.of(all)).isZero()).isTrue();
        assertThatThrownBy(() -> move(session, MovementType.WITHDRAWAL, "100.01", List.of()))
                .isInstanceOf(CashExpectedNegativeException.class);
    }

    @Test
    void aNegativeAdjustmentBeyondTheExpectedCashIsRefusedToo() {
        CashSession session = open("10.00");
        assertThatThrownBy(() -> session.recordMovement(UUID.randomUUID(), MovementType.ADJUSTMENT, hnl("-10.01"), NOTES,
                List.of(), cashier, NOW, "k", HASH)).isInstanceOf(CashExpectedNegativeException.class);
    }

    @Test
    void depositsAndWithdrawalsMustBePositiveAndAdjustmentsMustNotBeZero() {
        CashSession session = open("100.00");
        for (MovementType type : List.of(MovementType.DEPOSIT, MovementType.WITHDRAWAL)) {
            assertThatThrownBy(() -> move(session, type, "0.00", List.of()))
                    .isInstanceOf(InvalidCashboxValueException.class);
            assertThatThrownBy(() -> move(session, type, "-1.00", List.of()))
                    .isInstanceOf(InvalidCashboxValueException.class);
        }
        assertThatThrownBy(() -> move(session, MovementType.ADJUSTMENT, "0.00", List.of()))
                .isInstanceOf(InvalidCashboxValueException.class);
    }

    @Test
    void anAdjustmentNeedsNotesOfTwentyToFiveHundredCharacters() {
        CashSession session = open("100.00");
        for (String notes : new String[] {null, "", "too short", "x".repeat(501), NOTES + "\u0007"}) {
            assertThatThrownBy(() -> session.recordMovement(UUID.randomUUID(), MovementType.ADJUSTMENT, hnl("1.00"), notes,
                    List.of(), cashier, NOW, "k", HASH)).isInstanceOf(InvalidCashboxValueException.class);
        }
        CashMovement fine = session.recordMovement(UUID.randomUUID(), MovementType.ADJUSTMENT, hnl("1.00"),
                "  " + NOTES + "  ", List.of(), cashier, NOW, "k", HASH);
        assertThat(fine.notes()).isEqualTo(NOTES);
    }

    @Test
    void theNotesOfADepositAreOptionalButBounded() {
        CashSession session = open("100.00");
        assertThat(session.recordMovement(UUID.randomUUID(), MovementType.DEPOSIT, hnl("1.00"), null, List.of(), cashier,
                NOW, "k", HASH).notes()).isNull();
        assertThat(session.recordMovement(UUID.randomUUID(), MovementType.DEPOSIT, hnl("1.00"), "   ", List.of(), cashier,
                NOW, "k", HASH).notes()).isNull();
        assertThatThrownBy(() -> session.recordMovement(UUID.randomUUID(), MovementType.DEPOSIT, hnl("1.00"),
                "x".repeat(501), List.of(), cashier, NOW, "k", HASH)).isInstanceOf(InvalidCashboxValueException.class);
    }

    @Test
    void aMovementInAnotherCurrencyIsRefused() {
        CashSession session = open("100.00");
        assertThatThrownBy(() -> session.recordMovement(UUID.randomUUID(), MovementType.DEPOSIT,
                Money.of("1.00", CurrencyCode.USD), null, List.of(), cashier, NOW, "k", HASH))
                .isInstanceOf(InvalidCashboxValueException.class);
    }

    @Test
    void aClosedOrPendingSessionAcceptsNoMovementAndNoSecondClose() {
        CashSession session = open("100.00");
        CashClose clean = close(session, "100.00", null, List.of());
        CashSession closed = session.afterClose(clean);
        assertThat(closed.status()).isEqualTo(CashSessionStatus.CLOSED);
        assertThatThrownBy(() -> move(closed, MovementType.DEPOSIT, "1.00", List.of()))
                .isInstanceOf(CashSessionNotOpenException.class);
        assertThatThrownBy(() -> close(closed, "100.00", null, List.of())).isInstanceOf(CashSessionNotOpenException.class);

        CashSession pending = session.afterClose(close(session, "90.00", REASON, List.of()));
        assertThat(pending.status()).isEqualTo(CashSessionStatus.PENDING_APPROVAL);
        assertThatThrownBy(() -> move(pending, MovementType.DEPOSIT, "1.00", List.of()))
                .isInstanceOf(CashSessionNotOpenException.class);
        assertThatThrownBy(() -> close(pending, "90.00", REASON, List.of()))
                .isInstanceOf(CashSessionNotOpenException.class);
    }

    // --- the close (INV-23) ---

    @Test
    void theAcceptanceExampleDeclaring540WhenTheExpectedIs550LeavesADifferenceOfMinus10PendingApproval() {
        CashSession session = open("500.00");
        List<CashMovement> movements = new ArrayList<>();
        movements.add(move(session, MovementType.DEPOSIT, "100.00", movements));
        movements.add(move(session, MovementType.WITHDRAWAL, "50.00", movements));

        CashClose close = close(session, "540.00", REASON, movements);

        assertThat(close.expectedAmount()).isEqualTo(hnl("550.00"));
        assertThat(close.differenceAmount()).isEqualTo(hnl("-10.00"));
        assertThat(close.hasDifference()).isTrue();
        assertThat(close.resultingStatus()).isEqualTo(CashSessionStatus.PENDING_APPROVAL);
        assertThat(close.differenceReason()).isEqualTo(REASON);
    }

    @Test
    void aCountThatMatchesClosesTheSessionAndDropsAnyReason() {
        CashSession session = open("500.00");

        CashClose close = close(session, "500.00", "typed but not needed here", List.of());

        assertThat(close.differenceAmount().isZero()).isTrue();
        assertThat(close.differenceReason()).isNull();
        assertThat(close.resultingStatus()).isEqualTo(CashSessionStatus.CLOSED);
    }

    @Test
    void aPositiveDifferenceAlsoNeedsAReasonAndAnApprover() {
        CashSession session = open("500.00");

        CashClose close = close(session, "510.00", REASON, List.of());

        assertThat(close.differenceAmount()).isEqualTo(hnl("10.00"));
        assertThat(close.resultingStatus()).isEqualTo(CashSessionStatus.PENDING_APPROVAL);
    }

    @Test
    void aDifferenceWithoutAReasonOrWithAShortOneIsRefused() {
        CashSession session = open("500.00");
        for (String reason : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> close(session, "490.00", reason, List.of()))
                    .isInstanceOf(CashDifferenceReasonRequiredException.class);
        }
        assertThatThrownBy(() -> close(session, "490.00", "too short", List.of()))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThatThrownBy(() -> close(session, "490.00", "x".repeat(501), List.of()))
                .isInstanceOf(InvalidCashboxValueException.class);
    }

    @Test
    void aDeclaredAmountThatIsNegativeIsRefusedAndZeroIsAllowed() {
        CashSession session = open("0.00");
        assertThatThrownBy(() -> close(session, "-1.00", REASON, List.of()))
                .isInstanceOf(InvalidCashboxValueException.class);
        assertThat(close(session, "0.00", null, List.of()).resultingStatus()).isEqualTo(CashSessionStatus.CLOSED);
    }

    // --- the approval ---

    @Test
    void theCashierCannotApproveItsOwnCloseAndAnotherPersonCan() {
        CashSession session = open("500.00");
        CashSession pending = session.afterClose(close(session, "490.00", REASON, List.of()));

        assertThatThrownBy(() -> pending.approve(UUID.randomUUID(), cashier, "ok", NOW, "k", HASH))
                .isInstanceOf(CashApprovalBySameCashierException.class);
        CashApproval approval = pending.approve(UUID.randomUUID(), approver, "  Revisado con el cajero  ", NOW, "k",
                HASH);
        assertThat(approval.approvedBy()).isEqualTo(approver);
        assertThat(approval.note()).isEqualTo("Revisado con el cajero");
        assertThat(pending.afterApproval().status()).isEqualTo(CashSessionStatus.CLOSED);
    }

    @Test
    void theCashierIsRefusedEvenWhenTheSessionIsNotPending() {
        CashSession closed = open("500.00").afterClose(close(open("500.00"), "500.00", null, List.of()));
        CashSession own = new CashSession(closed.id(), cashier, closed.openingFloat(), CashSessionStatus.CLOSED, NOW, "k",
                HASH);
        assertThatThrownBy(() -> own.approve(UUID.randomUUID(), cashier, "ok", NOW, "k", HASH))
                .isInstanceOf(CashApprovalBySameCashierException.class);
    }

    @Test
    void aSessionWithoutDifferenceOrAlreadyApprovedIsNotApprovedAgain() {
        CashSession session = open("500.00");
        CashSession closed = session.afterClose(close(session, "500.00", null, List.of()));
        assertThatThrownBy(() -> closed.approve(UUID.randomUUID(), approver, "ok", NOW, "k", HASH))
                .isInstanceOf(CashSessionNotAwaitingApprovalException.class);

        CashSession pending = session.afterClose(close(session, "400.00", REASON, List.of()));
        CashSession approved = pending.afterApproval();
        assertThatThrownBy(() -> approved.approve(UUID.randomUUID(), approver, "again", NOW, "k2", HASH))
                .isInstanceOf(CashSessionNotAwaitingApprovalException.class);
        assertThatThrownBy(() -> session.approve(UUID.randomUUID(), approver, "open", NOW, "k3", HASH))
                .isInstanceOf(CashSessionNotAwaitingApprovalException.class);
    }

    @Test
    void theApprovalNoteIsRequiredAndBounded() {
        CashSession session = open("500.00");
        CashSession pending = session.afterClose(close(session, "490.00", REASON, List.of()));
        for (String note : new String[] {null, "", "  ", "x".repeat(501)}) {
            assertThatThrownBy(() -> pending.approve(UUID.randomUUID(), approver, note, NOW, "k", HASH))
                    .isInstanceOf(InvalidCashboxValueException.class);
        }
    }

    // --- the amounts the staff member types ---

    @Test
    void amountsAreDecimalStringsOfTheMinorUnitAtMost() {
        assertThat(CashAmounts.parse("10.5")).isEqualTo(hnl("10.50"));
        assertThat(CashAmounts.parse("-3.25")).isEqualTo(hnl("-3.25"));
        for (String bad : new String[] {"10.005", "1e3", "+1", " 1", "1,000", "abc", "", "99999999999.00"}) {
            assertThatThrownBy(() -> CashAmounts.parse(bad)).as(bad).isInstanceOf(InvalidCashboxValueException.class);
        }
        assertThatThrownBy(() -> CashAmounts.parse(null)).isInstanceOf(InvalidCashboxValueException.class);
        assertThatThrownBy(() -> CashAmounts.parseNotNegative("-0.01")).isInstanceOf(InvalidCashboxValueException.class);
        assertThat(CashAmounts.parseNotNegative("0").isZero()).isTrue();
    }
}

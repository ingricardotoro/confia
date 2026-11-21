package com.confia.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** INV-19 and the rest of what the edge of a registration asks, without a database. */
class PaymentRequestTest {

    private static final LocalDate TODAY = LocalDate.parse("2027-02-10");
    private final UUID student = UUID.randomUUID();

    private PaymentRequest cash(String amount) {
        return PaymentRequest.of(student, null, "CASH", amount, TODAY, TODAY, null, null, null);
    }

    private PaymentRequest transfer(String reference, String bank) {
        return PaymentRequest.of(student, null, "BANK_TRANSFER", "100.00", TODAY, TODAY, reference, bank, null);
    }

    @Test
    void aValidCashPaymentIsTheAmountInLempirasToTheCent() {
        PaymentRequest request = cash("1500.00");

        assertThat(request.amount()).isEqualTo(Money.of("1500.00", CurrencyCode.HNL));
        assertThat(request.method()).isEqualTo(PaymentMethod.CASH);
        assertThat(request.transfer()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1.00", "-0.01", "10.001", "1.0000001", "abc", "", "1e3", "+5.00", " 5.00"})
    void zeroNegativeSubCentAndMalformedAmountsAreRefused(String amount) {
        assertThatThrownBy(() -> cash(amount)).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aMissingAmountIsRefused() {
        assertThatThrownBy(() -> cash(null)).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void theSmallestAmountIsOneCentAndTheBoundIsExact() {
        assertThat(cash("0.01").amount()).isEqualTo(Money.of("0.01", CurrencyCode.HNL));
        assertThat(cash("9999999999.99").amount().isPositive()).isTrue();
    }

    @Test
    void anotherCurrencyIsRefusedByTheValueRule() {
        assertThatThrownBy(() -> PaymentAmounts.requireValid(Money.of("10.00", CurrencyCode.USD)))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentAmounts.requireValid(Money.of("10.0050", CurrencyCode.HNL)))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aTransferCannotHaveBeenReceivedInTheFutureNorBeforeTheYearTwoThousand() {
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "BANK_TRANSFER", "1.00", TODAY.plusDays(1), TODAY,
                "BAC-123456", "BAC", null)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "BANK_TRANSFER", "1.00", LocalDate.parse("1999-12-31"),
                TODAY, "BAC-123456", "BAC", null)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "BANK_TRANSFER", "1.00", null, TODAY, "BAC-123456",
                "BAC", null)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(PaymentRequest.of(student, null, "BANK_TRANSFER", "1.00", TODAY.minusDays(30), TODAY, "BAC-123456",
                "BAC", null).receivedOn()).isEqualTo(TODAY.minusDays(30));
    }

    @Test
    void cashIsReceivedOnTheBusinessDayOfTheOpenSessionAndNoOtherDay() {
        assertThat(cash("1.00").receivedOn()).isEqualTo(TODAY);
        for (LocalDate other : new LocalDate[] {TODAY.minusDays(1), TODAY.minusDays(30), TODAY.plusDays(1)}) {
            assertThatThrownBy(() -> PaymentRequest.of(student, null, "CASH", "1.00", other, TODAY, null, null, null))
                    .isInstanceOfSatisfying(InvalidPaymentsValueException.class, e -> {
                        assertThat(e.field()).isEqualTo("receivedOn");
                        assertThat(e.reason()).isEqualTo("cash-received-today");
                    });
        }
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "CASH", "1.00", null, TODAY, null, null, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void anUnknownOrMissingMethodAndAMissingStudentAreRefused() {
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "CARD", "1.00", TODAY, TODAY, null, null, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "cash", "1.00", TODAY, TODAY, null, null, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentRequest.of(null, null, "CASH", "1.00", TODAY, TODAY, null, null, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aTransferNeedsItsReferenceAndItsBank() {
        assertThat(transfer("BAC-123456", "BAC Credomatic").transfer().reference()).isEqualTo("BAC-123456");
        assertThatThrownBy(() -> transfer(null, "BAC")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> transfer("BAC-123456", null)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> transfer("BAC-123456", "  ")).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void theReferenceIsFourToFortyCharactersAfterStrippingTheBorders() {
        assertThatThrownBy(() -> transfer("abc", "BAC")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> transfer("  abc  ", "BAC")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(transfer("abcd", "BAC").transfer().reference()).isEqualTo("abcd");
        assertThat(transfer("  abcd  ", "BAC").transfer().reference()).isEqualTo("abcd");
        assertThat(transfer("x".repeat(40), "BAC").transfer().reference()).hasSize(40);
        assertThatThrownBy(() -> transfer("x".repeat(41), "BAC")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> transfer("abc\u0000def", "BAC")).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void theBankIsAtMostEightyCharacters() {
        assertThat(transfer("abcd", "B".repeat(80)).transfer().bankName()).hasSize(80);
        assertThatThrownBy(() -> transfer("abcd", "B".repeat(81))).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void cashTakesNoReferenceAndNoBankAndTheyAreNeverIgnored() {
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "CASH", "1.00", TODAY, TODAY, "REF-1234", null, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "CASH", "1.00", TODAY, TODAY, null, "BAC", null))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(PaymentRequest.of(student, null, "CASH", "1.00", TODAY, TODAY, " ", "", null).transfer()).isNull();
    }

    @Test
    void theNotesAreOptionalUpToFiveHundredCharactersAndAreStripped() {
        assertThat(PaymentRequest.of(student, null, "CASH", "1.00", TODAY, TODAY, null, null, "  ").notes()).isNull();
        assertThat(PaymentRequest.of(student, null, "CASH", "1.00", TODAY, TODAY, null, null, " pago de enero ").notes())
                .isEqualTo("pago de enero");
        assertThat(PaymentRequest.of(student, null, "CASH", "1.00", TODAY, TODAY, null, null, "n".repeat(500)).notes())
                .hasSize(500);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "CASH", "1.00", TODAY, TODAY, null, null,
                "n".repeat(501))).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void whatTheRequestAsksDeterminesTheCanonicalTextAndHidesPersonalData() {
        PaymentRequest first = PaymentRequest.of(student, null, "CASH", "10.00", TODAY, TODAY, null, null, "nota privada");
        PaymentRequest same = PaymentRequest.of(student, null, "CASH", "10.0", TODAY, TODAY, null, null, "nota privada");
        PaymentRequest other = PaymentRequest.of(student, null, "CASH", "10.01", TODAY, TODAY, null, null, "nota privada");

        assertThat(first.canonical()).isEqualTo(same.canonical()).isNotEqualTo(other.canonical());
        assertThat(first.toString()).doesNotContain("nota privada");
        assertThat(transfer("BAC-123456", "BAC").transfer().toString()).doesNotContain("BAC-123456").contains("3456");
    }

    @Test
    void movingTheSeparatorBetweenTheReferenceAndTheBankChangesWhatTheKeyVouchesFor() {
        PaymentRequest first = transfer("ABCD|EFGH", "BANK");
        PaymentRequest moved = transfer("ABCD", "EFGH|BANK");

        assertThat(first.canonical()).isNotEqualTo(moved.canonical());
        assertThat(transfer("ABCD", "BANK").canonical()).isNotEqualTo(transfer("ABCDB", "ANK").canonical());
    }

    @Test
    void theMaskedReferenceNeverRevealsItWholeAndShowsAtMostItsLastFourCharacters() {
        assertThat(TransferReference.of("BAC-123456", "BAC").masked()).isEqualTo("****3456");
        assertThat(TransferReference.of("abcd", "BAC").masked()).isEqualTo("****cd").doesNotContain("ab");
        assertThat(TransferReference.of("abcdefg", "BAC").masked()).isEqualTo("****fg");
        assertThat(TransferReference.of("abcdefgh", "BAC").masked()).isEqualTo("****efgh");
    }

    @Test
    void theRejectionReasonIsTwentyToFiveHundredCharactersAndNeverPrintsItself() {
        assertThat(RejectionReason.of("  El banco no registra esta transferencia  ").text())
                .isEqualTo("El banco no registra esta transferencia");
        assertThatThrownBy(() -> RejectionReason.of("demasiado corto")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> RejectionReason.of(null)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(RejectionReason.of("x".repeat(20)).text()).hasSize(20);
        assertThat(RejectionReason.of("x".repeat(500)).text()).hasSize(500);
        assertThatThrownBy(() -> RejectionReason.of("x".repeat(501))).isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(RejectionReason.of("Motivo con el nombre de alguien").toString()).doesNotContain("alguien");
    }
}

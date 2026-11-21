package com.confia.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.CurrencyCode;
import com.confia.kernel.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Free text, transfer reference, decision and request invariants not reached by the scenario tests. */
class PaymentFieldRulesTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2027-02-10");

    @Test
    void freeTextIsStrippedAndBoundedInCodePointsNotInChars() {
        assertThat(FreeText.of("  abcd  ", 4, 4)).isEqualTo("abcd");
        assertThatThrownBy(() -> FreeText.of("abc", 4, 10)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> FreeText.of("abcde", 1, 4)).isInstanceOf(InvalidPaymentsValueException.class);
        // One emoji is one code point (two chars): it fits a maximum of one.
        assertThat(FreeText.of("😀", 1, 1)).isEqualTo("😀");
        assertThatThrownBy(() -> FreeText.of(null, 0, 10)).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void freeTextRefusesControlAndFormatCharacters() {
        assertThatThrownBy(() -> FreeText.of("ab\ncd", 1, 20)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> FreeText.of("ab‮cd", 1, 20)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> FreeText.of("ab​cd", 1, 20)).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aTransferReferenceNeedsBothParts() {
        assertThatThrownBy(() -> new TransferReference(null, "BAC")).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> new TransferReference("REF-1234", null))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void theMaskShowsTwoCharactersBelowEightAndFourFromEight() {
        assertThat(new TransferReference("ABCDEFG", "BAC").masked()).isEqualTo("****FG");
        assertThat(new TransferReference("ABCDEFGH", "BAC").masked()).isEqualTo("****EFGH");
        assertThat(new TransferReference("ABCDEFGH", "BAC").toString()).doesNotContain("ABCD").contains("****EFGH");
    }

    @Test
    void aTransferRequestWithoutItsReferenceOrACashRequestWithOneCannotBeBuilt() {
        Money amount = Money.of("10.00", CurrencyCode.HNL);
        UUID student = UUID.randomUUID();
        TransferReference reference = new TransferReference("REF-1234", "BAC");

        assertThatThrownBy(() -> new PaymentRequest(student, null, PaymentMethod.BANK_TRANSFER, amount, TODAY, null, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> new PaymentRequest(student, null, PaymentMethod.CASH, amount, TODAY, reference, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThat(new PaymentRequest(student, null, PaymentMethod.CASH, amount, TODAY, null, null).transfer()).isNull();
    }

    @Test
    void theCanonicalFormOfARequestWithAGuardianDiffersFromOneWithout() {
        UUID student = UUID.randomUUID();
        UUID guardian = UUID.randomUUID();
        PaymentRequest without = PaymentRequest.of(student, null, "CASH", "10.00", TODAY, TODAY, null, null, null);
        PaymentRequest with = PaymentRequest.of(student, guardian, "CASH", "10.00", TODAY, TODAY, null, null, null);

        assertThat(with.canonical()).contains(guardian.toString()).isNotEqualTo(without.canonical());
        assertThat(with.toString()).contains(student.toString());
    }

    @Test
    void aBlankReferenceOrBankOnCashCountsAsAbsentButARealOneIsRefused() {
        UUID student = UUID.randomUUID();

        assertThat(PaymentRequest.of(student, null, "CASH", "10.00", TODAY, TODAY, "  ", " ", null).transfer())
                .isNull();
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "CASH", "10.00", TODAY, TODAY, null, "BAC", null))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aTransferMayBeReceivedOnTheEarliestDateAndOnTodayButNotOnTheDayBeforeOrAfter() {
        UUID student = UUID.randomUUID();
        LocalDate earliest = LocalDate.of(2000, 1, 1);

        assertThat(PaymentRequest.of(student, null, "BANK_TRANSFER", "10.00", earliest, TODAY, "REF-1234", "BAC", null)
                .receivedOn()).isEqualTo(earliest);
        assertThat(PaymentRequest.of(student, null, "BANK_TRANSFER", "10.00", TODAY, TODAY, "REF-1234", "BAC", null)
                .receivedOn()).isEqualTo(TODAY);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "BANK_TRANSFER", "10.00", earliest.minusDays(1), TODAY,
                "REF-1234", "BAC", null)).isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "BANK_TRANSFER", "10.00", TODAY.plusDays(1), TODAY,
                "REF-1234", "BAC", null)).isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aMissingReceivedDateOrTodayIsRefused() {
        UUID student = UUID.randomUUID();

        assertThatThrownBy(() -> PaymentRequest.of(student, null, "CASH", "10.00", null, TODAY, null, null, null))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> PaymentRequest.of(student, null, "BANK_TRANSFER", "10.00", TODAY, null, "REF-1234",
                "BAC", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aDecisionConfirmsWithItsPostingAndRejectsWithItsReasonButNeverBoth() {
        UUID id = UUID.randomUUID();
        UUID ledger = UUID.randomUUID();

        assertThat(new PaymentDecision(id, id, DecisionKind.CONFIRMED, null, ledger, id, NOW, "k", "h").kind())
                .isEqualTo(DecisionKind.CONFIRMED);
        assertThat(new PaymentDecision(id, id, DecisionKind.REJECTED, "reason", null, id, NOW, "k", "h").reason())
                .isEqualTo("reason");
        assertThatThrownBy(() -> new PaymentDecision(id, id, DecisionKind.CONFIRMED, "reason", ledger, id, NOW, "k", "h"))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, id, DecisionKind.REJECTED, "reason", ledger, id, NOW, "k", "h"))
                .isInstanceOf(InvalidPaymentsValueException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, id, DecisionKind.REJECTED, null, null, id, NOW, "k", "h"))
                .isInstanceOf(InvalidPaymentsValueException.class);
    }

    @Test
    void aDecisionAnswersOnlyItsOwnHashAndPrintsNeitherReasonNorKey() {
        UUID id = UUID.randomUUID();
        PaymentDecision decision =
                new PaymentDecision(id, id, DecisionKind.REJECTED, "secret reason", null, id, NOW, "secret-key", "hash");

        assertThat(decision.answers("hash")).isTrue();
        assertThat(decision.answers("other")).isFalse();
        assertThat(decision.toString()).doesNotContain("secret").contains("REJECTED");
    }

    @Test
    void aDecisionRefusesEveryMissingMandatoryPart() {
        UUID id = UUID.randomUUID();
        UUID ledger = UUID.randomUUID();

        assertThatThrownBy(() -> new PaymentDecision(null, id, DecisionKind.CONFIRMED, null, ledger, id, NOW, "k", "h"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, null, DecisionKind.CONFIRMED, null, ledger, id, NOW, "k", "h"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, id, null, null, ledger, id, NOW, "k", "h"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, id, DecisionKind.CONFIRMED, null, ledger, null, NOW, "k", "h"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, id, DecisionKind.CONFIRMED, null, ledger, id, null, "k", "h"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, id, DecisionKind.CONFIRMED, null, ledger, id, NOW, null, "h"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PaymentDecision(id, id, DecisionKind.CONFIRMED, null, ledger, id, NOW, "k", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aTransferAlreadyConfirmedOrRejectedIsNotDecidableEvenByAnotherPerson() {
        UUID cashier = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        PaymentRequest request = PaymentRequest.of(UUID.randomUUID(), null, "BANK_TRANSFER", "10.00", TODAY, TODAY,
                "REF-1234", "BAC", null);
        Payment declared = Payment.declaredTransfer(UUID.randomUUID(), request, cashier, NOW, "k", "h");
        Payment rejected = declared.decided(declared.reject(UUID.randomUUID(), admin,
                RejectionReason.of("the reference does not match"), NOW, "k2", "h2"));

        assertThat(declared.isDeclared()).isTrue();
        assertThat(rejected.isDeclared()).isFalse();
        assertThatThrownBy(() -> rejected.requireDecidableBy(admin)).isInstanceOf(PaymentNotDeclaredException.class);
        assertThatThrownBy(() -> declared.requireDecidableBy(null)).isInstanceOf(NullPointerException.class);
    }
}

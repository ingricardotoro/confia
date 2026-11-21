package com.confia.payments.domain;

import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * What a cashier asks to register, validated before anything is written: the method, an amount greater than zero in HNL with at
 * most two decimals (INV-19), the day the money was received (not in the future, not before the year 2000; cash only today, the business day of the open session), and what the method
 * takes. A transfer needs its bank reference and bank; cash takes neither (a field the method does not take is refused, never
 * ignored). The notes are optional free text of up to 500 characters and may name a person: {@code toString} hides them.
 */
public record PaymentRequest(UUID studentId, UUID guardianId, PaymentMethod method, Money amount, LocalDate receivedOn,
        TransferReference transfer, String notes) {

    public static final int NOTES_MAX = 500;
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);

    public PaymentRequest {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(receivedOn, "receivedOn");
        if ((method == PaymentMethod.BANK_TRANSFER) != (transfer != null)) {
            throw new InvalidPaymentsValueException();
        }
    }

    /**
     * Validates the raw values of a request.
     *
     * @param today the day it is in the institution; the money cannot have been received after it
     * @throws InvalidPaymentsValueException when any value breaks a rule
     */
    public static PaymentRequest of(UUID studentId, UUID guardianId, String method, String amount, LocalDate receivedOn,
            LocalDate today, String reference, String bankName, String notes) {
        PaymentMethod paymentMethod = PaymentMethod.ofName(method).orElseThrow(InvalidPaymentsValueException::new);
        Money money = PaymentAmounts.parse(amount);
        if (receivedOn == null || studentId == null) {
            throw new InvalidPaymentsValueException();
        }
        if (paymentMethod == PaymentMethod.CASH && !receivedOn.equals(Objects.requireNonNull(today, "today"))) {
            // Cash is collected in the open session of the cashier, and a session is of one business day (security audit MEDIO-2).
            throw new InvalidPaymentsValueException("receivedOn", "cash-received-today");
        }
        if (receivedOn.isAfter(Objects.requireNonNull(today, "today")) || receivedOn.isBefore(EARLIEST)) {
            throw new InvalidPaymentsValueException();
        }
        boolean hasTransferData = !isBlank(reference) || !isBlank(bankName);
        TransferReference transfer = null;
        if (paymentMethod == PaymentMethod.BANK_TRANSFER) {
            transfer = TransferReference.of(reference, bankName);
        } else if (hasTransferData) {
            throw new InvalidPaymentsValueException();
        }
        String text = isBlank(notes) ? null : FreeText.of(notes, 1, NOTES_MAX);
        return new PaymentRequest(studentId, guardianId, paymentMethod, money, receivedOn, transfer, text);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * What the request asked, as one text: the idempotency key vouches for exactly this (the actor is added by the caller). Every
     * free field is length-prefixed, so moving a separator between two fields never yields the same text.
     */
    public String canonical() {
        return field(studentId.toString()) + field(guardianId == null ? null : guardianId.toString())
                + field(method.name()) + field(amount.toPlainString()) + field(amount.currency().name())
                + field(receivedOn.toString())
                + (transfer == null ? field(null) + field(null) : field(transfer.reference()) + field(transfer.bankName()))
                + field(notes);
    }

    private static String field(String value) {
        return value == null ? "~;" : value.length() + ":" + value + ";";
    }

    @Override
    public String toString() {
        return "PaymentRequest[studentId=" + studentId + ", method=" + method + "]";
    }
}

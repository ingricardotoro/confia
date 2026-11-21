package com.confia.payments.application;

import com.confia.kernel.Money;
import com.confia.payments.domain.Payment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A payment as a list shows it. The notes are not part of it. The reference is the one the cashier typed, shown to the staff
 * that may read the payment, so that a transfer can be matched with the bank; it never reaches a log through {@code toString}.
 */
public record PaymentSummary(UUID id, UUID studentId, String studentCode, String method, String status, Money amount,
        LocalDate receivedOn, String reference, String bankName, UUID registeredBy, Instant registeredAt) {

    static PaymentSummary of(Payment payment, String studentCode) {
        return new PaymentSummary(payment.id(), payment.studentId(), studentCode, payment.method().name(),
                payment.status().code(), payment.amount(), payment.receivedOn(),
                payment.transfer() == null ? null : payment.transfer().reference(),
                payment.transfer() == null ? null : payment.transfer().bankName(), payment.registeredBy(),
                payment.registeredAt());
    }

    @Override
    public String toString() {
        return "PaymentSummary[id=" + id + ", method=" + method + ", status=" + status + "]";
    }
}

package com.confia.payments.application;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The filters of the list of payments: the student, the status code ({@code declared}, {@code confirmed} or {@code rejected}), the
 * method ({@code CASH} or {@code BANK_TRANSFER}) and the days on which the money was received, both ends included. A reader
 * limited to its own payments is restricted to the ones it registered, whatever it asked for. Every member may be null.
 */
public record PaymentFilter(UUID studentId, String status, String method, LocalDate from, LocalDate to,
        UUID registeredBy) {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("registeredAt", "receivedOn", "amount");

    /** The same filter restricted to the payments that one staff member registered. */
    public PaymentFilter onlyRegisteredBy(UUID account) {
        return new PaymentFilter(studentId, status, method, from, to, account);
    }
}

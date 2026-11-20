package com.confia.cashbox.application;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * The filters of the list of sessions: the status code ({@code open}, {@code pending_approval} or {@code closed}), the cashier and the days (in the time zone of the institution) on which the
 * session was opened, both ends included. Every member may be null.
 */
public record SessionFilter(String status, UUID cashier, LocalDate from, LocalDate to) {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("openedAt", "status");

    /** The same filter restricted to one cashier. */
    public SessionFilter onlyCashier(UUID account) {
        return new SessionFilter(status, account, from, to);
    }
}

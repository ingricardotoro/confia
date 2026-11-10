package com.confia.notifications.application;

/**
 * The port through which a send attempt is recorded in the delivery log
 * ({@code notifications_email_delivery}; transactional-email-adapter design.md decision 7). The log is
 * insert-only: an attempt is one new row, never an update of an earlier one.
 *
 * <p>The implementation never opens a transaction of its own (ADR-0015 rule 7): the caller records the
 * attempt inside a transaction of its own choosing, after the send has finished, so a slow or failing
 * log never holds a connection across the call to the mail server.
 */
public interface EmailDeliveryLog {

    /** Appends one row for {@code attempt}. Contains no address, subject, body or parameter. */
    void record(EmailDeliveryAttempt attempt);
}

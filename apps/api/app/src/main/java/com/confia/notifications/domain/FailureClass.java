package com.confia.notifications.domain;

/**
 * The closed list of reasons a send can fail (specs/notifications/spec.md, conventions). It is all
 * a caller, a log line and the delivery log ever learn about a failure: never the message, the cause
 * or the text of the mail server, which can contain the recipient's address.
 *
 * <p>The {@link #code()} values are the ones the {@code failure_class} column of {@code
 * notifications_email_delivery} will accept.
 */
public enum FailureClass {

    /** No connection could be made to the mail server. */
    UNAVAILABLE("unavailable"),
    /** A connection, read or write exceeded its bounded wait. */
    TIMEOUT("timeout"),
    /** STARTTLS was missing or the server certificate was not valid for the host. */
    TLS("tls"),
    /** The server refused the SMTP credentials. */
    AUTHENTICATION("authentication"),
    /** The server refused the sender, the recipient or the message data. */
    REJECTED("rejected"),
    /** Anything else. */
    UNKNOWN("unknown");

    private final String code;

    FailureClass(String code) {
        this.code = code;
    }

    /** The lower-case code stored in the delivery log and written in log lines. */
    public String code() {
        return code;
    }
}

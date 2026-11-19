package com.confia.cashbox.application;

/**
 * An insert lost a race against another request: the Idempotency-Key or the one-open-session rule was taken a moment ago. The
 * caller starts again in a new transaction and finds what the other request wrote.
 */
public final class RequestKeyTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RequestKeyTakenException() {
        super("the request key or the open session was taken by another request", null, false, false);
    }
}

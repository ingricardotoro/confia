package com.confia.scholarships.application;

/** Another request took the Idempotency-Key a moment ago: the caller starts again and finds the winner by key. */
public final class RequestKeyTakenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RequestKeyTakenException() {
        super("the request key is already in use", null, false, false);
    }
}

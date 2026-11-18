package com.confia.charges.domain;

import java.util.Objects;

/**
 * The refusal of one charge by a rule of the engine: carries the {@link AccrualErrorCode} and nothing else. It is control
 * flow between the pure rules and the use case that records the error of the charge, never an error of the request, so it
 * is not a domain exception of the edge and has no stack trace.
 */
public final class AccrualFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient AccrualErrorCode errorCode;

    public AccrualFailure(AccrualErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode").name(), null, false, false);
        this.errorCode = errorCode;
    }

    public AccrualErrorCode errorCode() {
        return errorCode;
    }
}

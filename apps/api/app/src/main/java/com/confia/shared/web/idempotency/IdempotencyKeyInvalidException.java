package com.confia.shared.web.idempotency;

import com.confia.kernel.DomainException;

/**
 * An {@code Idempotency-Key} the edge cannot accept: repeated, longer than {@value
 * IdempotencyKeyInterceptor#MAX_KEY_LENGTH} characters or with a character outside visible ASCII
 * ({@code 0x21} to {@code 0x7E}). It answers {@code 400 validation-failed}, the code of every
 * malformed request, and not {@code idempotency-key-missing}, which means there is no usable key
 * at all (web-edge-foundations design.md, decision 19). The message never repeats the value: a
 * key is client input chosen to be logged.
 */
public final class IdempotencyKeyInvalidException extends DomainException {

    /** The code of {@code ProblemCode.VALIDATION_FAILED}. */
    public static final String CODE = "validation-failed";

    public IdempotencyKeyInvalidException(String reason) {
        super(CODE, "the Idempotency-Key header is not acceptable: " + reason);
    }
}

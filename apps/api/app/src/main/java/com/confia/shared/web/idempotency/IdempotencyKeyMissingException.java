package com.confia.shared.web.idempotency;

import com.confia.kernel.DomainException;

/**
 * A write that moves money arrived with no {@code Idempotency-Key}, or with a blank one: {@code 400
 * idempotency-key-missing} (web-edge-foundations design.md, decision 19). It is raised before the
 * controller runs, so no use case, no transaction and no marker is involved. The message names
 * neither the header value nor anything else of the request.
 */
public final class IdempotencyKeyMissingException extends DomainException {

    public static final String CODE = "idempotency-key-missing";

    public IdempotencyKeyMissingException() {
        super(CODE, "the Idempotency-Key header is absent or blank");
    }
}

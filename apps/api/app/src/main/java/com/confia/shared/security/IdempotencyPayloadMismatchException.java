package com.confia.shared.security;

import com.confia.kernel.DomainException;

/**
 * The same idempotency key was reused with a request payload whose canonicalized hash differs from
 * the one stored for that key (design.md, decision 8; specs/build-integrity/spec.md, requirement
 * "Rechazo de la misma clave con carga útil distinta, comparada por hash canonicalizado"). Raised
 * before the use case runs — never after — so a client error never reaches production logic under
 * a key it does not actually own the effect of.
 */
public final class IdempotencyPayloadMismatchException extends DomainException {

    public static final String CODE = "idempotency-payload-mismatch";

    public IdempotencyPayloadMismatchException() {
        super(CODE, "idempotency key reused with a different request payload");
    }
}

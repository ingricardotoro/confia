package com.confia.shared.security;

import java.util.Objects;

/**
 * Identity of an idempotency marker within an institution; the institution itself comes from the
 * {@link SecurityContext} the caller's transaction already carries, never from this record
 * (design.md, section 6.1). {@code endpoint} disambiguates the same {@code idempotency_key} value
 * reused across two different operations, matching the composite primary key of {@code
 * shared_idempotency_key} (design.md, decision 2).
 */
public record IdempotencyKey(String endpoint, String value) {

    public IdempotencyKey {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(value, "value");
    }
}

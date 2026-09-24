package com.confia.shared.security;

import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * What an idempotent operation returns, and exactly what {@code shared_idempotency_key} stores
 * (design.md, decision 9). Deliberately not generic over a business response type: a repeated
 * request must return literally the same value the table holds, never a reconstruction from stored
 * JSON, so there is exactly one production path that can diverge from what was stored (design.md,
 * decision 9, "Por qué no {@code <T>}").
 */
public record IdempotentResponse(int responseStatus, JsonNode responseBody) {

    public IdempotentResponse {
        Objects.requireNonNull(responseBody, "responseBody");
    }
}

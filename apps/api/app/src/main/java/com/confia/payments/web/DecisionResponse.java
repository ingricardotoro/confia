package com.confia.payments.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** What was decided about a declared transfer. The reason, only of a rejection, may name a person: {@code toString} hides it. */
public record DecisionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"confirmed", "rejected"}) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID decidedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant decidedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "Present only for a rejection")
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

    @Override
    public String toString() {
        return "DecisionResponse[id=" + id + ", kind=" + kind + "]";
    }
}

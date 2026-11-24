package com.confia.scholarships.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** What was decided about an award. The reason, only of a rejection and a revocation, may name a person: {@code toString} hides it. */
public record AwardDecisionResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"approved", "rejected", "revoked"}) String kind,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID decidedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant decidedAt,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, description = "Present only for a rejection and a revocation")
        @JsonInclude(JsonInclude.Include.NON_NULL) String reason) {

    @Override
    public String toString() {
        return "AwardDecisionResponse[id=" + id + ", kind=" + kind + "]";
    }
}

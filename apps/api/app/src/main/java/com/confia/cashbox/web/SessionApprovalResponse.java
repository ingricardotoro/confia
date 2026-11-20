package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.UUID;

/** The approval of a close with a difference. The note may name a person. */
public record SessionApprovalResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = RequiredMode.REQUIRED) UUID approvedBy,
        @Schema(requiredMode = RequiredMode.REQUIRED) String note,
        @Schema(requiredMode = RequiredMode.REQUIRED) Instant approvedAt) {

    @Override
    public String toString() {
        return "SessionApprovalResponse[id=" + id + "]";
    }
}

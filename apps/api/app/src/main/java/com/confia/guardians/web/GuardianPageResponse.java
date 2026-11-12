package com.confia.guardians.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/** One page of guardians. */
public record GuardianPageResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<GuardianResponse> items,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Rows that match, in all pages") long total,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The page served, from 0") int page,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The page size served") int size) {

    /** Spring logs the object it writes at DEBUG: only the counts reach a log through it. */
    @Override
    public String toString() {
        return "GuardianPageResponse[total=" + total + ", page=" + page + ", size=" + size + "]";
    }
}

package com.confia.scholarships.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/v1/scholarships/discounts/{id}/reject}: the version the decider saw and why, from 20 to 500
 * characters. The reason may name a person: it lives only in the decision record and never reaches a log through {@code toString}.
 */
public record RejectDiscountChangeRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, minimum = "0", description = "The version of the discount the decider saw")
        @NotNull @Min(0) Long version,
        @Schema(requiredMode = RequiredMode.REQUIRED, minLength = 20, maxLength = 500)
        @NotNull @Size(min = 20, max = 500) @Pattern(regexp = "^[^\\p{Cc}\\p{Cf}]*$") String reason) {

    @Override
    public String toString() {
        return "RejectDiscountChangeRequest[version=" + version + ", reasonLength=" + (reason == null ? 0 : reason.length()) + "]";
    }
}

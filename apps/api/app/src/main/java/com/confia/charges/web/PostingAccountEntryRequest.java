package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/** One entry of {@code PUT /api/v1/charges/posting-accounts}: the account of one purpose and, for the revenue, one category. */
public record PostingAccountEntryRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"RECEIVABLE", "REVENUE", "TAX_PAYABLE", "BENEFITS"})
        @NotNull @Pattern(regexp = "RECEIVABLE|REVENUE|TAX_PAYABLE|BENEFITS") String purpose,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true,
                allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER"},
                description = "Only for the revenue; absent sets the default revenue account")
        @Pattern(regexp = "TUITION|ENROLLMENT|TRANSPORT|MATERIAL|FINE|OTHER") String conceptCategory,
        @Schema(requiredMode = RequiredMode.REQUIRED,
                description = "An active account of the chart of the institution of the type the purpose needs: "
                        + "an ASSET that holds student balances for the receivable, an INCOME for the revenue and a "
                        + "LIABILITY for the tax payable, the last two without student balances")
        @NotNull UUID ledgerAccountId,
        @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true, minimum = "0",
                description = "The version of the row the editor read; absent when the entry was never set. A "
                        + "different one answers 409 stale-version")
        @Min(0) Long version) {
}

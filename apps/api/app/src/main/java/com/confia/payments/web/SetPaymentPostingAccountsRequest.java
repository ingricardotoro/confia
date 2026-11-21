package com.confia.payments.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The body of {@code PUT /api/v1/payments/posting-accounts}: the entries to set, each the account of one purpose (CASH or BANK).
 * Only the entries named are written, so a map can be loaded in steps; none is ever deleted. The institution is the one of the
 * token and is never a member of the body.
 */
public record SetPaymentPostingAccountsRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 2, description = "1 or 2 entries, none twice")
        @NotEmpty @Size(max = 2) List<@NotNull @Valid PaymentPostingAccountEntryRequest> entries) {
}

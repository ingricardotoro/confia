package com.confia.cashbox.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** The body of {@code POST /api/v1/cashbox/sessions}: the float the cashier starts with. The cashier is the one of the token. */
public record OpenSessionRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "The opening float: zero or more, at most two decimals")
        @NotNull @Valid CashMoney openingFloat) {
}

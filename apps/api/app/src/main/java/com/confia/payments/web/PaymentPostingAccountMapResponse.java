package com.confia.payments.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * The payments map of the institution as it is stored, possibly incomplete: a payment that needs a missing account answers {@code
 * posting-accounts-incomplete}, so a partial map is a normal answer here.
 */
public record PaymentPostingAccountMapResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<PaymentPostingAccountResponse> items) {
}

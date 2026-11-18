package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;

/**
 * The map of posting accounts of the institution as it is stored, possibly incomplete: the accrual preview reports an
 * incomplete map as {@code posting-accounts-incomplete}, so a partial map is a normal answer here.
 */
public record PostingAccountMapResponse(
        @Schema(requiredMode = RequiredMode.REQUIRED) List<PostingAccountResponse> items) {
}

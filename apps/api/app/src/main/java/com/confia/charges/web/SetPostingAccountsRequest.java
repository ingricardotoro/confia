package com.confia.charges.web;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The body of {@code PUT /api/v1/charges/posting-accounts}: the entries to set, each the account of one purpose and, for the
 * revenue, one category. Only the entries named are written, so a map can be loaded in steps; none is ever deleted. The
 * institution is the one of the token and is never a member of the body.
 */
public record SetPostingAccountsRequest(
        @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 20,
                description = "1 to 20 entries, none twice (the same purpose and category)")
        @NotEmpty @Size(max = 20) List<@NotNull @Valid PostingAccountEntryRequest> entries) {
}

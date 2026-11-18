package com.confia.charges.web;

import com.confia.charges.application.ManagePostingAccounts;
import com.confia.charges.application.PostingAccountView;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.ratelimit.RateLimitKey;
import com.confia.shared.web.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two routes of {@code /api/v1/charges/posting-accounts} (docs/cortes/ledger-chart-of-accounts.md): the map of the
 * accounts of the chart that every charge posts to. The permission each route needs is decided before the controller runs, by
 * the permission decider over {@code PermissionedEndpoints}: {@code charges:read} for the read and {@code ledger:create} for
 * setting the map, because it decides where the money of every charge is booked and so belongs to the same authority that
 * loads the chart (the Super Administrator), not to {@code charges:update}, which the Administrator holds. The institution is
 * the one of the token, never a member of the request.
 */
@RestController
@RequestMapping("/api/v1/charges/posting-accounts")
public class PostingAccountController {

    private static final String TAG = "charges-posting-accounts";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManagePostingAccounts postingAccounts;

    public PostingAccountController(ManagePostingAccounts postingAccounts) {
        this.postingAccounts = postingAccounts;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPostingAccounts", tags = TAG, summary = "Reads the map of posting accounts",
            description = "The rows of the map of the institution of the token, ordered by purpose and category. The map "
                    + "may be incomplete (the accrual preview then reports posting-accounts-incomplete). Needs the "
                    + "permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "The map as it is stored",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PostingAccountMapResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PostingAccountMapResponse get() {
        return toResponse(postingAccounts.list(RequestContext.actor(), RequestContext.requestId()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setPostingAccounts", tags = TAG, summary = "Sets entries of the map of posting accounts",
            description = "Sets the account of each entry named (the receivable, the tax payable, the default revenue and "
                    + "the revenue of a category of concept) and answers the whole map. Only the entries named are "
                    + "written, so the map can be loaded in steps; no entry is ever deleted. Every account must exist in "
                    + "the institution, be active and be of the type its purpose needs. An entry carries the version of "
                    + "the row the editor read, or none when the entry was never set; any other answers 409 "
                    + "stale-version and nothing is written. Needs the permission ledger:create, which only the Super "
                    + "Administrator holds.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "ledger:create")))
    @ApiResponse(responseCode = "200", description = "The whole map after the write",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PostingAccountMapResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid: no entries, more than 20, a purpose or category outside the set, a "
                    + "category on a purpose that has none, or the same purpose and category twice (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold ledger:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "An account named does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "An account named is inactive (inactive-reference), is not of the type its purpose needs "
                    + "(posting-account-invalid), or the version of an entry is not the current one (stale-version)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PostingAccountMapResponse set(@Valid @RequestBody SetPostingAccountsRequest request) {
        List<ManagePostingAccounts.Entry> entries = request.entries().stream()
                .map(entry -> new ManagePostingAccounts.Entry(entry.purpose(), entry.conceptCategory(),
                        entry.ledgerAccountId(), entry.version()))
                .toList();
        return toResponse(postingAccounts.set(RequestContext.actor(), RequestContext.requestId(), entries));
    }

    private static PostingAccountMapResponse toResponse(List<PostingAccountView> views) {
        return new PostingAccountMapResponse(views.stream()
                .map(view -> new PostingAccountResponse(view.id(), view.purpose(), view.conceptCategory(),
                        view.ledgerAccountId(), view.version(), view.updatedAt()))
                .toList());
    }
}

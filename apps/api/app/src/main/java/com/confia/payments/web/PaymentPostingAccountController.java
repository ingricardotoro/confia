package com.confia.payments.web;

import com.confia.payments.application.ManagePostingAccounts;
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
 * The two routes of {@code /api/v1/payments/posting-accounts} (docs/cortes/payments-register.md): the map of the cash and bank
 * accounts of the chart that every payment is posted to. The permission each route needs is decided before the controller runs, by
 * the permission decider over {@code PermissionedEndpoints}: {@code ledger:read} for the read and {@code ledger:create} for setting
 * the map, because it decides where the money of every payment is booked and so belongs to the same authority that loads the chart
 * and the map of the charges (the Super Administrator). The institution is the one of the token, never a member of the request.
 */
@RestController
@RequestMapping("/api/v1/payments/posting-accounts")
public class PaymentPostingAccountController {

    private static final String TAG = "payments-posting-accounts";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManagePostingAccounts postingAccounts;

    public PaymentPostingAccountController(ManagePostingAccounts postingAccounts) {
        this.postingAccounts = postingAccounts;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPaymentPostingAccounts", tags = TAG, summary = "Reads the map of payment accounts",
            description = "The rows of the cash and bank map of the institution of the token, ordered by purpose. The map may "
                    + "be incomplete (a payment that needs it then answers posting-accounts-incomplete). Needs the "
                    + "permission ledger:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "ledger:read")))
    @ApiResponse(responseCode = "200", description = "The map as it is stored",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentPostingAccountMapResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold ledger:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentPostingAccountMapResponse get() {
        return PaymentMapper.toResponse(postingAccounts.list(RequestContext.actor(), RequestContext.requestId()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "setPaymentPostingAccounts", tags = TAG, summary = "Sets entries of the map of payment accounts",
            description = "Sets the account of each entry named (CASH and BANK) and answers the whole map. Only the entries "
                    + "named are written, so the map can be loaded in steps; no entry is ever deleted. Every account must "
                    + "exist in the institution, be active and be an asset that carries no balance of a student. An entry "
                    + "carries the version of the row the editor read, or none when the entry was never set; any other "
                    + "answers 409 stale-version and nothing is written. Needs the permission ledger:create, which only the "
                    + "Super Administrator holds.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission",
                    value = "ledger:create")))
    @ApiResponse(responseCode = "200", description = "The whole map after the write",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentPostingAccountMapResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid: no entries, more than 2, a purpose outside CASH and BANK, or the same "
                    + "purpose twice (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold ledger:create, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "An account named does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "An account named is inactive (inactive-reference), is not an asset without student balances "
                    + "(posting-account-invalid), or the version of an entry is not the current one (stale-version)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentPostingAccountMapResponse set(@Valid @RequestBody SetPaymentPostingAccountsRequest request) {
        List<ManagePostingAccounts.Entry> entries = request.entries().stream()
                .map(entry -> new ManagePostingAccounts.Entry(entry.purpose(), entry.ledgerAccountId(), entry.version()))
                .toList();
        return PaymentMapper.toResponse(postingAccounts.set(RequestContext.actor(), RequestContext.requestId(), entries));
    }
}

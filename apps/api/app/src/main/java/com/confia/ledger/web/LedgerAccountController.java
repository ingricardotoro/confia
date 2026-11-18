package com.confia.ledger.web;

import com.confia.ledger.application.LedgerAccountView;
import com.confia.ledger.application.ManageLedgerAccounts;
import com.confia.ledger.application.Page;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.ratelimit.RateLimitKey;
import com.confia.shared.web.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three routes of {@code /api/v1/ledger/accounts} (docs/cortes/ledger-chart-of-accounts.md). The permission each route
 * needs is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * ledger:read} for the list, and {@code ledger:create} for the creation and the edit of an account, which only the Super
 * Administrator holds (no {@code ledger:update} exists: nobody edits the ledger, and the chart is configuration). The
 * institution is the one of the token, never a member of the request. An account is never deleted: it is deactivated with
 * {@code PUT}. The approved answers are declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/ledger/accounts")
public class LedgerAccountController {

    private static final String TAG = "ledger-accounts";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageLedgerAccounts accounts;

    public LedgerAccountController(ManageLedgerAccounts accounts) {
        this.accounts = accounts;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listLedgerAccounts", tags = TAG, summary = "Lists the accounts of the chart",
            description = "One page of the accounts of the chart of the institution of the token, optionally filtered by "
                    + "type and by active flag. Needs the permission ledger:read. page counts from 0, size defaults to 50 "
                    + "and is at most 200, sort is code or name optionally followed by ,asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "ledger:read")))
    @ApiResponse(responseCode = "200", description = "One page of the accounts",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LedgerAccountPageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range, the type is not one of the set or the sort field is not allowed "
                    + "(validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold ledger:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public LedgerAccountPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "code or name, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Only the accounts of this type",
                    schema = @Schema(allowableValues = {"ASSET", "LIABILITY", "EQUITY", "INCOME", "EXPENSE"}))
            @RequestParam(name = "type", required = false) @Size(max = 20) String type,
            @Parameter(description = "Only the active (true) or only the inactive (false) accounts")
            @RequestParam(name = "active", required = false) Boolean active) {
        Page<LedgerAccountView> result = accounts.list(RequestContext.actor(), RequestContext.requestId(), page, size,
                sort, type, active);
        return new LedgerAccountPageResponse(result.items().stream().map(LedgerAccountController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createLedgerAccount", tags = TAG, summary = "Creates an account of the chart",
            description = "Adds an account to the chart of the institution, active, at version 0. The code, the type and "
                    + "the student flag cannot be changed later, and an account is never deleted. Needs the permission "
                    + "ledger:create, which only the Super Administrator holds.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "ledger:create")))
    @ApiResponse(responseCode = "201", description = "The account created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LedgerAccountResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid: a code outside its shape, a type outside the set or the student flag on "
                    + "a type that cannot hold it (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold ledger:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The institution already has an account with this code, whatever the capitals "
                    + "(duplicate-account-code)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public LedgerAccountResponse create(@Valid @RequestBody CreateLedgerAccountRequest request) {
        return toResponse(accounts.create(RequestContext.actor(), RequestContext.requestId(), request.code(),
                request.name(), request.type(), request.holdsStudentBalances()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateLedgerAccount", tags = TAG, summary = "Renames, activates or deactivates an account",
            description = "Replaces the name and the active flag of the account (never its code, type or student flag). "
                    + "Deactivating an account makes it refuse new facts; its entries stay readable and reversible. An "
                    + "account that the posting map of the charges points at cannot be deactivated. The body carries the "
                    + "version the editor read: a different one answers 409 stale-version. Needs the permission "
                    + "ledger:create (there is no ledger:update: nobody edits the ledger), which only the Super "
                    + "Administrator holds.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "ledger:create")))
    @ApiResponse(responseCode = "200", description = "The account after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LedgerAccountResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid, or the body carries a code, a type or a student flag "
                    + "(validation-failed)",
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
            description = "The account does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The version of the body is not the current one (stale-version), or the account is in use by "
                    + "the posting map and cannot be deactivated (account-in-use)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public LedgerAccountResponse update(@PathVariable("id") UUID id,
            @Valid @RequestBody UpdateLedgerAccountRequest request) {
        return toResponse(accounts.update(RequestContext.actor(), RequestContext.requestId(), id, request.version(),
                request.name(), request.active()));
    }

    private static LedgerAccountResponse toResponse(LedgerAccountView view) {
        return new LedgerAccountResponse(view.id(), view.code(), view.name(), view.type(), view.holdsStudentBalances(),
                view.active(), view.version(), view.createdAt(), view.updatedAt());
    }
}

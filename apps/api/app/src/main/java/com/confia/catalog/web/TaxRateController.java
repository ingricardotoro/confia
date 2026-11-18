package com.confia.catalog.web;

import com.confia.catalog.application.ManageTaxRates;
import com.confia.catalog.application.Page;
import com.confia.catalog.application.TaxRateDetailView;
import com.confia.catalog.application.TaxRatePeriodView;
import com.confia.catalog.application.TaxRateView;
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
 * The five routes of {@code /api/v1/catalog/tax-rates} (docs/cortes/catalog-tax-rates.md). The permission each route
 * needs is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * catalog:read} for the list and the detail, {@code catalog:create} for the creation of a rate and for adding a period,
 * and {@code catalog:update} for the edit of a rate. The institution is the one of the token, never a member of the
 * request, and nothing is deleted: a rate is deactivated with {@code PUT} and a percentage is replaced by a new period
 * with {@code POST .../periods}. No percentage is known to the server until the administrator loads it. The approved
 * answers are declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/catalog/tax-rates")
public class TaxRateController {

    private static final String TAG = "catalog-tax-rates";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageTaxRates taxRates;

    public TaxRateController(ManageTaxRates taxRates) {
        this.taxRates = taxRates;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listTaxRates", tags = TAG, summary = "Lists the tax rates",
            description = "One page of the tax rates of the institution of the token, optionally filtered by active. "
                    + "Needs the permission catalog:read. page counts from 0, size defaults to 50 and is at most 200, "
                    + "sort is a field of code or name optionally followed by ,asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:read")))
    @ApiResponse(responseCode = "200", description = "One page of the tax rates",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TaxRatePageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range or the sort field is not allowed (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold catalog:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public TaxRatePageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of code or name, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Only the active (true) or the inactive (false) tax rates")
            @RequestParam(name = "active", required = false) Boolean active) {
        Page<TaxRateView> result = taxRates.list(RequestContext.actor(), RequestContext.requestId(), page, size,
                sort, active);
        return new TaxRatePageResponse(result.items().stream().map(TaxRateController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createTaxRate", tags = TAG, summary = "Creates a tax rate",
            description = "Creates a tax rate with no percentage; the percentages are added as periods. Its code is "
                    + "unique in the institution whatever its capitals and cannot be changed later. Needs the "
                    + "permission catalog:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:create")))
    @ApiResponse(responseCode = "201", description = "The tax rate created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TaxRateResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold catalog:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The code is taken (duplicate-tax-rate-code)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public TaxRateResponse create(@Valid @RequestBody CreateTaxRateRequest request) {
        return toResponse(taxRates.create(RequestContext.actor(), RequestContext.requestId(), request.code(),
                request.name()));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getTaxRate", tags = TAG, summary = "Reads a tax rate with its periods",
            description = "The tax rate with every period it has had, oldest first. Needs the permission catalog:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:read")))
    @ApiResponse(responseCode = "200", description = "The tax rate and its periods",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TaxRateDetailResponse.class)))
    @ApiResponse(responseCode = "400", description = "The identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold catalog:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The tax rate does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public TaxRateDetailResponse get(@PathVariable("id") UUID id) {
        TaxRateDetailView detail = taxRates.get(RequestContext.actor(), RequestContext.requestId(), id);
        TaxRateView rate = detail.rate();
        return new TaxRateDetailResponse(rate.id(), rate.code(), rate.name(), rate.active(), rate.version(),
                rate.createdAt(), rate.updatedAt(),
                detail.periods().stream().map(TaxRateController::toResponse).toList());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateTaxRate", tags = TAG, summary = "Edits a tax rate",
            description = "Replaces the name and the active flag of the tax rate (never its code nor its percentages); "
                    + "false in isActive deactivates it. The body carries the version the editor read: a different one "
                    + "answers 409 stale-version. Needs the permission catalog:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:update")))
    @ApiResponse(responseCode = "200", description = "The tax rate after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TaxRateResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid, or the body carries a code or a percentage "
                    + "(validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold catalog:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The tax rate does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The version of the body is not the current one (stale-version)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public TaxRateResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateTaxRateRequest request) {
        return toResponse(taxRates.update(RequestContext.actor(), RequestContext.requestId(), id, request.version(),
                request.name(), request.isActive()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/periods", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "addTaxRatePeriod", tags = TAG, summary = "Adds a period (a new validity)",
            description = "Adds a percentage to the tax rate from a day. When the rate already has an open period that "
                    + "the new one replaces, the server closes it the day before the new one starts, in the same "
                    + "transaction. Any other overlap with the validity of a period of the same rate answers 409 "
                    + "tax-rate-period-overlap. An inactive rate takes no period. Needs the permission catalog:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:create")))
    @ApiResponse(responseCode = "201", description = "The period created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TaxRatePeriodResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid: a percentage that is not greater than zero, is "
                    + "above 100, has more than four decimals or is not a string, or an end before the start "
                    + "(validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold catalog:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The tax rate does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The validity overlaps another one (tax-rate-period-overlap) or the tax rate is inactive "
                    + "(inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public TaxRatePeriodResponse addPeriod(@PathVariable("id") UUID id,
            @Valid @RequestBody AddTaxRatePeriodRequest request) {
        return toResponse(taxRates.addPeriod(RequestContext.actor(), RequestContext.requestId(), id,
                request.ratePercent(), request.validFrom(), request.validTo()));
    }

    private static TaxRateResponse toResponse(TaxRateView view) {
        return new TaxRateResponse(view.id(), view.code(), view.name(), view.active(), view.version(),
                view.createdAt(), view.updatedAt());
    }

    private static TaxRatePeriodResponse toResponse(TaxRatePeriodView view) {
        return new TaxRatePeriodResponse(view.id(), view.taxRateId(), view.ratePercent(), view.validFrom(),
                view.validTo(), view.createdAt());
    }
}

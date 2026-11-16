package com.confia.catalog.web;

import com.confia.catalog.application.ManagePriceLists;
import com.confia.catalog.application.Page;
import com.confia.catalog.application.PriceListDetailView;
import com.confia.catalog.application.PriceListItemView;
import com.confia.catalog.application.PriceListView;
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
 * The five routes of {@code /api/v1/catalog/price-lists} (docs/cortes/catalog-price-lists.md). The permission each route
 * needs is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * catalog:read} for the list and the detail, {@code catalog:create} for the creation of a list and for adding a price,
 * and {@code catalog:update} for the edit of a list. The institution is the one of the token, never a member of the
 * request, and nothing is deleted: a list is archived with {@code PUT} and a price is replaced by a new validity with
 * {@code POST .../items}. The approved answers are declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/catalog/price-lists")
public class PriceListController {

    private static final String TAG = "catalog-price-lists";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManagePriceLists priceLists;

    public PriceListController(ManagePriceLists priceLists) {
        this.priceLists = priceLists;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listPriceLists", tags = TAG, summary = "Lists the price lists",
            description = "One page of the price lists of the institution of the token, optionally filtered by "
                    + "academic year, scope and status. Needs the permission catalog:read. page counts from 0, size "
                    + "defaults to 50 and is at most 200, sort is a field of name or createdAt optionally followed by "
                    + ",asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:read")))
    @ApiResponse(responseCode = "200", description = "One page of the price lists",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PriceListPageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range, the scope or the status is not one of the set or the sort "
                    + "field is not allowed (validation-failed)",
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
    public PriceListPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of name or createdAt, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Only the lists of this academic year")
            @RequestParam(name = "academicYearId", required = false) UUID academicYearId,
            @Parameter(description = "Only the lists of this scope",
                    schema = @Schema(allowableValues = {"INSTITUTION", "MODALITY", "GRADE"}))
            @RequestParam(name = "scope", required = false) @Size(max = 20) String scope,
            @Parameter(description = "Only the lists in this status",
                    schema = @Schema(allowableValues = {"ACTIVE", "ARCHIVED"}))
            @RequestParam(name = "status", required = false) @Size(max = 20) String status) {
        Page<PriceListView> result = priceLists.list(RequestContext.actor(), RequestContext.requestId(), page,
                size, sort, academicYearId, scope, status);
        return new PriceListPageResponse(
                result.items().stream().map(PriceListController::toResponse).toList(), result.total(),
                result.page(), result.size());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createPriceList", tags = TAG, summary = "Creates a price list",
            description = "Creates a price list of an academic year, for the whole institution or for one modality or "
                    + "grade. The year, the scope and the reference cannot be changed later. Needs the permission "
                    + "catalog:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:create")))
    @ApiResponse(responseCode = "201", description = "The price list created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PriceListResponse.class)))
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
    @ApiResponse(responseCode = "404",
            description = "The academic year, the modality or the grade does not exist in the institution of the "
                    + "token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PriceListResponse create(@Valid @RequestBody CreatePriceListRequest request) {
        return toResponse(priceLists.create(RequestContext.actor(), RequestContext.requestId(),
                request.academicYearId(), request.name(), request.scope(), request.scopeRefId()));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getPriceList", tags = TAG, summary = "Reads a price list with its prices",
            description = "The price list with every validity of every price it holds, by concept and start. Needs the "
                    + "permission catalog:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:read")))
    @ApiResponse(responseCode = "200", description = "The price list and its prices",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PriceListDetailResponse.class)))
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
            description = "The price list does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PriceListDetailResponse get(@PathVariable("id") UUID id) {
        PriceListDetailView detail = priceLists.get(RequestContext.actor(), RequestContext.requestId(), id);
        PriceListView list = detail.list();
        return new PriceListDetailResponse(list.id(), list.academicYearId(), list.name(), list.scope(),
                list.scopeRefId(), list.status(), list.version(), list.createdAt(), list.updatedAt(),
                detail.items().stream().map(PriceListController::toResponse).toList());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updatePriceList", tags = TAG, summary = "Edits a price list",
            description = "Replaces the name and the status of the price list (never its year, scope or reference); "
                    + "ARCHIVED archives it. The body carries the version the editor read: a different one answers 409 "
                    + "stale-version. Needs the permission catalog:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:update")))
    @ApiResponse(responseCode = "200", description = "The price list after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PriceListResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid, or the body carries a year, a scope or a "
                    + "reference (validation-failed)",
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
            description = "The price list does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The version of the body is not the current one (stale-version)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PriceListResponse update(@PathVariable("id") UUID id,
            @Valid @RequestBody UpdatePriceListRequest request) {
        return toResponse(priceLists.update(RequestContext.actor(), RequestContext.requestId(), id,
                request.version(), request.name(), request.status()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/items", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "addPriceListItem", tags = TAG, summary = "Adds a price (a new validity)",
            description = "Adds the price of a payment concept to the list from a day. When the concept already has an "
                    + "open validity that the new price replaces, the server closes it the day before the new one "
                    + "starts, in the same transaction. Any other overlap with the validity of a price of the same "
                    + "concept in the list answers 409 price-validity-overlap. An archived list takes no price. Needs "
                    + "the permission catalog:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:create")))
    @ApiResponse(responseCode = "201", description = "The price created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PriceListItemResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid: an amount that is not greater than zero or has more "
                    + "than four decimals, a currency outside the set, or an end before the start (validation-failed)",
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
            description = "The price list or the payment concept does not exist in the institution of the token "
                    + "(resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The validity overlaps another one (price-validity-overlap), the list is archived "
                    + "(price-list-archived) or the concept is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PriceListItemResponse addPrice(@PathVariable("id") UUID id,
            @Valid @RequestBody AddPriceRequest request) {
        return toResponse(priceLists.addPrice(RequestContext.actor(), RequestContext.requestId(), id,
                request.paymentConceptId(), request.price().amount(), request.price().currency(),
                request.validFrom(), request.validTo()));
    }

    private static PriceListResponse toResponse(PriceListView view) {
        return new PriceListResponse(view.id(), view.academicYearId(), view.name(), view.scope(),
                view.scopeRefId(), view.status(), view.version(), view.createdAt(), view.updatedAt());
    }

    private static PriceListItemResponse toResponse(PriceListItemView view) {
        return new PriceListItemResponse(view.id(), view.priceListId(), view.paymentConceptId(),
                new MoneyDto(view.amount(), view.currency()), view.validFrom(), view.validTo(), view.createdAt());
    }
}

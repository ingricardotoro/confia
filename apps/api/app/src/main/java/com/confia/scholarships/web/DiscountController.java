package com.confia.scholarships.web;

import com.confia.scholarships.application.DiscountFilter;
import com.confia.scholarships.application.ManageDiscounts;
import com.confia.scholarships.application.PageRequest;
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
import jakarta.validation.constraints.Pattern;
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
 * The routes of the discounts under {@code /api/v1/scholarships/discounts} (docs/cortes/scholarships-discounts.md). The permission
 * each route needs is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * scholarships:read} to list and read, and {@code scholarships:create} to create, edit, deactivate and reactivate. The institution is
 * the one of the token, never a member of a request, and nothing is deleted. A discount of another institution and an unknown one look
 * the same: 404. Every amount is rounded by the server.
 */
@RestController
@RequestMapping("/api/v1/scholarships/discounts")
public class DiscountController {

    private static final String TAG = "discounts";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageDiscounts discounts;

    public DiscountController(ManageDiscounts discounts) {
        this.discounts = discounts;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listDiscounts", tags = TAG, summary = "Lists discounts",
            description = "One page of the discounts of the institution of the token, filtered by active, by kind (campaign or "
                    + "agreement) and by type of benefit, sorted by code, name, validFrom or createdAt (sort=field or field,asc|desc; "
                    + "code ascending by default). page counts from 0, size defaults to 50 and is at most 200. Needs the permission "
                    + "scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "One page of discounts",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DiscountPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public DiscountPageResponse list(
            @Parameter(description = "Only the active (true) or the inactive (false) discounts")
            @RequestParam(name = "active", required = false) Boolean active,
            @Parameter(description = "Only discounts of this kind")
            @RequestParam(name = "kind", required = false) @Pattern(regexp = "campaign|agreement") String kind,
            @Parameter(description = "Only discounts of this type of benefit")
            @RequestParam(name = "benefitType", required = false) @Pattern(regexp = "PERCENTAGE|FIXED_AMOUNT") String benefitType,
            @Parameter(description = "code, name, validFrom or createdAt, with ,asc or ,desc; code,asc when absent")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        PageRequest request = PageRequest.of(page, size, sort, DiscountFilter.SORT_FIELDS, "code", true);
        return ScholarshipMapper.toDiscountResponse(discounts.list(RequestContext.actor(), RequestContext.requestId(),
                new DiscountFilter(active, kind, benefitType), request));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createDiscount", tags = TAG, summary = "Creates a discount",
            description = "Creates an active discount: a unique code, a name, a kind (campaign or agreement), a benefit (a percentage "
                    + "greater than 0 and up to 100, or a fixed amount greater than 0 in HNL, both with up to two decimals), a validity "
                    + "(both ends included), whether it is stackable and the payment concepts it covers (one or more). A campaign "
                    + "discount applies by itself to every charge of a covered concept issued inside its validity; an agreement "
                    + "discount applies only to the students with an approved assignment. Needs the permission scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "201", description = "The discount created, active, at version 0",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DiscountResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid or a covered concept does not exist (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The code is in use (duplicate-discount-code), or a covered concept is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public DiscountResponse create(@Valid @RequestBody CreateDiscountRequest request) {
        return ScholarshipMapper.toResponse(ScholarshipController.named(() -> discounts.create(RequestContext.actor(),
                RequestContext.requestId(), request.code(), request.kind(),
                new ManageDiscounts.Draft(request.name(), request.benefitType(), request.percent(),
                        ScholarshipController.amountOf(request.fixedAmount()), request.validFrom(), request.validTo(),
                        request.stackable(), request.conceptIds()))));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getDiscount", tags = TAG, summary = "Reads a discount",
            description = "One discount with its covered concepts and whether it is in use. Needs the permission scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "The discount",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DiscountResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The discount does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public DiscountResponse get(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toResponse(discounts.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateDiscount", tags = TAG, summary = "Edits a discount",
            description = "Changes the name, the benefit, the validity, the stackable flag and the covered concepts of a discount; the "
                    + "code, the kind and the active flag stay. It asks for the version the editor saw. Once an assignment of the "
                    + "discount was approved the type and the value of the benefit cannot change, the validity only shortens and the "
                    + "concepts are only removed: widening a discount means creating another one. Needs the permission "
                    + "scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "200", description = "The discount edited, with its version advanced",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DiscountResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid, or the new validity leaves an assignment outside it (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The discount does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The version is not the current one (stale-version), the benefit cannot change because the discount is in use "
                    + "(discount-benefit-locked), its validity or concepts cannot widen (discount-scope-locked), or a covered concept "
                    + "is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public DiscountResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateDiscountRequest request) {
        return ScholarshipMapper.toResponse(ScholarshipController.named(() -> discounts.update(RequestContext.actor(),
                RequestContext.requestId(), id, request.version(),
                new ManageDiscounts.Draft(request.name(), request.benefitType(), request.percent(),
                        ScholarshipController.amountOf(request.fixedAmount()), request.validFrom(), request.validTo(),
                        request.stackable(), request.conceptIds()))));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/deactivate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deactivateDiscount", tags = TAG, summary = "Deactivates a discount",
            description = "The discount takes no new assignment and stops applying to the charges issued afterwards; the charges already "
                    + "issued do not change. Deactivating an inactive discount changes nothing. Needs the permission "
                    + "scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "200", description = "The discount, inactive",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DiscountResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The discount does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public DiscountResponse deactivate(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toResponse(discounts.deactivate(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/reactivate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reactivateDiscount", tags = TAG, summary = "Reactivates a discount",
            description = "The discount takes new assignments again and applies to the charges issued afterwards. Reactivating an active "
                    + "discount changes nothing. Needs the permission scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "200", description = "The discount, active",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = DiscountResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The discount does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public DiscountResponse reactivate(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toResponse(discounts.reactivate(RequestContext.actor(), RequestContext.requestId(), id));
    }
}

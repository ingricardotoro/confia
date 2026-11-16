package com.confia.catalog.web;

import com.confia.catalog.application.ManagePaymentConcepts;
import com.confia.catalog.application.Page;
import com.confia.catalog.application.PaymentConceptView;
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
 * {@code GET}, {@code POST} and {@code PUT} of {@code /api/v1/catalog/payment-concepts}
 * (docs/cortes/catalog-payment-concepts.md). The permission each route needs is decided before the controller runs, by
 * the permission decider over {@code PermissionedEndpoints}: {@code catalog:read} for the list, {@code
 * catalog:create} for the creation and {@code catalog:update} for the edit. The institution is the one of the token,
 * never a member of the request, the code is never edited, and nothing is deleted: a concept is deactivated with
 * {@code PUT}. The approved answers are declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/catalog/payment-concepts")
public class PaymentConceptController {

    private static final String TAG = "catalog-payment-concepts";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManagePaymentConcepts concepts;

    public PaymentConceptController(ManagePaymentConcepts concepts) {
        this.concepts = concepts;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listPaymentConcepts", tags = TAG, summary = "Lists the payment concepts",
            description = "One page of the payment concepts of the institution of the token, optionally "
                    + "filtered by category and by active. Needs the permission catalog:read. page counts from 0, "
                    + "size defaults to 50 and is at most 200, sort is a field of code or name optionally "
                    + "followed by ,asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:read")))
    @ApiResponse(responseCode = "200", description = "One page of the payment concepts",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentConceptPageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range, the category is not one of the set or the sort field is "
                    + "not allowed (validation-failed)",
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
    public PaymentConceptPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of code or name, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Only the concepts of this category",
                    schema = @Schema(allowableValues = {"TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE",
                            "OTHER"}))
            @RequestParam(name = "category", required = false) @Size(max = 20) String category,
            @Parameter(description = "Only the active (true) or the inactive (false) concepts")
            @RequestParam(name = "active", required = false) Boolean active) {
        Page<PaymentConceptView> result = concepts.list(RequestContext.actor(), RequestContext.requestId(),
                page, size, sort, category, active);
        return new PaymentConceptPageResponse(
                result.items().stream().map(PaymentConceptController::toResponse).toList(), result.total(),
                result.page(), result.size());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createPaymentConcept", tags = TAG, summary = "Creates a payment concept",
            description = "Creates a payment concept; its code is unique in the institution whatever its capitals and "
                    + "cannot be changed later. Needs the permission catalog:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:create")))
    @ApiResponse(responseCode = "201", description = "The payment concept created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentConceptResponse.class)))
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
    @ApiResponse(responseCode = "409", description = "The code is taken (duplicate-payment-concept-code)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentConceptResponse create(@Valid @RequestBody CreatePaymentConceptRequest request) {
        return toResponse(concepts.create(RequestContext.actor(), RequestContext.requestId(), request.code(),
                request.name(), request.category(), request.isTaxable(), request.isRecurring()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updatePaymentConcept", tags = TAG, summary = "Edits a payment concept",
            description = "Replaces the editable members of the payment concept (never its code); false in isActive "
                    + "deactivates it. Needs the permission catalog:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "catalog:update")))
    @ApiResponse(responseCode = "200", description = "The payment concept after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = PaymentConceptResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid, or the body carries a code (validation-failed)",
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
            description = "The payment concept does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public PaymentConceptResponse update(@PathVariable("id") UUID id,
            @Valid @RequestBody UpdatePaymentConceptRequest request) {
        return toResponse(concepts.update(RequestContext.actor(), RequestContext.requestId(), id, request.name(),
                request.category(), request.isTaxable(), request.isRecurring(), request.isActive()));
    }

    private static PaymentConceptResponse toResponse(PaymentConceptView view) {
        return new PaymentConceptResponse(view.id(), view.code(), view.name(), view.category(), view.taxable(),
                view.recurring(), view.active(), view.createdAt(), view.updatedAt());
    }
}

package com.confia.charges.web;

import com.confia.charges.application.ChargePlanDetailView;
import com.confia.charges.application.ChargePlanItemView;
import com.confia.charges.application.ChargePlanView;
import com.confia.charges.application.ManageChargePlans;
import com.confia.charges.application.Page;
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
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * The six routes of {@code /api/v1/charges/plans} (docs/cortes/charges-plans.md). The permission each route needs is
 * decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code charges:read}
 * for the list and the detail, {@code charges:create} for the creation of a plan, and {@code charges:update} for the edit
 * of a plan and for adding and removing its items. The institution is the one of the token, never a member of the request.
 * A plan is never deleted: it is archived with {@code PUT}. An item is removed with {@code DELETE}, only while the plan is
 * a draft. The approved answers are declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/charges/plans")
public class ChargePlanController {

    private static final String TAG = "charges-plans";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageChargePlans plans;

    public ChargePlanController(ManageChargePlans plans) {
        this.plans = plans;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listChargePlans", tags = TAG, summary = "Lists the charge plans",
            description = "One page of the charge plans of the institution of the token, optionally filtered by "
                    + "academic year, target scope and status. Needs the permission charges:read. page counts from 0, "
                    + "size defaults to 50 and is at most 200, sort is a field of name or createdAt optionally followed "
                    + "by ,asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "One page of the charge plans",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ChargePlanPageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range, the target scope or the status is not one of the set or the "
                    + "sort field is not allowed (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ChargePlanPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of name or createdAt, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Only the plans of this academic year")
            @RequestParam(name = "academicYearId", required = false) UUID academicYearId,
            @Parameter(description = "Only the plans of this target scope",
                    schema = @Schema(allowableValues = {"MODALITY", "GRADE", "SECTION"}))
            @RequestParam(name = "targetScope", required = false) @Size(max = 20) String targetScope,
            @Parameter(description = "Only the plans in this status",
                    schema = @Schema(allowableValues = {"DRAFT", "ACTIVE", "ARCHIVED"}))
            @RequestParam(name = "status", required = false) @Size(max = 20) String status) {
        Page<ChargePlanView> result = plans.list(RequestContext.actor(), RequestContext.requestId(), page, size,
                sort, academicYearId, targetScope, status);
        return new ChargePlanPageResponse(
                result.items().stream().map(ChargePlanController::toResponse).toList(), result.total(),
                result.page(), result.size());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createChargePlan", tags = TAG, summary = "Creates a charge plan",
            description = "Creates a charge plan, as a draft, of an academic year for one modality, grade or section. "
                    + "The year, the target scope and the target reference cannot be changed later. Needs the "
                    + "permission charges:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:create")))
    @ApiResponse(responseCode = "201", description = "The charge plan created, as a draft",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ChargePlanResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid, in particular a due day outside 1 to 28 (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The academic year, or the modality, grade or section of the target, does not exist in the "
                    + "institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ChargePlanResponse create(@Valid @RequestBody CreateChargePlanRequest request) {
        return toResponse(plans.create(RequestContext.actor(), RequestContext.requestId(),
                request.academicYearId(), request.name(), request.targetScope(), request.targetRefId(),
                request.frequency(), request.dueDayOfMonth()));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getChargePlan", tags = TAG, summary = "Reads a charge plan with its items",
            description = "The charge plan with every item it holds, in the order they were added. Needs the "
                    + "permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "The charge plan and its items",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ChargePlanDetailResponse.class)))
    @ApiResponse(responseCode = "400", description = "The identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The charge plan does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ChargePlanDetailResponse get(@PathVariable("id") UUID id) {
        ChargePlanDetailView detail = plans.get(RequestContext.actor(), RequestContext.requestId(), id);
        ChargePlanView plan = detail.plan();
        return new ChargePlanDetailResponse(plan.id(), plan.academicYearId(), plan.name(), plan.targetScope(),
                plan.targetRefId(), plan.frequency(), plan.dueDayOfMonth(), plan.status(), plan.version(),
                plan.createdAt(), plan.updatedAt(),
                detail.items().stream().map(ChargePlanController::toResponse).toList());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateChargePlan", tags = TAG, summary = "Edits, activates or archives a charge plan",
            description = "Replaces the name, the frequency, the due day and the status of the plan (never its year, "
                    + "target scope or target reference). A draft changes all of them; ACTIVE activates it, which needs "
                    + "at least one item and freezes it. An active plan changes only its name and may be archived with "
                    + "ARCHIVED. An archived plan never changes, and there is no way back. The body carries the version "
                    + "the editor read: a different one answers 409 stale-version. Needs the permission "
                    + "charges:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:update")))
    @ApiResponse(responseCode = "200", description = "The charge plan after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ChargePlanResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid, or the body carries a year, a target scope or a "
                    + "target reference (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The charge plan does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The version of the body is not the current one (stale-version), the state of the plan does "
                    + "not allow the change (plan-not-editable) or an activation of a plan without items "
                    + "(plan-without-items)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ChargePlanResponse update(@PathVariable("id") UUID id,
            @Valid @RequestBody UpdateChargePlanRequest request) {
        return toResponse(plans.update(RequestContext.actor(), RequestContext.requestId(), id, request.version(),
                request.name(), request.frequency(), request.dueDayOfMonth(), request.status()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/items", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "addChargePlanItem", tags = TAG, summary = "Adds a payment concept to a draft plan",
            description = "Adds an active payment concept of the institution to the plan with the number of periods "
                    + "and the first day of the first one. A concept appears once per plan. Only a draft takes items. "
                    + "Needs the permission charges:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:update")))
    @ApiResponse(responseCode = "201", description = "The item created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ChargePlanItemResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid: a number of periods outside 1 to 36 or a first "
                    + "period that is not the first day of a month (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The charge plan or the payment concept does not exist in the institution of the token "
                    + "(resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The plan is not a draft (plan-not-editable), the concept is inactive "
                    + "(inactive-reference) or the plan already has the concept (duplicate-plan-item)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ChargePlanItemResponse addItem(@PathVariable("id") UUID id,
            @Valid @RequestBody AddChargePlanItemRequest request) {
        return toResponse(plans.addItem(RequestContext.actor(), RequestContext.requestId(), id,
                request.paymentConceptId(), request.periodsCount(), request.firstPeriodOn()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @DeleteMapping(path = "/{id}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "removeChargePlanItem", tags = TAG, summary = "Removes an item of a draft plan",
            description = "Removes the item of the plan. An item is not a financial datum, so it is deleted, and only "
                    + "while the plan is a draft. Needs the permission charges:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:update")))
    @ApiResponse(responseCode = "204", description = "The item was removed")
    @ApiResponse(responseCode = "400", description = "An identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The charge plan or the item does not exist in the institution of the token "
                    + "(resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The plan is not a draft (plan-not-editable)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public void removeItem(@PathVariable("id") UUID id, @PathVariable("itemId") UUID itemId) {
        plans.removeItem(RequestContext.actor(), RequestContext.requestId(), id, itemId);
    }

    private static ChargePlanResponse toResponse(ChargePlanView view) {
        return new ChargePlanResponse(view.id(), view.academicYearId(), view.name(), view.targetScope(),
                view.targetRefId(), view.frequency(), view.dueDayOfMonth(), view.status(), view.version(),
                view.createdAt(), view.updatedAt());
    }

    private static ChargePlanItemResponse toResponse(ChargePlanItemView view) {
        return new ChargePlanItemResponse(view.id(), view.planId(), view.paymentConceptId(), view.periodsCount(),
                view.firstPeriodOn(), view.createdAt());
    }
}

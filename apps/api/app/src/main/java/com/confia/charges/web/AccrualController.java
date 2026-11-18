package com.confia.charges.web;

import com.confia.charges.application.AccrualPeriodView;
import com.confia.charges.application.AccrualPreviewResult;
import com.confia.charges.application.AccrualRunDetailView;
import com.confia.charges.application.AccrualRunView;
import com.confia.charges.application.ManageAccrual;
import com.confia.charges.application.Page;
import com.confia.charges.application.RunItem;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of the accrual engine under {@code /api/v1/charges} (docs/cortes/charges-accrual.md): the periods of a plan, the
 * preview of a period, the request of a run and the reading of runs. The permission each route needs is decided before the
 * controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code charges:read} for the periods and the
 * reading of runs, {@code charges:create} for the preview and the request of a run. The institution is the one of the token,
 * never a member of a request. A run is executed by the worker, not here: the request answers 202 with a queued run.
 */
@RestController
@RequestMapping("/api/v1/charges")
public class AccrualController {

    private static final String TAG = "charges-accrual";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageAccrual accrual;

    public AccrualController(ManageAccrual accrual) {
        this.accrual = accrual;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/plans/{id}/accrual-periods", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listChargeAccrualPeriods", tags = TAG, summary = "Lists the accrual periods of a plan",
            description = "The periods the plan accrues, each with its most recent run. The server derives them from the "
                    + "frequency and the items of the plan; the browser never computes a period. Needs the permission "
                    + "charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "The periods of the plan",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AccrualPeriodResponse[].class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The plan does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "A one-time plan has an item with more than one period (plan-item-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public List<AccrualPeriodResponse> periods(@PathVariable("id") UUID id) {
        return accrual.periods(RequestContext.actor(), RequestContext.requestId(), id).stream()
                .map(AccrualController::toResponse).toList();
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/plans/{id}/accrual-preview", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "previewChargeAccrual", tags = TAG, summary = "Previews the accrual of a period",
            description = "Calculates what accruing the period would do, with nothing written: counts, exact totals by "
                    + "currency, errors by code, up to 500 rows, what would make a run refuse, and the digest to send "
                    + "back when confirming. Needs the permission charges:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:create")))
    @ApiResponse(responseCode = "200", description = "What the accrual would do",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AccrualPreviewResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid, or the period is not one of the periods of the plan (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:create, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The plan does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "A one-time plan has an item with more than one period (plan-item-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AccrualPreviewResponse preview(@PathVariable("id") UUID id,
            @Valid @RequestBody AccrualPreviewRequest request) {
        return toResponse(accrual.preview(RequestContext.actor(), RequestContext.requestId(), id,
                request.periodStart()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/plans/{id}/accrual-runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(operationId = "requestChargeAccrualRun", tags = TAG, summary = "Requests the accrual of a period",
            description = "Queues the accrual of the period of an active plan; the worker executes it. The "
                    + "Idempotency-Key header is required and stored permanently on the run: the same key and the same "
                    + "body answer the same run, the same key with another body answers 422 idempotency-payload-mismatch, "
                    + "and another key while a run of the same plan and period is queued or running answers 409 "
                    + "accrual-run-in-progress. With the previewDigest, a calculation that changed since the preview "
                    + "ends the run rejected with preview-outdated and nothing written. Needs the permission "
                    + "charges:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:create")))
    @ApiResponse(responseCode = "202", description = "The run, queued (or the run the key already produced)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AccrualRunResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid, or the period is not one of the periods of the "
                    + "plan (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold charges:create, or the Origin is missing or not allowed "
                    + "(forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The plan does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "Another run of the plan and period is queued or running (accrual-run-in-progress)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422",
            description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AccrualRunResponse request(@PathVariable("id") UUID id,
            @Parameter(required = true, description = "1 to 128 visible characters, unique per request")
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AccrualRunRequest request) {
        return toResponse(accrual.request(RequestContext.actor(), RequestContext.requestId(), idempotencyKey, id,
                request.periodStart(), request.previewDigest()));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/accrual-runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listChargeAccrualRuns", tags = TAG, summary = "Lists the accrual runs",
            description = "One page of the accrual runs of the institution of the token, most recent first, optionally of "
                    + "one plan. page counts from 0, size defaults to 50 and is at most 200. Needs the permission "
                    + "charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "One page of accrual runs",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AccrualRunPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AccrualRunPageResponse list(
            @Parameter(description = "Only the runs of this plan")
            @RequestParam(name = "planId", required = false) UUID planId,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        Page<AccrualRunView> result = accrual.list(RequestContext.actor(), RequestContext.requestId(), planId, page,
                size);
        return new AccrualRunPageResponse(result.items().stream().map(AccrualController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/accrual-runs/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getChargeAccrualRun", tags = TAG, summary = "Reads an accrual run",
            description = "The run, its counters and one page of its items (what happened to each student and concept, "
                    + "with the error code of the ones that failed). page counts from 0, size defaults to 50 and is at "
                    + "most 200. Needs the permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "The run with one page of items",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AccrualRunDetailResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The run does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AccrualRunDetailResponse get(@PathVariable("runId") UUID runId,
            @Parameter(description = "Page of items, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Items per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        AccrualRunDetailView detail = accrual.get(RequestContext.actor(), RequestContext.requestId(), runId, page, size);
        Page<RunItem> items = detail.items();
        return new AccrualRunDetailResponse(toResponse(detail.run()),
                items.items().stream().map(AccrualController::toResponse).toList(), items.total(), items.page(),
                items.size());
    }

    private static AccrualRunResponse toResponse(AccrualRunView run) {
        return new AccrualRunResponse(run.id(), run.planId(), run.periodStart(), run.status(), run.previewChecked(),
                run.queuedAt(), run.startedAt(), run.finishedAt(), run.studentsEvaluated(), run.chargesCreated(),
                run.chargesExisting(), run.chargesFailed(), run.failureCode());
    }

    private static AccrualRunItemResponse toResponse(RunItem item) {
        return new AccrualRunItemResponse(item.studentId(), item.paymentConceptId(), item.outcome().name(),
                item.error(), item.chargeId(), item.existingChargeId());
    }

    private static AccrualPeriodResponse toResponse(AccrualPeriodView period) {
        return new AccrualPeriodResponse(period.periodStart(), period.label(), period.dueOn(),
                period.paymentConceptIds(), period.lastRun() == null ? null : toResponse(period.lastRun()));
    }

    private static AccrualPreviewResponse toResponse(AccrualPreviewResult preview) {
        List<AccrualPreviewResponse.Total> totals = preview.totals().stream()
                .map(total -> new AccrualPreviewResponse.Total(total.currency(), total.charges(),
                        AccrualMoney.of(total.base()), AccrualMoney.of(total.tax()), AccrualMoney.of(total.total())))
                .toList();
        Map<String, Integer> errors = new LinkedHashMap<>(preview.errorCounts());
        List<AccrualPreviewResponse.Item> items = preview.items().stream()
                .map(item -> new AccrualPreviewResponse.Item(item.studentId(), item.paymentConceptId(),
                        item.outcome().name(), item.error(),
                        AccrualMoney.of(item.base()), AccrualMoney.of(item.tax()), AccrualMoney.of(item.total()),
                        item.existingChargeId()))
                .toList();
        return new AccrualPreviewResponse(preview.planId(), preview.periodStart(), preview.studentsEvaluated(),
                preview.toCreate(), preview.alreadyAccrued(), preview.alreadyAccruedOtherPlan(), preview.errors(), totals,
                errors, items, preview.itemsTruncated(),
                preview.blockerCodes(), preview.previewDigest());
    }
}

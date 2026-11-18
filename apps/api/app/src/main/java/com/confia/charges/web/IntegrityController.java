package com.confia.charges.web;

import com.confia.charges.application.Page;
import com.confia.charges.application.ViewIntegrityRuns;
import com.confia.charges.application.IntegrityFindingView;
import com.confia.charges.application.IntegrityRunView;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The results of the nightly integrity check of the charges under {@code /api/v1/charges/integrity-runs}
 * (docs/cortes/charges-void-and-integrity.md). Reads only: the worker produces them. The permission {@code charges:read} is
 * decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}; the institution is the
 * one of the token, so each institution sees only its own runs.
 */
@RestController
@RequestMapping("/api/v1/charges/integrity-runs")
public class IntegrityController {

    private static final String TAG = "charges-integrity";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ViewIntegrityRuns runs;

    public IntegrityController(ViewIntegrityRuns runs) {
        this.runs = runs;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listChargeIntegrityRuns", tags = TAG, summary = "Lists the integrity runs",
            description = "One page of the results of the nightly integrity check of the institution of the token, most "
                    + "recent first. page counts from 0, size defaults to 50 and is at most 200. Needs the permission "
                    + "charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "One page of integrity runs",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IntegrityRunPageResponse.class)))
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
    public IntegrityRunPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        Page<IntegrityRunView> result = runs.list(RequestContext.actor(), RequestContext.requestId(), page, size);
        return new IntegrityRunPageResponse(result.items().stream().map(IntegrityController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getLatestChargeIntegrityRun", tags = TAG, summary = "Reads the latest integrity run",
            description = "The most recent result of the nightly integrity check of the institution of the token, with the "
                    + "discrepancies it confirmed. Needs the permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "The latest run with its discrepancies",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IntegrityRunDetailResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted "
                    + "(token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold charges:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The check has not run for the institution of the token yet (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public IntegrityRunDetailResponse latest() {
        return toResponse(runs.latest(RequestContext.actor(), RequestContext.requestId()));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{runId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getChargeIntegrityRun", tags = TAG, summary = "Reads an integrity run",
            description = "One result of the nightly integrity check of the institution of the token, with the "
                    + "discrepancies it confirmed. Needs the permission charges:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "charges:read")))
    @ApiResponse(responseCode = "200", description = "The run with its discrepancies",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = IntegrityRunDetailResponse.class)))
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
    public IntegrityRunDetailResponse get(@PathVariable("runId") UUID runId) {
        return toResponse(runs.get(RequestContext.actor(), RequestContext.requestId(), runId));
    }

    private static IntegrityRunResponse toResponse(IntegrityRunView run) {
        return new IntegrityRunResponse(run.id(), run.startedAt(), run.finishedAt(), run.status(),
                run.chargesChecked(), run.postingsChecked(), run.discrepancyCount());
    }

    private static IntegrityRunDetailResponse toResponse(ViewIntegrityRuns.Detail detail) {
        return new IntegrityRunDetailResponse(toResponse(detail.run()),
                detail.findings().stream().map(IntegrityController::toResponse).toList());
    }

    private static IntegrityFindingResponse toResponse(IntegrityFindingView finding) {
        return new IntegrityFindingResponse(finding.kind(), finding.chargeId(), finding.ledgerTransactionId(),
                AccrualMoney.of(finding.expected()), AccrualMoney.of(finding.actual()));
    }
}

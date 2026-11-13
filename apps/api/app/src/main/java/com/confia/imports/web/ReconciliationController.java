package com.confia.imports.web;

import com.confia.imports.application.ManageReconciliations;
import com.confia.imports.application.Page;
import com.confia.imports.application.ReconciliationPreview;
import com.confia.imports.application.ReconciliationView;
import com.confia.imports.application.ReconciliationView.KindCountView;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.ratelimit.RateLimitKey;
import com.confia.shared.web.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of the reconciliation of the migration under {@code /api/v1/imports/reconciliations}
 * (docs/cortes/imports-reconciliation.md): the signing of a record, the preview of the counts it would hold, and the
 * history. The permission each route needs is decided before the controller runs, by the permission decider over
 * {@code PermissionedEndpoints}: {@code students:create} for the signing and {@code students:read} for the other two.
 * The institution is the one of the token. The body carries the year, the reference of the official roster and its
 * total; every count of the migration is computed by the server. A record is never changed or deleted.
 */
@RestController
@RequestMapping("/api/v1/imports/reconciliations")
public class ReconciliationController {

    private static final String TAG = "imports";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageReconciliations reconciliations;

    public ReconciliationController(ManageReconciliations reconciliations) {
        this.reconciliations = reconciliations;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "signReconciliation", tags = TAG, summary = "Signs the reconciliation of the migration",
            description = "Records, for an academic year, what the import created and left rejected of each kind and the "
                    + "active enrollments of the year, next to the total the official roster declares and the "
                    + "reference of that roster. The server computes every count; the signer only says the year, the "
                    + "reference and the total. The record is never changed or deleted: signing again keeps both. "
                    + "Needs the permission students:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:create")))
    @ApiResponse(responseCode = "201", description = "The reconciliation signed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReconciliationResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The academic year does not exist (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ReconciliationResponse sign(@Valid @RequestBody SignReconciliationRequest request) {
        return toResponse(reconciliations.sign(RequestContext.actor(), RequestContext.requestId(),
                request.academicYearId(), request.officialReference(), request.officialEnrollmentCount()));
    }

    @GetMapping(path = "/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "previewReconciliation", tags = TAG, summary = "Previews the reconciliation of the migration",
            description = "The counts a reconciliation signed now for the academic year would record: what the import "
                    + "created and left rejected of each kind and the active enrollments of the year. Nothing is "
                    + "written. Needs the permission students:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "The counts",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReconciliationPreviewResponse.class)))
    @ApiResponse(responseCode = "400", description = "The year identifier is missing or malformed (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold students:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The academic year does not exist (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ReconciliationPreviewResponse preview(
            @Parameter(description = "The academic year") @RequestParam(name = "academicYearId") UUID academicYearId) {
        ReconciliationPreview preview = reconciliations.preview(RequestContext.actor(), RequestContext.requestId(),
                academicYearId);
        return new ReconciliationPreviewResponse(preview.academicYearId(), preview.enrollmentsActive(),
                toResponse(preview.students()), toResponse(preview.guardians()), toResponse(preview.guardianLinks()),
                toResponse(preview.enrollments()));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "listReconciliations", tags = TAG, summary = "Lists the reconciliations of the migration",
            description = "One page of the reconciliations signed in the institution of the token, newest first. Needs "
                    + "the permission students:read. page counts from 0 and size defaults to 50 and is at most 100.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "One page of the reconciliations",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ReconciliationPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold students:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ReconciliationPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 100; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(100) int size) {
        Page<ReconciliationView> result = reconciliations.list(RequestContext.actor(), RequestContext.requestId(),
                page, size);
        return new ReconciliationPageResponse(result.items().stream().map(ReconciliationController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    private static ReconciliationResponse toResponse(ReconciliationView view) {
        return new ReconciliationResponse(view.id(), view.academicYearId(), view.officialReference(),
                view.officialEnrollmentCount(), view.enrollmentsActive(), view.difference(),
                toResponse(view.students()), toResponse(view.guardians()), toResponse(view.guardianLinks()),
                toResponse(view.enrollments()), view.signedBy(), view.signedAt());
    }

    private static KindCountResponse toResponse(KindCountView view) {
        return new KindCountResponse(view.created(), view.rejected());
    }
}

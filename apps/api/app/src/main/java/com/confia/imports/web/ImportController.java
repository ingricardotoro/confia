package com.confia.imports.web;

import com.confia.imports.application.ImportBatchView;
import com.confia.imports.application.ImportRun;
import com.confia.imports.application.ImportStudents;
import com.confia.imports.application.Page;
import com.confia.imports.application.RejectedImportFileException;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.problem.FieldViolation;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemCodeException;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The routes of the student import under {@code /api/v1/imports} (docs/cortes/imports-students.md): the simulation and
 * the application of a CSV file, both {@code multipart/form-data} with the file in the part {@code file}, and the
 * history of the batches. The permission each route needs is decided before the controller runs, by the permission
 * decider over {@code PermissionedEndpoints}: {@code students:create} for the two {@code POST} and {@code
 * students:read} for the history. The institution is the one of the token. The size of the file is limited at the web
 * edge (5 MB) and again by the use case, which also limits the rows (10,000) and refuses a file that is not UTF-8.
 *
 * <p>The file is a file of personal data of minors: it is read into memory for the length of the call and is never
 * stored, logged or echoed. A file that cannot be read as a student import is a {@code 400 validation-failed} with the
 * {@code errors} list (a field of the template, or a column position, and a reason; never a value). The rows with a
 * defect are not an error: they come back in the answer as line, column and reason.
 *
 * <p>The two {@code POST} are rate limited strictly, a few runs an hour, per client address ({@code admin-import})
 * and per authenticated account ({@code admin-import-account}); the history uses {@code admin-read} and {@code
 * admin-read-account}. Every answer carries {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private static final String TAG = "imports";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ImportStudents imports;

    public ImportController(ImportStudents imports) {
        this.imports = imports;
    }

    @PostMapping(path = "/students/simulate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-import")
    @RateLimited(policy = "admin-import-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "simulateStudentImport", tags = TAG, summary = "Simulates the import of students",
            description = "Reads a CSV file (UTF-8, separator , or ; detected in the header, up to 5 MB and 10000 "
                    + "rows, columns legacyId, firstName, lastName, birthDate and optionally nationalId and sex), "
                    + "judges every row with the rules of the student registry and records a VALIDATED batch. It "
                    + "writes no student. The rejections (line, column, reason; never a value) are returned and not "
                    + "stored; at most 200 are listed. Needs the permission students:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:create")))
    @ApiResponse(responseCode = "200", description = "The simulation batch and the rejections",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ImportRunResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The file is missing, empty, too big, not UTF-8, not a well-formed CSV, has too many rows "
                    + "or its header is not the template (validation-failed, with errors)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "415", description = "The request is not multipart/form-data (unsupported-media-type)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many imports from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ImportRunResponse simulate(@RequestPart("file") MultipartFile file) {
        try {
            return toResponse(imports.simulate(RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
        } catch (RejectedImportFileException e) {
            throw invalid(e);
        }
    }

    @PostMapping(path = "/students/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-import")
    @RateLimited(policy = "admin-import-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "applyStudentImport", tags = TAG, summary = "Applies the import of students",
            description = "Applies the same file the account simulated: it only proceeds when the SHA-256 of the file "
                    + "matches a simulation by the same account in the institution. It judges the file again and "
                    + "registers the valid rows through the same use case as the hand registration (generated "
                    + "code, encrypted document, audit). A row imported before is skipped, so applying twice "
                    + "creates nothing twice. Needs the permission students:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:create")))
    @ApiResponse(responseCode = "200", description = "The APPLIED batch and the rejections",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ImportRunResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The file cannot be read as a student import (validation-failed, with errors)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The account never simulated a file with this fingerprint (import-not-simulated), or its "
                    + "last simulation was of another file (import-file-changed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "415", description = "The request is not multipart/form-data (unsupported-media-type)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many imports from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ImportRunResponse apply(@RequestPart("file") MultipartFile file) {
        try {
            return toResponse(imports.apply(RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
        } catch (RejectedImportFileException e) {
            throw invalid(e);
        }
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "listImportBatches", tags = TAG, summary = "Lists the import batches",
            description = "One page of the batches (simulations and applications) of the institution of the token, "
                    + "newest first. A batch holds counts and the SHA-256 of the file, never a row of it. Needs the "
                    + "permission students:read. page counts from 0 and size defaults to 50 and is at most 100.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "One page of the batches",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ImportBatchPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ImportBatchPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 100; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(100) int size) {
        Page<ImportBatchView> result = imports.list(RequestContext.actor(), RequestContext.requestId(), page, size);
        return new ImportBatchPageResponse(result.items().stream().map(ImportController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // The cause can name a temporary path, not the content; it is not chained.
            throw new UncheckedIOException("the uploaded file cannot be read", new IOException("unreadable"));
        }
    }

    private static ProblemCodeException invalid(RejectedImportFileException e) {
        List<FieldViolation> violations = e.problems().stream()
                .map(problem -> new FieldViolation(problem.field(), problem.reason())).toList();
        return new ProblemCodeException(ProblemCode.VALIDATION_FAILED, violations);
    }

    private static ImportRunResponse toResponse(ImportRun run) {
        return new ImportRunResponse(toResponse(run.batch()),
                run.rejections().stream()
                        .map(r -> new RejectionResponse(r.line(), r.field(), r.reason())).toList(),
                run.rejectionsTruncated());
    }

    private static ImportBatchResponse toResponse(ImportBatchView view) {
        return new ImportBatchResponse(view.id(), view.kind(), view.status(), view.fileSha256(), view.totalRows(),
                view.validRows(), view.rejectedRows(), view.createdRows(), view.skippedRows(), view.createdAt(),
                view.appliedAt());
    }
}

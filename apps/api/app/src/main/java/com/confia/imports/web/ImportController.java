package com.confia.imports.web;

import com.confia.imports.application.ImportBatchView;
import com.confia.imports.application.ImportRun;
import com.confia.imports.application.ManageImports;
import com.confia.imports.application.KindNotReadableException;
import com.confia.imports.application.UnknownImportKindException;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.web.authorization.PermissionDecider;
import com.confia.shared.web.authorization.PermissionDeniedException;
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
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
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

    private static final String LIST_ROUTE = "/api/v1/imports";

    private final ManageImports imports;

    public ImportController(ManageImports imports) {
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
                    + "writes no student. It detects only the problems of the file itself (field rules, repeated "
                    + "legacyId or national id inside the file) and rows already imported by legacyId; it never "
                    + "checks the national ids already stored, so a national id held by another student is not "
                    + "reported here and the rejected count of the application can be higher. The rejections (line, "
                    + "column, reason; never a value) are returned and not stored; at most 200 are listed. Needs "
                    + "the permission students:create.",
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
            return toResponse(imports.simulate("students", RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
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
                    + "creates nothing twice. A national id already held by another student is found only here, "
                    + "when the row is registered (each attempt is a real, audited write like a hand registration), "
                    + "and the row is rejected as duplicate-national-id without its value: the rejected count of "
                    + "the application can therefore be higher than the one the simulation reported. Needs the "
                    + "permission students:create.",
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
            return toResponse(imports.apply("students", RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
        } catch (RejectedImportFileException e) {
            throw invalid(e);
        }
    }

    @PostMapping(path = "/guardians/simulate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-import")
    @RateLimited(policy = "admin-import-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "simulateGuardianImport", tags = TAG, summary = "Simulates the import of guardians",
            description = "Reads a CSV file (UTF-8, separator , or ; detected in the header, up to 5 MB and 10000 "
                    + "rows, columns legacyId, firstName, lastName and optionally nationalId, rtn, email, phone, address and preferredChannel), "
                    + "judges every row with the rules of the guardian registry and records a VALIDATED batch. It "
                    + "writes no guardian. It detects only the problems of the file itself (field rules, repeated "
                    + "legacyId or national id inside the file) and rows already imported by legacyId; it never "
                    + "checks the national ids already stored, so a national id held by another guardian is not "
                    + "reported here and the rejected count of the application can be higher. The rejections (line, "
                    + "column, reason; never a value) are returned and not stored; at most 200 are listed. Needs "
                    + "the permission guardians:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:create")))
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
            description = "The account does not hold guardians:create, or the Origin is missing or "
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
    public ImportRunResponse simulateGuardians(@RequestPart("file") MultipartFile file) {
        try {
            return toResponse(imports.simulate("guardians", RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
        } catch (RejectedImportFileException e) {
            throw invalid(e);
        }
    }

    @PostMapping(path = "/guardians/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-import")
    @RateLimited(policy = "admin-import-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "applyGuardianImport", tags = TAG, summary = "Applies the import of guardians",
            description = "Applies the same file the account simulated: it only proceeds when the SHA-256 of the file "
                    + "matches a simulation by the same account in the institution. It judges the file again and "
                    + "registers the valid rows through the same use case as the hand registration (generated "
                    + "code, encrypted document, audit). A row imported before is skipped, so applying twice "
                    + "creates nothing twice. A national id already held by another guardian is found only here, "
                    + "when the row is registered (each attempt is a real, audited write like a hand registration), "
                    + "and the row is rejected as duplicate-national-id without its value: the rejected count of "
                    + "the application can therefore be higher than the one the simulation reported. Needs the "
                    + "permission guardians:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:create")))
    @ApiResponse(responseCode = "200", description = "The APPLIED batch and the rejections",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ImportRunResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The file cannot be read as a guardian import (validation-failed, with errors)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold guardians:create, or the Origin is missing or "
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
    public ImportRunResponse applyGuardians(@RequestPart("file") MultipartFile file) {
        try {
            return toResponse(imports.apply("guardians", RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
        } catch (RejectedImportFileException e) {
            throw invalid(e);
        }
    }

    @PostMapping(path = "/guardian-links/simulate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-import")
    @RateLimited(policy = "admin-import-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "simulateGuardianLinkImport", tags = TAG, summary = "Simulates the import of guardian links",
            description = "Reads a CSV file (UTF-8, separator , or ; detected in the header, up to 5 MB and 10000 "
                    + "rows, columns studentLegacyId, guardianLegacyId, relationship, financialResponsibilityPercent and "
                    + "optionally isPrimaryPayer and isBillingRecipient), groups the rows by student and judges each group "
                    + "as the complete set the hand registration demands (INV-32: the percentages add up to exactly "
                    + "100.00, at most one primary payer and one billing recipient, no guardian twice); a group with any "
                    + "problem is rejected whole. The students and guardians are the ones already imported, found by "
                    + "their legacyId; a student that already has current links is skipped (student-already-linked), "
                    + "the import never replaces links. It writes nothing and records a VALIDATED batch. A file with a row "
                    + "of the wrong shape or without a usable student reference is refused whole (400, file / row-shape), because "
                    + "such a row cannot be attributed to a student's set. The rejections "
                    + "(line, column, reason; never a value) are returned and not stored; at most 200 are listed. Needs "
                    + "the permissions guardians:create and guardians:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    {@ExtensionProperty(name = "x-required-permission", value = "guardians:create"),
                            @ExtensionProperty(name = "x-also-required-permissions", value = "[\"guardians:update\"]",
                                    parseValue = true)}))
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
            description = "The account does not hold guardians:create and guardians:update, or the Origin is missing or "
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
    public ImportRunResponse simulateGuardianLinks(@RequestPart("file") MultipartFile file) {
        try {
            return toResponse(imports.simulate("guardian-links", RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
        } catch (RejectedImportFileException e) {
            throw invalid(e);
        }
    }

    @PostMapping(path = "/guardian-links/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-import")
    @RateLimited(policy = "admin-import-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "applyGuardianLinkImport", tags = TAG, summary = "Applies the import of guardian links",
            description = "Applies the same file the account simulated: it only proceeds when the SHA-256 of the file "
                    + "matches a simulation of this kind by the same account in the institution. It judges the file again "
                    + "and registers each valid group, one transaction per student, with the same use case as the "
                    + "replacement of the set of guardians of a student (lock of the student, history, audit, INV-32). A "
                    + "student that already has current links, by hand or from an earlier application, is skipped "
                    + "(student-already-linked), so applying twice creates nothing twice. Needs the permissions "
                    + "guardians:create and guardians:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    {@ExtensionProperty(name = "x-required-permission", value = "guardians:create"),
                            @ExtensionProperty(name = "x-also-required-permissions", value = "[\"guardians:update\"]",
                                    parseValue = true)}))
    @ApiResponse(responseCode = "200", description = "The APPLIED batch and the rejections",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ImportRunResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The file cannot be read as a guardian links import (validation-failed, with errors)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold guardians:create and guardians:update, or the Origin is missing or "
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
    public ImportRunResponse applyGuardianLinks(@RequestPart("file") MultipartFile file) {
        try {
            return toResponse(imports.apply("guardian-links", RequestContext.actor(), RequestContext.requestId(), bytesOf(file)));
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
                    + "permission students:read (and guardians:read for the batches of the guardians kinds, which are listed only to an account that holds it; asking for a kind it cannot read is a 403). page counts from 0 and size defaults to 50 and is at most 100.",
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
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(100) int size,
            @Parameter(description = "Only the batches of this kind: students, guardians or guardian-links. Without it, "
                    + "the kinds the account may read")
            @RequestParam(name = "kind", required = false) String kind,
            @Parameter(hidden = true) HttpServletRequest request) {
        AuthenticatedActor actor = RequestContext.actor();
        // The decider of the edge resolved the permissions of this request already; they are read, not resolved again.
        Object resolved = request.getAttribute(PermissionDecider.RESOLVED_PERMISSIONS);
        if (!(resolved instanceof Set<?> held)) {
            throw new IllegalStateException("the permission decider did not resolve the permissions of this request");
        }
        Page<ImportBatchView> result;
        try {
            result = imports.list(actor, RequestContext.requestId(), page, size, kind,
                    held.contains(Permission.STUDENTS_READ), held.contains(Permission.GUARDIANS_READ));
        } catch (UnknownImportKindException e) {
            throw new ProblemCodeException(ProblemCode.VALIDATION_FAILED, List.of(new FieldViolation("kind", "invalid")));
        } catch (KindNotReadableException e) {
            throw new PermissionDeniedException(e.students() ? Permission.STUDENTS_READ : Permission.GUARDIANS_READ,
                    LIST_ROUTE, actor);
        }
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

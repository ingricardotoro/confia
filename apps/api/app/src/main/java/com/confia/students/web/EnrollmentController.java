package com.confia.students.web;

import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.ratelimit.RateLimitKey;
import com.confia.shared.web.ratelimit.RateLimited;
import com.confia.students.application.EnrollmentView;
import com.confia.students.application.ManageEnrollments;
import com.confia.students.application.Page;
import com.confia.students.application.RequestFieldException;
import com.confia.students.application.TransferView;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The six routes of the enrollments (docs/cortes/enrollments.md): {@code GET /api/v1/enrollments}, {@code GET
 * /api/v1/enrollments/{id}}, {@code GET /api/v1/students/{studentId}/enrollments}, {@code POST
 * /api/v1/enrollments}, {@code POST /api/v1/enrollments/{id}/transfer} and {@code POST
 * /api/v1/enrollments/{id}/exit}. The permission each route needs is decided before the controller runs, by
 * the permission decider over {@code PermissionedEndpoints}: {@code students:read} for the three reads, {@code
 * students:create} for the enrollment and {@code students:update} for the transfer and the exit. The
 * institution is the one of the token, never a member of the request; the academic year is the one of the
 * section. Nothing is deleted. Every route is rate limited twice: per client address (reads {@code
 * admin-read}, writes {@code admin-write}) and per authenticated account ({@code admin-read-account}, {@code
 * admin-write-account}). The writes need no {@code Idempotency-Key}: they move no money, and repeating a
 * transition already done answers {@code 409 invalid-enrollment-state}. The document of the student is never
 * part of an answer.
 */
@RestController
@RequestMapping("/api/v1")
public class EnrollmentController {

    private static final String TAG = "enrollments";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageEnrollments enrollments;

    public EnrollmentController(ManageEnrollments enrollments) {
        this.enrollments = enrollments;
    }

    @GetMapping(path = "/enrollments", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "listEnrollments", tags = TAG, summary = "Lists the enrollments",
            description = "One page of the enrollments of the institution of the token. Needs the permission "
                    + "students:read. page counts from 0, size defaults to 50 and is at most 200, sort is "
                    + "enrolledOn, studentLastName or status optionally followed by ,asc or ,desc "
                    + "(studentLastName ascending by default). academicYearId defaults to the ACTIVE academic "
                    + "year when there is one (every year otherwise); sectionId, gradeId and status narrow the "
                    + "list.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "One page of the enrollments",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EnrollmentPageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range or the sort field is not allowed (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public EnrollmentPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of enrolledOn, studentLastName or status, optionally followed by "
                    + ",asc or ,desc; studentLastName ascending when absent")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Academic year; the ACTIVE one when absent and there is one")
            @RequestParam(name = "academicYearId", required = false) UUID academicYearId,
            @Parameter(description = "Only the enrollments in this section")
            @RequestParam(name = "sectionId", required = false) UUID sectionId,
            @Parameter(description = "Only the enrollments in the sections of this grade")
            @RequestParam(name = "gradeId", required = false) UUID gradeId,
            @Parameter(description = "ACTIVE, WITHDRAWN, TRANSFERRED or GRADUATED; all when absent")
            @RequestParam(name = "status", required = false)
            @Pattern(regexp = "ACTIVE|WITHDRAWN|TRANSFERRED|GRADUATED") String status) throws BindException {
        try {
            Page<EnrollmentView> result = enrollments.list(RequestContext.actor(), RequestContext.requestId(), page,
                    size, sort, academicYearId, sectionId, gradeId, status);
            return new EnrollmentPageResponse(result.items().stream().map(EnrollmentController::toResponse).toList(),
                    result.total(), result.page(), result.size());
        } catch (RequestFieldException e) {
            throw invalid(e);
        }
    }

    @GetMapping(path = "/enrollments/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "getEnrollment", tags = TAG, summary = "Reads an enrollment",
            description = "The enrollment with this identifier in the institution of the token, with the history "
                    + "of its sections. Needs the permission students:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "The enrollment",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentEnrollmentResponse.class)))
    @ApiResponse(responseCode = "400", description = "The identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The enrollment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentEnrollmentResponse get(@PathVariable("id") UUID id) {
        return toResponse(enrollments.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @GetMapping(path = "/students/{studentId}/enrollments", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "listEnrollmentsOfStudent", tags = TAG, summary = "Lists the enrollments of a student",
            description = "Every enrollment of the student, the most recent first. Needs the permission "
                    + "students:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "The enrollments of the student",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EnrollmentListResponse.class)))
    @ApiResponse(responseCode = "400", description = "The identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The student does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public EnrollmentListResponse ofStudent(@PathVariable("studentId") UUID studentId) {
        return new EnrollmentListResponse(enrollments.ofStudent(RequestContext.actor(), RequestContext.requestId(),
                studentId).stream().map(EnrollmentController::toResponse).toList());
    }

    @PostMapping(path = "/enrollments", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "enrollStudent", tags = TAG, summary = "Enrolls a student in a section",
            description = "Enrolls an ACTIVE student in an active section; the academic year is the one of the "
                    + "section. The date is inside the year (both ends included) and not in the future. A "
                    + "student has at most one ACTIVE enrollment per academic year, and a section takes no more "
                    + "ACTIVE enrollments than its capacity. Needs the permission students:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:create")))
    @ApiResponse(responseCode = "201", description = "The enrollment created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentEnrollmentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid, or the date is outside the year or in the future "
                    + "(validation-failed, with the field)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The student or the section does not exist in the institution of the token "
                    + "(resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The student is not active (student-not-active), the section is inactive "
                    + "(inactive-reference) or full (section-full), the year is closed (academic-year-closed) or "
                    + "the student already has an active enrollment in the year (duplicate-active-enrollment)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentEnrollmentResponse enroll(@Valid @RequestBody EnrollStudentRequest request) throws BindException {
        try {
            return toResponse(enrollments.enroll(RequestContext.actor(), RequestContext.requestId(),
                    request.studentId(), request.sectionId(), request.enrolledOn()));
        } catch (RequestFieldException e) {
            throw invalid(e);
        }
    }

    @PostMapping(path = "/enrollments/{id}/transfer", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "transferEnrollment", tags = TAG, summary = "Moves an enrollment to another section",
            description = "Moves an ACTIVE enrollment to another section of the same academic year, which must be "
                    + "active and have a free seat, and adds an immutable entry to the history. Needs the "
                    + "permission students:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:update")))
    @ApiResponse(responseCode = "200", description = "The enrollment in its new section, with its history",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentEnrollmentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid, the date is out of range or the target is the "
                    + "current section (validation-failed, with the field)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The enrollment or the target section does not exist in the institution of the token "
                    + "(resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The enrollment is not active (invalid-enrollment-state), the target is of another year "
                    + "(transfer-year-mismatch), inactive (inactive-reference) or full (section-full) or the year "
                    + "is closed (academic-year-closed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentEnrollmentResponse transfer(@PathVariable("id") UUID id,
            @Valid @RequestBody TransferEnrollmentRequest request) throws BindException {
        try {
            return toResponse(enrollments.transfer(RequestContext.actor(), RequestContext.requestId(), id,
                    request.toSectionId(), request.transferredOn(), request.reason()));
        } catch (RequestFieldException e) {
            throw invalid(e);
        }
    }

    @PostMapping(path = "/enrollments/{id}/exit", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "exitEnrollment", tags = TAG, summary = "Ends an enrollment",
            description = "Ends an ACTIVE enrollment as WITHDRAWN, TRANSFERRED (to another institution) or "
                    + "GRADUATED, which are final. The reason is required for the first two. Repeating an exit "
                    + "already done answers invalid-enrollment-state. Needs the permission students:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:update")))
    @ApiResponse(responseCode = "200", description = "The enrollment after the exit, with its history",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentEnrollmentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid, the date is out of range or the reason is "
                    + "missing (validation-failed, with the field)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold students:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The enrollment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The enrollment is not active (invalid-enrollment-state) or the year is closed "
                    + "(academic-year-closed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentEnrollmentResponse exit(@PathVariable("id") UUID id, @Valid @RequestBody ExitEnrollmentRequest request)
            throws BindException {
        try {
            return toResponse(enrollments.exit(RequestContext.actor(), RequestContext.requestId(), id,
                    request.status(), request.withdrawnOn(), request.withdrawalReason()));
        } catch (RequestFieldException e) {
            throw invalid(e);
        }
    }

    /**
     * The same answer as a failed Bean Validation: the handler of the edge reads the field and the constraint
     * (never the value) of this binding error and lists them in {@code errors}.
     */
    private static BindException invalid(RequestFieldException e) {
        BindException binding = new BindException(new Object(), "request");
        binding.addError(new FieldError("request", e.field(), null, false, new String[] {e.reason()}, null, null));
        return binding;
    }

    private static StudentEnrollmentResponse toResponse(EnrollmentView view) {
        List<EnrollmentTransferResponse> transfers = view.transfers() == null ? null
                : view.transfers().stream().map(EnrollmentController::toTransferResponse).toList();
        return new StudentEnrollmentResponse(view.id(), view.studentId(), view.studentCode(), view.studentFirstName(),
                view.studentLastName(), view.academicYearId(), view.academicYearName(), view.sectionId(),
                view.sectionName(), view.gradeId(), view.gradeName(), view.modalityName(), view.enrolledOn(),
                view.status(), view.withdrawnOn(), view.withdrawalReason(), view.createdAt(), view.updatedAt(),
                transfers);
    }

    private static EnrollmentTransferResponse toTransferResponse(TransferView view) {
        return new EnrollmentTransferResponse(view.id(), view.fromSectionId(), view.fromSectionName(),
                view.toSectionId(), view.toSectionName(), view.transferredOn(), view.reason(), view.transferredBy(),
                view.createdAt());
    }
}

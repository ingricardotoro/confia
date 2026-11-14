package com.confia.students.web;

import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.ratelimit.RateLimitKey;
import com.confia.shared.web.ratelimit.RateLimited;
import com.confia.students.application.ManageStudents;
import com.confia.students.application.Page;
import com.confia.students.application.StudentView;
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
 * {@code GET}, {@code POST} and {@code PUT} of {@code /api/v1/students} (docs/cortes/students-registry.md).
 * The permission each route needs is decided before the controller runs, by the permission decider over
 * {@code PermissionedEndpoints}: {@code students:read} for the list and the detail, {@code
 * students:create} for the creation and {@code students:update} for the edit. The institution is the one
 * of the token, never a member of the request, and nothing is deleted: a student is made inactive with
 * {@code PUT}. Every route is rate limited twice: per client address (reads {@code admin-read}, 300 a
 * minute; writes {@code admin-write}, 60 a minute) and per authenticated account (reads {@code
 * admin-read-account}, 600 a minute; writes {@code admin-write-account}, 120 a minute). The national identity document is never part of an answer: only whether there is one. The
 * approved answers are declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/students")
public class StudentController {

    private static final String TAG = "students";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageStudents students;

    public StudentController(ManageStudents students) {
        this.students = students;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "listStudents", tags = TAG, summary = "Lists the students",
            description = "One page of the students of the institution of the token, active and inactive. Needs "
                    + "the permission students:read. page counts from 0, size defaults to 50 and is at most 200, "
                    + "sort is a field of studentCode, lastName, firstName or birthDate optionally followed by "
                    + ",asc or ,desc (lastName ascending by default), q looks in the first name, the last name "
                    + "and the code (2 to 100 characters) and status keeps ACTIVE or INACTIVE students.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "One page of the students",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentPageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range, the search is shorter than 2 characters or the sort "
                    + "field is not allowed (validation-failed)",
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
    public StudentPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of studentCode, lastName, firstName or birthDate, optionally "
                    + "followed by ,asc or ,desc; lastName ascending when absent")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Text to look for in the first name, the last name and the code; 2 to 100 characters")
            @RequestParam(name = "q", required = false) @Size(min = 2, max = 100) String q,
            @Parameter(description = "ACTIVE or INACTIVE; both when absent")
            @RequestParam(name = "status", required = false) @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
        Page<StudentView> result = students.list(RequestContext.actor(), RequestContext.requestId(), page, size,
                sort, q, status);
        return new StudentPageResponse(result.items().stream().map(StudentController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "getStudent", tags = TAG, summary = "Reads a student",
            description = "The student with this identifier in the institution of the token. The national "
                    + "identity document is never returned: only hasNationalId. Needs "
                    + "the permission students:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:read")))
    @ApiResponse(responseCode = "200", description = "The student",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentResponse.class)))
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
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentResponse get(@PathVariable("id") UUID id) {
        return toResponse(students.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "createStudent", tags = TAG, summary = "Registers a student",
            description = "Registers a student; its code is unique in the institution and is generated "
                    + "(E-000001, E-000002...) when missing. The national identity document, if sent, is stored "
                    + "encrypted and never returned. Needs the permission students:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:create")))
    @ApiResponse(responseCode = "201", description = "The student created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentResponse.class)))
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
    @ApiResponse(responseCode = "409",
            description = "The code is taken (duplicate-student-code) or another student has the same "
                    + "document (duplicate-national-id)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentResponse create(@Valid @RequestBody CreateStudentRequest request) {
        return toResponse(students.create(RequestContext.actor(), RequestContext.requestId(),
                request.studentCode(), request.firstName(), request.lastName(), request.birthDate(),
                request.sex(), request.nationalId()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "updateStudent", tags = TAG, summary = "Edits a student",
            description = "Replaces the editable members of the student, status included; INACTIVE takes it "
                    + "out of use and deletes nothing. The code never changes. An omitted nationalId keeps the "
                    + "stored document, an empty one removes it and any other replaces it. Needs the "
                    + "permission students:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "students:update")))
    @ApiResponse(responseCode = "200", description = "The student after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body or the identifier is not valid (validation-failed)",
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
            description = "The student does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "Another student has the same document (duplicate-national-id)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateStudentRequest request) {
        return toResponse(students.update(RequestContext.actor(), RequestContext.requestId(), id,
                request.firstName(), request.lastName(), request.birthDate(), request.sex(), request.status(),
                request.nationalId()));
    }

    private static StudentResponse toResponse(StudentView view) {
        return new StudentResponse(view.id(), view.studentCode(), view.firstName(), view.lastName(),
                view.birthDate(), view.sex(), view.status(), view.hasNationalId(),
                view.createdAt(), view.updatedAt());
    }
}

package com.confia.guardians.web;

import com.confia.guardians.application.GuardianView;
import com.confia.guardians.application.LinkedStudentView;
import com.confia.guardians.application.ManageGuardians;
import com.confia.guardians.application.Page;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
 * {@code GET}, {@code POST} and {@code PUT} of {@code /api/v1/guardians} (docs/cortes/guardians-registry.md). The
 * permission each route needs is decided before the controller runs, by the permission decider over {@code
 * PermissionedEndpoints}: {@code guardians:read} for the list and the detail, {@code guardians:create} for the
 * creation and {@code guardians:update} for the edit. The institution is the one of the token, never a member of the
 * request, and nothing is deleted: a guardian is made inactive with {@code PUT}. Every route is rate limited twice:
 * per client address (reads {@code admin-read}, 300 a minute; writes {@code admin-write}, 60 a minute) and per
 * authenticated account (reads {@code admin-read-account}, 600 a minute; writes {@code admin-write-account}, 120 a
 * minute). The national identity document and the RTN are never part of an answer: only whether there is one of each.
 * The approved answers are declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/guardians")
public class GuardianController {

    private static final String TAG = "guardians";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageGuardians guardians;

    public GuardianController(ManageGuardians guardians) {
        this.guardians = guardians;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "listGuardians", tags = TAG, summary = "Lists the guardians",
            description = "One page of the guardians of the institution of the token, active and inactive. Needs "
                    + "the permission guardians:read. page counts from 0, size defaults to 50 and is at most 200, "
                    + "sort is a field of lastName, code or status optionally followed by ,asc or ,desc (lastName "
                    + "ascending by default), q looks in the first name, the last name and the code (2 to 100 "
                    + "characters) and status keeps ACTIVE or INACTIVE guardians.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:read")))
    @ApiResponse(responseCode = "200", description = "One page of the guardians",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GuardianPageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range, the search is shorter than 2 characters or the sort "
                    + "field is not allowed (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold guardians:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public GuardianPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of lastName, code or status, optionally followed by ,asc or ,desc; "
                    + "lastName ascending when absent")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Text to look for in the first name, the last name and the code; 2 to 100 characters")
            @RequestParam(name = "q", required = false) @Size(min = 2, max = 100) String q,
            @Parameter(description = "ACTIVE or INACTIVE; both when absent")
            @RequestParam(name = "status", required = false) @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
        Page<GuardianView> result = guardians.list(RequestContext.actor(), RequestContext.requestId(), page, size,
                sort, q, status);
        return new GuardianPageResponse(result.items().stream().map(GuardianController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "getGuardian", tags = TAG, summary = "Reads a guardian",
            description = "The guardian with this identifier in the institution of the token, with the students "
                    + "it is linked to now (code, names, relationship and percentage). The national identity "
                    + "document and the RTN are never returned: only hasNationalId and hasRtn. Needs the "
                    + "permission guardians:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:read")))
    @ApiResponse(responseCode = "200", description = "The guardian",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GuardianResponse.class)))
    @ApiResponse(responseCode = "400", description = "The identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold guardians:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The guardian does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public GuardianResponse get(@PathVariable("id") UUID id) {
        return toResponse(guardians.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "createGuardian", tags = TAG, summary = "Registers a guardian",
            description = "Registers a guardian; its code (P-000001, P-000002...) is generated and unique in the "
                    + "institution. The national identity document and the RTN, if sent, are stored encrypted and "
                    + "never returned. Needs the permission guardians:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:create")))
    @ApiResponse(responseCode = "201", description = "The guardian created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GuardianResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid (validation-failed)",
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
            description = "Another guardian has the same document (duplicate-national-id)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public GuardianResponse create(@Valid @RequestBody CreateGuardianRequest request) {
        return toResponse(guardians.create(RequestContext.actor(), RequestContext.requestId(),
                request.firstName(), request.lastName(), request.nationalId(), request.rtn(), request.email(),
                request.phone(), request.address(), request.preferredChannel()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "updateGuardian", tags = TAG, summary = "Edits a guardian",
            description = "Replaces the editable members of the guardian, status included; INACTIVE takes it out "
                    + "of use and deletes nothing, and the links it still has stay (the answer lists them). The "
                    + "code never changes. An omitted nationalId or rtn keeps the stored one, an empty one removes "
                    + "it and any other replaces it. Needs the permission guardians:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:update")))
    @ApiResponse(responseCode = "200", description = "The guardian after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GuardianResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body or the identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold guardians:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The guardian does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "Another guardian has the same document (duplicate-national-id)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public GuardianResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateGuardianRequest request) {
        return toResponse(guardians.update(RequestContext.actor(), RequestContext.requestId(), id,
                request.firstName(), request.lastName(), request.nationalId(), request.rtn(), request.email(),
                request.phone(), request.address(), request.preferredChannel(), request.status()));
    }

    private static GuardianResponse toResponse(GuardianView view) {
        List<LinkedStudentResponse> students = view.students() == null ? null
                : view.students().stream().map(GuardianController::toStudentResponse).toList();
        return new GuardianResponse(view.id(), view.guardianCode(), view.firstName(), view.lastName(),
                view.hasNationalId(), view.hasRtn(), view.email(), view.phone(), view.address(),
                view.preferredChannel(), view.status(), view.createdAt(), view.updatedAt(), students);
    }

    private static LinkedStudentResponse toStudentResponse(LinkedStudentView view) {
        return new LinkedStudentResponse(view.linkId(), view.studentId(), view.studentCode(),
                view.studentFirstName(), view.studentLastName(), view.relationship(),
                view.financialResponsibilityPercent(), view.primaryPayer(), view.billingRecipient(),
                view.validFrom());
    }
}

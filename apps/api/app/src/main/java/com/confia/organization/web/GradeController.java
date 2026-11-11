package com.confia.organization.web;

import com.confia.organization.application.ManageGrades;
import com.confia.organization.application.GradeView;
import com.confia.organization.application.Page;
import com.confia.shared.web.openapi.ContractSchemas;
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
 * {@code GET}, {@code POST} and {@code PUT} of {@code /api/v1/organization/grades}
 * (docs/cortes/organization-academic-structure.md). The permission each route needs is decided before
 * the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * organization:read} for the list, {@code organization:create} for the creation and {@code
 * organization:update} for the edit. The institution is the one of the token, never a member of the
 * request, and nothing is deleted: a row is deactivated with {@code PUT}. The approved answers are
 * declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/organization/grades")
public class GradeController {

    private static final String TAG = "organization-grades";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageGrades grades;

    public GradeController(ManageGrades grades) {
        this.grades = grades;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listGrades", tags = TAG, summary = "Lists the grades",
            description = "One page of the grades of the institution of the token, active and "
                    + "inactive. Needs the permission organization:read. page counts from 0, size "
                    + "defaults to 50 and is at most 200, sort is a field of name or ordinal optionally "
                    + "followed by ,asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:read")))
    @ApiResponse(responseCode = "200", description = "One page of the grades",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GradePageResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "A parameter is out of range or the sort field is not allowed (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold organization:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public GradePageResponse list(
            @Parameter(description = "Only the grades of this modality")
            @RequestParam(name = "modalityId", required = false) UUID modalityId,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of name or ordinal, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort) {
        Page<GradeView> result = grades.list(RequestContext.actor(), RequestContext.requestId(),
                modalityId,
                page, size, sort);
        return new GradePageResponse(result.items().stream().map(GradeController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createGrade", tags = TAG, summary = "Creates a grade",
            description = "Creates a grade in an active modality; its name and its ordinal are unique within the modality. Needs the permission organization:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:create")))
    @ApiResponse(responseCode = "201", description = "The grade created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GradeResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold organization:create, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The modality does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The name or the ordinal is taken in the modality (duplicate-name) or the modality is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public GradeResponse create(@Valid @RequestBody CreateGradeRequest request) {
        return toResponse(grades.create(RequestContext.actor(), RequestContext.requestId(),
                request.modalityId(), request.name(), request.ordinal()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateGrade", tags = TAG, summary = "Edits a grade",
            description = "Replaces the editable members of the grade; false in isActive deactivates it and leaves its sections as they are. Needs the permission organization:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:update")))
    @ApiResponse(responseCode = "200", description = "The grade after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GradeResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body or the identifier is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold organization:update, or the Origin is missing or "
                    + "not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The grade does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The name or the ordinal is taken in the modality (duplicate-name)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public GradeResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateGradeRequest request) {
        return toResponse(grades.update(RequestContext.actor(), RequestContext.requestId(), id,
                request.name(), request.ordinal(), request.isActive()));
    }

    private static GradeResponse toResponse(GradeView view) {
        return new GradeResponse(view.id(), view.modalityId(), view.name(), view.ordinal(), view.active(),
                view.createdAt(), view.updatedAt());
    }
}

package com.confia.organization.web;

import com.confia.organization.application.ManageSections;
import com.confia.organization.application.SectionView;
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
 * {@code GET}, {@code POST} and {@code PUT} of {@code /api/v1/organization/sections}
 * (docs/cortes/organization-academic-structure.md). The permission each route needs is decided before
 * the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * organization:read} for the list, {@code organization:create} for the creation and {@code
 * organization:update} for the edit. The institution is the one of the token, never a member of the
 * request, and nothing is deleted: a row is deactivated with {@code PUT}. The approved answers are
 * declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/organization/sections")
public class SectionController {

    private static final String TAG = "organization-sections";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageSections sections;

    public SectionController(ManageSections sections) {
        this.sections = sections;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSections", tags = TAG, summary = "Lists the sections",
            description = "One page of the sections of the institution of the token, active and "
                    + "inactive. Needs the permission organization:read. page counts from 0, size "
                    + "defaults to 50 and is at most 200, sort is a field of name or capacity optionally "
                    + "followed by ,asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:read")))
    @ApiResponse(responseCode = "200", description = "One page of the sections",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SectionPageResponse.class)))
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
    public SectionPageResponse list(
            @Parameter(description = "Only the sections of this academic year")
            @RequestParam(name = "academicYearId", required = false) UUID academicYearId,
            @Parameter(description = "Only the sections of this grade")
            @RequestParam(name = "gradeId", required = false) UUID gradeId,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of name or capacity, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort) {
        Page<SectionView> result = sections.list(RequestContext.actor(), RequestContext.requestId(),
                academicYearId, gradeId,
                page, size, sort);
        return new SectionPageResponse(result.items().stream().map(SectionController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createSection", tags = TAG, summary = "Creates a section",
            description = "Creates a section of a grade in an academic year that is not CLOSED, for an active grade. Needs the permission organization:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:create")))
    @ApiResponse(responseCode = "201", description = "The section created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SectionResponse.class)))
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
            description = "The academic year or the grade does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The name is taken in the year and grade (duplicate-name), the year is CLOSED (academic-year-closed) or the grade is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SectionResponse create(@Valid @RequestBody CreateSectionRequest request) {
        return toResponse(sections.create(RequestContext.actor(), RequestContext.requestId(),
                request.academicYearId(), request.gradeId(), request.name(), request.capacity()));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateSection", tags = TAG, summary = "Edits a section",
            description = "Replaces the editable members of the section; a section of a CLOSED year is not edited. Needs the permission organization:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:update")))
    @ApiResponse(responseCode = "200", description = "The section after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = SectionResponse.class)))
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
            description = "The section does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The name is taken in the year and grade (duplicate-name) or the year is CLOSED (academic-year-closed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public SectionResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateSectionRequest request) {
        return toResponse(sections.update(RequestContext.actor(), RequestContext.requestId(), id,
                request.name(), request.capacity(), request.isActive()));
    }

    private static SectionResponse toResponse(SectionView view) {
        return new SectionResponse(view.id(), view.academicYearId(), view.gradeId(), view.name(), view.capacity(),
                view.active(), view.createdAt(), view.updatedAt());
    }
}

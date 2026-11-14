package com.confia.organization.web;

import com.confia.organization.application.AcademicYearView;
import com.confia.organization.application.ManageAcademicYears;
import com.confia.organization.application.Page;
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
 * {@code GET}, {@code POST} and {@code PUT} of {@code /api/v1/organization/academic-years}
 * (docs/cortes/organization-academic-structure.md). The permission each route needs is decided before
 * the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * organization:read} for the list, {@code organization:create} for the creation and {@code
 * organization:update} for the edit. The institution is the one of the token, never a member of the
 * request, and nothing is deleted: a row is deactivated with {@code PUT}. The approved answers are
 * declared one by one, so the contract snapshot holds exactly these.
 */
@RestController
@RequestMapping("/api/v1/organization/academic-years")
public class AcademicYearController {

    private static final String TAG = "organization-academic-years";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageAcademicYears years;

    public AcademicYearController(ManageAcademicYears years) {
        this.years = years;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listAcademicYears", tags = TAG, summary = "Lists the academic years",
            description = "One page of the academic years of the institution of the token, active and "
                    + "inactive. Needs the permission organization:read. page counts from 0, size "
                    + "defaults to 50 and is at most 200, sort is a field of name, startDate, endDate or status optionally "
                    + "followed by ,asc or ,desc.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:read")))
    @ApiResponse(responseCode = "200", description = "One page of the academic years",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AcademicYearPageResponse.class)))
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
    public AcademicYearPageResponse list(
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size,
            @Parameter(description = "Field of name, startDate, endDate or status, optionally followed by ,asc or ,desc")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort) {
        Page<AcademicYearView> result = years.list(RequestContext.actor(), RequestContext.requestId(),
                page, size, sort);
        return new AcademicYearPageResponse(result.items().stream().map(AcademicYearController::toResponse).toList(),
                result.total(), result.page(), result.size());
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createAcademicYear", tags = TAG, summary = "Creates an academic year",
            description = "Creates a year, PLANNED unless the body says ACTIVE. The range must not share a day with another year of the institution. Needs the permission organization:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:create")))
    @ApiResponse(responseCode = "201", description = "The academic year created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AcademicYearResponse.class)))
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
    @ApiResponse(responseCode = "409", description = "The range overlaps another year (academic-year-overlap), another year is already ACTIVE (academic-year-already-active), the name is taken (duplicate-name) or the year was asked CLOSED (academic-year-closed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AcademicYearResponse create(@Valid @RequestBody CreateAcademicYearRequest request) {
        return toResponse(years.create(RequestContext.actor(), RequestContext.requestId(),
                request.name(), request.startDate(), request.endDate(), request.status()));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateAcademicYear", tags = TAG, summary = "Edits an academic year",
            description = "Replaces the editable members of the year. A CLOSED year is neither edited nor reopened. Needs the permission organization:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "organization:update")))
    @ApiResponse(responseCode = "200", description = "The academic year after the edit",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AcademicYearResponse.class)))
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
            description = "The academic year does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The range overlaps another year (academic-year-overlap), another year is already ACTIVE (academic-year-already-active), the name is taken (duplicate-name) or the year is CLOSED (academic-year-closed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AcademicYearResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateAcademicYearRequest request) {
        return toResponse(years.update(RequestContext.actor(), RequestContext.requestId(), id,
                request.name(), request.startDate(), request.endDate(), request.status(),
                request.isActive()));
    }

    private static AcademicYearResponse toResponse(AcademicYearView view) {
        return new AcademicYearResponse(view.id(), view.name(), view.startDate(), view.endDate(), view.status(), view.active(),
                view.createdAt(), view.updatedAt());
    }
}

package com.confia.scholarships.web;

import com.confia.scholarships.application.ManageScholarships;
import com.confia.scholarships.application.PageRequest;
import com.confia.scholarships.application.RejectedScholarshipFieldException;
import com.confia.scholarships.application.ScholarshipFilter;
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
import java.util.function.Supplier;
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
 * The routes of the scholarships under {@code /api/v1/scholarships} (docs/cortes/scholarships-awards.md). The permission each route
 * needs is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}: {@code
 * scholarships:read} to list and read, and {@code scholarships:create} to create, edit, deactivate and reactivate. The institution is
 * the one of the token, never a member of a request, and nothing is deleted. A scholarship of another institution and an unknown one
 * look the same: 404. Every amount is rounded by the server.
 */
@RestController
@RequestMapping("/api/v1/scholarships")
public class ScholarshipController {

    private static final String TAG = "scholarships";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageScholarships scholarships;

    public ScholarshipController(ManageScholarships scholarships) {
        this.scholarships = scholarships;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listScholarships", tags = TAG, summary = "Lists scholarships",
            description = "One page of the scholarships of the institution of the token, filtered by active and by type of benefit, "
                    + "sorted by code, name or createdAt (sort=field or field,asc|desc; code ascending by default). page counts "
                    + "from 0, size defaults to 50 and is at most 200. Needs the permission scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "One page of scholarships",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ScholarshipPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ScholarshipPageResponse list(
            @Parameter(description = "Only the active (true) or the inactive (false) scholarships")
            @RequestParam(name = "active", required = false) Boolean active,
            @Parameter(description = "Only scholarships of this type of benefit")
            @RequestParam(name = "benefitType", required = false) @Pattern(regexp = "PERCENTAGE|FIXED_AMOUNT") String benefitType,
            @Parameter(description = "code, name or createdAt, with ,asc or ,desc; code,asc when absent")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        PageRequest request = PageRequest.of(page, size, sort, ScholarshipFilter.SORT_FIELDS, "code", true);
        return ScholarshipMapper.toResponse(scholarships.list(RequestContext.actor(), RequestContext.requestId(),
                new ScholarshipFilter(active, benefitType), request));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "createScholarship", tags = TAG, summary = "Creates a scholarship",
            description = "Creates an active scholarship: a unique code, a name, a benefit (a percentage greater than 0 and up to 100, "
                    + "or a fixed amount greater than 0, both with at most two decimals, in HNL), one or more covered payment "
                    + "concepts and an optional cap per period. Needs the permission scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "201", description = "The scholarship created",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScholarshipResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The code is in use (duplicate-scholarship-code), or a covered concept is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ScholarshipResponse create(@Valid @RequestBody CreateScholarshipRequest request) {
        return ScholarshipMapper.toResponse(named(() -> scholarships.create(RequestContext.actor(), RequestContext.requestId(),
                request.code(), new ManageScholarships.Draft(request.name(), request.benefitType(), request.percent(),
                        amountOf(request.fixedAmount()), amountOf(request.periodCap()), request.conceptIds()))));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getScholarship", tags = TAG, summary = "Reads a scholarship",
            description = "One scholarship with its covered concepts and whether its benefit is locked. Needs the permission "
                    + "scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "The scholarship",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScholarshipResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The scholarship does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ScholarshipResponse get(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toResponse(scholarships.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "updateScholarship", tags = TAG, summary = "Edits a scholarship",
            description = "Changes the name, the benefit, the cap and the covered concepts of a scholarship; the code and the active "
                    + "flag stay. The body names the version the editor saw. Once an award of the scholarship was approved the type "
                    + "and the value of its benefit cannot change: another scholarship is created. Needs the permission "
                    + "scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "200", description = "The scholarship edited",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScholarshipResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The scholarship does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The version is not the current one (stale-version), the benefit cannot change because an award was approved "
                    + "(scholarship-benefit-locked), the cap is raised or removed or a concept is added once an award was approved "
                    + "(scholarship-scope-locked), or a newly covered concept is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ScholarshipResponse update(@PathVariable("id") UUID id, @Valid @RequestBody UpdateScholarshipRequest request) {
        return ScholarshipMapper.toResponse(named(() -> scholarships.update(RequestContext.actor(), RequestContext.requestId(), id,
                request.version(), new ManageScholarships.Draft(request.name(), request.benefitType(), request.percent(),
                        amountOf(request.fixedAmount()), amountOf(request.periodCap()), request.conceptIds()))));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/deactivate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "deactivateScholarship", tags = TAG, summary = "Deactivates a scholarship",
            description = "The scholarship takes no new award and stops applying to the charges issued afterwards; nothing already "
                    + "issued changes. Deactivating an inactive scholarship changes nothing. Needs the permission scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "200", description = "The scholarship, inactive",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScholarshipResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The scholarship does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ScholarshipResponse deactivate(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toResponse(scholarships.deactivate(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/reactivate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "reactivateScholarship", tags = TAG, summary = "Reactivates a scholarship",
            description = "The scholarship takes new awards again and applies to the charges issued afterwards. Reactivating an "
                    + "active scholarship changes nothing. Needs the permission scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "200", description = "The scholarship, active",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ScholarshipResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The scholarship does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ScholarshipResponse reactivate(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toResponse(scholarships.reactivate(RequestContext.actor(), RequestContext.requestId(), id));
    }

    private static String amountOf(ScholarshipMoney money) {
        return money == null ? null : money.amount();
    }

    /** A rule about one field of the body answers validation-failed and names that field, so the screen shows it there. */
    static <T> T named(Supplier<T> useCase) {
        try {
            return useCase.get();
        } catch (RejectedScholarshipFieldException e) {
            throw new ProblemCodeException(ProblemCode.VALIDATION_FAILED, List.of(new FieldViolation(e.field(), e.reason())));
        }
    }
}

package com.confia.scholarships.web;

import com.confia.scholarships.application.AwardFilter;
import com.confia.scholarships.application.DecideAward;
import com.confia.scholarships.application.PageRequest;
import com.confia.scholarships.application.RequestAward;
import com.confia.scholarships.application.ViewAwards;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routes of the awards of the scholarships under {@code /api/v1/scholarship-awards} (docs/cortes/scholarships-awards.md). The
 * permission each route needs is decided before the controller runs, by the permission decider over {@code PermissionedEndpoints}:
 * {@code scholarships:read} to list and read, {@code scholarships:create} to request an award, {@code scholarships:approve} to
 * approve and reject it, and {@code scholarships:void} to revoke an approved one. The institution and the requester are the ones of
 * the token. An award of another institution and an unknown one look the same: 404. Whoever requested an award cannot approve nor
 * reject it. Approving, rejecting and revoking carry an {@code Idempotency-Key}, stored permanently on the decision.
 */
@RestController
@RequestMapping("/api/v1/scholarship-awards")
public class ScholarshipAwardController {

    private static final String TAG = "scholarship-awards";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;
    private static final String KEY_DESCRIPTION = "1 to 128 visible characters, unique per request";

    private final RequestAward requestAward;
    private final DecideAward decideAward;
    private final ViewAwards viewAwards;

    public ScholarshipAwardController(RequestAward requestAward, DecideAward decideAward, ViewAwards viewAwards) {
        this.requestAward = requestAward;
        this.decideAward = decideAward;
        this.viewAwards = viewAwards;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listScholarshipAwards", tags = TAG, summary = "Lists scholarship awards",
            description = "One page of awards, filtered by student, status (pending, approved, rejected or revoked), scholarship and "
                    + "academic year, sorted by requestedAt, validFrom or validTo (sort=field or field,asc|desc; requestedAt descending "
                    + "by default). The rows carry no decisions. page counts from 0, size defaults to 50 and is at most 200. Needs the "
                    + "permission scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "One page of awards",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AwardPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AwardPageResponse list(
            @Parameter(description = "Only the awards of this student")
            @RequestParam(name = "studentId", required = false) UUID studentId,
            @Parameter(description = "Only awards in this status")
            @RequestParam(name = "status", required = false) @Pattern(regexp = "pending|approved|rejected|revoked") String status,
            @Parameter(description = "Only the awards of this scholarship")
            @RequestParam(name = "scholarshipId", required = false) UUID scholarshipId,
            @Parameter(description = "Only the awards of this academic year")
            @RequestParam(name = "academicYearId", required = false) UUID academicYearId,
            @Parameter(description = "requestedAt, validFrom or validTo, with ,asc or ,desc; requestedAt,desc when absent")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        PageRequest request = PageRequest.of(page, size, sort, AwardFilter.SORT_FIELDS, "requestedAt", false);
        return ScholarshipMapper.toAwardResponse(viewAwards.list(RequestContext.actor(), RequestContext.requestId(),
                new AwardFilter(studentId, status, scholarshipId, academicYearId), request));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "requestScholarshipAward", tags = TAG, summary = "Requests the award of a scholarship",
            description = "Requests the award of an active scholarship to a student for an academic year, with a validity inside that "
                    + "year. It is born pending and changes no charge until another person approves it. Needs the permission "
                    + "scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "201", description = "The award requested, pending",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AwardResponse.class)))
    @ApiResponse(responseCode = "400", description = "The body is not valid or the validity is not inside the year (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The scholarship, the student or the academic year does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The scholarship is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AwardResponse request(@Valid @RequestBody RequestAwardRequest request) {
        return ScholarshipMapper.toAwardResponse(ScholarshipController.named(() -> requestAward.request(RequestContext.actor(),
                RequestContext.requestId(), new RequestAward.Command(request.scholarshipId(), request.studentId(),
                        request.academicYearId(), request.validFrom(), request.validTo()))));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getScholarshipAward", tags = TAG, summary = "Reads an award with its decisions",
            description = "One award with the scholarship, the student (by code) and the decisions on it. Needs the permission "
                    + "scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "The award",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AwardResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The award does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AwardResponse get(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toAwardResponse(viewAwards.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "approveScholarshipAward", tags = TAG, summary = "Approves a pending award",
            description = "Approves a pending award of an active scholarship. From then on the accrual engine applies it to the charges "
                    + "issued within its validity; the charges already issued do not change. Whoever requested the award cannot "
                    + "approve it. A student has at most one approved award of a scholarship on any day. The Idempotency-Key header is "
                    + "required and stored permanently on the decision. Needs the permission scholarships:approve.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:approve")))
    @ApiResponse(responseCode = "200", description = "The approved award (or the award the key already approved)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AwardResponse.class)))
    @ApiResponse(responseCode = "400", description = "The Idempotency-Key is not valid (idempotency-key-missing, validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:approve, the Origin is missing or not allowed (forbidden, csrf-invalid), "
                    + "or the actor requested the award (self-approval-forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The award does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The award is not pending (invalid-award-state), its scholarship is inactive (inactive-reference), or the "
                    + "student has another approved award of the scholarship in those days (scholarship-award-overlap)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AwardResponse approve(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ScholarshipMapper.toAwardResponse(decideAward.approve(RequestContext.actor(), RequestContext.requestId(),
                idempotencyKey, id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rejectScholarshipAward", tags = TAG, summary = "Rejects a pending award",
            description = "Rejects a pending award with a reason of 20 to 500 characters, kept only in the decision. Whoever requested "
                    + "the award cannot reject it. The Idempotency-Key header is required and stored permanently on the decision. "
                    + "Needs the permission scholarships:approve.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:approve")))
    @ApiResponse(responseCode = "200", description = "The rejected award (or the award the key already rejected)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AwardResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:approve, the Origin is missing or not allowed (forbidden, csrf-invalid), "
                    + "or the actor requested the award (self-approval-forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The award does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The award is not pending (invalid-award-state)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AwardResponse reject(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DecisionReasonRequest request) {
        return ScholarshipMapper.toAwardResponse(ScholarshipController.named(() -> decideAward.reject(RequestContext.actor(),
                RequestContext.requestId(), idempotencyKey, id, request.reason())));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revokeScholarshipAward", tags = TAG, summary = "Revokes an approved award",
            description = "Revokes an approved award with a reason of 20 to 500 characters, kept only in the decision. It affects only "
                    + "the charges issued afterwards; the ones already issued do not change. The Idempotency-Key header is required "
                    + "and stored permanently on the decision. Needs the permission scholarships:void.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:void")))
    @ApiResponse(responseCode = "200", description = "The revoked award (or the award the key already revoked)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AwardResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:void, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The award does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The award is not approved (invalid-award-state)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AwardResponse revoke(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DecisionReasonRequest request) {
        return ScholarshipMapper.toAwardResponse(ScholarshipController.named(() -> decideAward.revoke(RequestContext.actor(),
                RequestContext.requestId(), idempotencyKey, id, request.reason())));
    }
}

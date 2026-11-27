package com.confia.scholarships.web;

import com.confia.scholarships.application.AssignmentFilter;
import com.confia.scholarships.application.DecideDiscountAssignment;
import com.confia.scholarships.application.PageRequest;
import com.confia.scholarships.application.RequestDiscountAssignment;
import com.confia.scholarships.application.ViewDiscountAssignments;
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
 * The routes of the assignments of agreement discounts to students under {@code /api/v1/scholarships/discount-assignments}
 * (docs/cortes/scholarships-discounts.md). The permission each route needs is decided before the controller runs, by the permission
 * decider over {@code PermissionedEndpoints}: {@code scholarships:read} to list and read, {@code scholarships:create} to request an
 * assignment, {@code scholarships:approve} to approve and reject it, and {@code scholarships:void} to revoke an approved one. The
 * institution and the requester are the ones of the token. An assignment of another institution and an unknown one look the same:
 * 404. Whoever requested an assignment cannot approve nor reject it. Approving, rejecting and revoking carry an {@code
 * Idempotency-Key}, stored permanently on the decision.
 */
@RestController
@RequestMapping("/api/v1/scholarships/discount-assignments")
public class DiscountAssignmentController {

    private static final String TAG = "discount-assignments";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF = "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;
    private static final String KEY_DESCRIPTION = "1 to 128 visible characters, unique per request";

    private final RequestDiscountAssignment requestAssignment;
    private final DecideDiscountAssignment decideAssignment;
    private final ViewDiscountAssignments viewAssignments;

    public DiscountAssignmentController(RequestDiscountAssignment requestAssignment, DecideDiscountAssignment decideAssignment,
            ViewDiscountAssignments viewAssignments) {
        this.requestAssignment = requestAssignment;
        this.decideAssignment = decideAssignment;
        this.viewAssignments = viewAssignments;
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listDiscountAssignments", tags = TAG, summary = "Lists discount assignments",
            description = "One page of assignments, filtered by student, status (pending, approved, rejected or revoked) and discount, "
                    + "sorted by requestedAt, validFrom or validTo (sort=field or field,asc|desc; requestedAt descending by default). "
                    + "The rows carry no decisions. page counts from 0, size defaults to 50 and is at most 200. Needs the permission "
                    + "scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "One page of assignments",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AssignmentPageResponse.class)))
    @ApiResponse(responseCode = "400", description = "A parameter is out of range (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AssignmentPageResponse list(
            @Parameter(description = "Only the assignments of this student")
            @RequestParam(name = "studentId", required = false) UUID studentId,
            @Parameter(description = "Only assignments in this status")
            @RequestParam(name = "status", required = false) @Pattern(regexp = "pending|approved|rejected|revoked") String status,
            @Parameter(description = "Only the assignments of this discount")
            @RequestParam(name = "discountId", required = false) UUID discountId,
            @Parameter(description = "requestedAt, validFrom or validTo, with ,asc or ,desc; requestedAt,desc when absent")
            @RequestParam(name = "sort", required = false) @Size(max = 40) String sort,
            @Parameter(description = "Page, counted from 0")
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Rows per page, 1 to 200; 50 when absent")
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        PageRequest request = PageRequest.of(page, size, sort, AssignmentFilter.SORT_FIELDS, "requestedAt", false);
        return ScholarshipMapper.toAssignmentResponse(viewAssignments.list(RequestContext.actor(), RequestContext.requestId(),
                new AssignmentFilter(studentId, status, discountId), request));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "requestDiscountAssignment", tags = TAG, summary = "Requests the assignment of a discount",
            description = "Requests the assignment of an active agreement discount to a student, with a validity inside the one of the "
                    + "discount. It is born pending and changes no charge until another person approves it. Needs the permission "
                    + "scholarships:create.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:create")))
    @ApiResponse(responseCode = "201", description = "The assignment requested, pending",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AssignmentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid, the discount is not an agreement one, or the validity is not inside the one of the "
                    + "discount (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:create, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404",
            description = "The discount or the student does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The discount is inactive (inactive-reference)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AssignmentResponse request(@Valid @RequestBody RequestDiscountAssignmentRequest request) {
        return ScholarshipMapper.toAssignmentResponse(ScholarshipController.named(() -> requestAssignment.request(
                RequestContext.actor(), RequestContext.requestId(), new RequestDiscountAssignment.Command(request.discountId(),
                        request.studentId(), request.validFrom(), request.validTo()))));
    }

    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getDiscountAssignment", tags = TAG, summary = "Reads a discount assignment with its decisions",
            description = "One assignment with the discount, the student (by code) and the decisions on it. Needs the permission "
                    + "scholarships:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:read")))
    @ApiResponse(responseCode = "200", description = "The assignment",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AssignmentResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403", description = "The account does not hold scholarships:read (forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The assignment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AssignmentResponse get(@PathVariable("id") UUID id) {
        return ScholarshipMapper.toAssignmentResponse(viewAssignments.get(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "approveDiscountAssignment", tags = TAG, summary = "Approves a pending assignment",
            description = "Approves a pending assignment of an active discount. From then on the accrual engine applies the discount to "
                    + "the student's charges issued within its validity; the charges already issued do not change. Whoever requested "
                    + "the assignment cannot approve it. A student has at most one approved assignment of a discount on any day. The "
                    + "Idempotency-Key header is required and stored permanently on the decision. Needs the permission "
                    + "scholarships:approve.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:approve")))
    @ApiResponse(responseCode = "200", description = "The approved assignment (or the assignment the key already approved)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AssignmentResponse.class)))
    @ApiResponse(responseCode = "400", description = "The Idempotency-Key is not valid (idempotency-key-missing, validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:approve, the Origin is missing or not allowed (forbidden, csrf-invalid), "
                    + "or the actor requested the assignment (self-approval-forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The assignment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The assignment is not pending (invalid-discount-assignment-state), its discount is inactive "
                    + "(inactive-reference), or the student has another approved assignment of the discount in those days "
                    + "(discount-assignment-overlap)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AssignmentResponse approve(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return ScholarshipMapper.toAssignmentResponse(decideAssignment.approve(RequestContext.actor(),
                RequestContext.requestId(), idempotencyKey, id));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "rejectDiscountAssignment", tags = TAG, summary = "Rejects a pending assignment",
            description = "Rejects a pending assignment with a reason of 20 to 500 characters, kept only in the decision. Whoever "
                    + "requested the assignment cannot reject it. The Idempotency-Key header is required and stored permanently on "
                    + "the decision. Needs the permission scholarships:approve.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:approve")))
    @ApiResponse(responseCode = "200", description = "The rejected assignment (or the assignment the key already rejected)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AssignmentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:approve, the Origin is missing or not allowed (forbidden, csrf-invalid), "
                    + "or the actor requested the assignment (self-approval-forbidden)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The assignment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The assignment is not pending (invalid-discount-assignment-state)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AssignmentResponse reject(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DecisionReasonRequest request) {
        return ScholarshipMapper.toAssignmentResponse(ScholarshipController.named(() -> decideAssignment.reject(
                RequestContext.actor(), RequestContext.requestId(), idempotencyKey, id, request.reason())));
    }

    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @PostMapping(path = "/{id}/revoke", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "revokeDiscountAssignment", tags = TAG, summary = "Revokes an approved assignment",
            description = "Revokes an approved assignment with a reason of 20 to 500 characters, kept only in the decision. It affects "
                    + "only the charges issued afterwards; the ones already issued do not change. The Idempotency-Key header is "
                    + "required and stored permanently on the decision. Needs the permission scholarships:void.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties = @ExtensionProperty(name = "x-required-permission", value = "scholarships:void")))
    @ApiResponse(responseCode = "200", description = "The revoked assignment (or the assignment the key already revoked)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AssignmentResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the Idempotency-Key is not valid (validation-failed, idempotency-key-missing)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be accepted (token-expired, token-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The account does not hold scholarships:void, or the Origin is missing or not allowed (forbidden, csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "404", description = "The assignment does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409", description = "The assignment is not approved (invalid-discount-assignment-state)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "422", description = "The Idempotency-Key was used for another request (idempotency-payload-mismatch)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AssignmentResponse revoke(@PathVariable("id") UUID id,
            @Parameter(required = true, description = KEY_DESCRIPTION)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody DecisionReasonRequest request) {
        return ScholarshipMapper.toAssignmentResponse(ScholarshipController.named(() -> decideAssignment.revoke(
                RequestContext.actor(), RequestContext.requestId(), idempotencyKey, id, request.reason())));
    }
}

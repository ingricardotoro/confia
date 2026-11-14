package com.confia.guardians.web;

import com.confia.guardians.application.LinkRequest;
import com.confia.guardians.application.ManageGuardianLinks;
import com.confia.guardians.application.RejectedSplitException;
import com.confia.guardians.application.StudentGuardianView;
import com.confia.guardians.application.StudentGuardiansView;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.problem.FieldViolation;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemCodeException;
import com.confia.shared.web.ratelimit.RateLimitKey;
import com.confia.shared.web.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET} and {@code PUT} of {@code /api/v1/students/{studentId}/guardians} (docs/cortes/guardians-registry.md):
 * the current guardians of a student with their exact total, and the replacement of the whole set (the only way to
 * change them, so INV-32 holds at every commit). The permission each route needs is decided before the controller
 * runs: {@code guardians:read} for the read and {@code guardians:update} for the replacement. The institution is the
 * one of the token. Every route is rate limited per client address and per authenticated account, like the other
 * administrative routes. A set that breaks a rule is answered {@code invalid-responsibility-split} with the field and
 * the rule in {@code errors}.
 */
@RestController
@RequestMapping("/api/v1/students/{studentId}/guardians")
public class StudentGuardiansController {

    private static final String TAG = "guardians";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageGuardianLinks links;

    public StudentGuardiansController(ManageGuardianLinks links) {
        this.links = links;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "getStudentGuardians", tags = TAG, summary = "Reads the guardians of a student",
            description = "The current links of the student (guardian code, names and status, relationship, "
                    + "percentage, primary payer, billing recipient and the day each started) with their exact "
                    + "total: 100.00 when the student is assigned, 0.00 when it has no guardian yet. Needs the "
                    + "permission guardians:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:read")))
    @ApiResponse(responseCode = "200", description = "The current guardians of the student",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentGuardiansResponse.class)))
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
            description = "The student does not exist in the institution of the token (resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentGuardiansResponse get(@PathVariable("studentId") UUID studentId) {
        return toResponse(links.of(RequestContext.actor(), RequestContext.requestId(), studentId));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "replaceStudentGuardians", tags = TAG, summary = "Replaces the guardians of a student",
            description = "Replaces the complete set of the guardians of the student (INV-32). The percentages "
                    + "add up to exactly 100.00, at most one link is the primary payer, at most one is the "
                    + "billing recipient, no guardian appears twice and every guardian is ACTIVE; an empty list "
                    + "leaves the student without guardians. A link that is absent or whose percentage, "
                    + "relationship or flags differ is closed today (the history stays) and a new one opens; an "
                    + "identical link is untouched, and a set that changes nothing changes nothing. The student "
                    + "must be ACTIVE. Needs the permission guardians:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:update")))
    @ApiResponse(responseCode = "200", description = "The guardians of the student after the replacement",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentGuardiansResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid (validation-failed), or the set breaks INV-32 or its companion "
                    + "rules; errors names the field links and the reason sum-not-100, multiple-primary-payer, "
                    + "multiple-billing-recipient or duplicate-guardian (invalid-responsibility-split)",
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
            description = "The student or a guardian does not exist in the institution of the token "
                    + "(resource-not-found)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The student is not active (student-not-active) or a guardian of the set is not "
                    + "active (guardian-not-active)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public StudentGuardiansResponse replace(@PathVariable("studentId") UUID studentId,
            @Valid @RequestBody ReplaceStudentGuardiansRequest request) {
        List<LinkRequest> requested = request.links().stream()
                .map(link -> new LinkRequest(link.guardianId(), link.relationship(),
                        link.financialResponsibilityPercent(), link.isPrimaryPayer(), link.isBillingRecipient()))
                .toList();
        try {
            return toResponse(links.replace(RequestContext.actor(), RequestContext.requestId(), studentId, requested));
        } catch (RejectedSplitException e) {
            // The field and the keyword of the rule, never a value of the request.
            throw new ProblemCodeException(ProblemCode.INVALID_RESPONSIBILITY_SPLIT,
                    List.of(new FieldViolation(e.field(), e.reason())));
        }
    }

    private static StudentGuardiansResponse toResponse(StudentGuardiansView view) {
        return new StudentGuardiansResponse(view.studentId(), view.studentCode(), view.totalPercent(),
                view.links().stream().map(StudentGuardiansController::toLinkResponse).toList());
    }

    private static StudentGuardianResponse toLinkResponse(StudentGuardianView view) {
        return new StudentGuardianResponse(view.linkId(), view.guardianId(), view.guardianCode(),
                view.guardianFirstName(), view.guardianLastName(), view.guardianStatus(), view.relationship(),
                view.financialResponsibilityPercent(), view.primaryPayer(), view.billingRecipient(),
                view.validFrom());
    }
}

package com.confia.guardians.web;

import com.confia.guardians.application.ConsentChangeView;
import com.confia.guardians.application.GuardianConsentsView;
import com.confia.guardians.application.ManageGuardianConsents;
import com.confia.guardians.application.RejectedConsentException;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET} and {@code POST} of {@code /api/v1/guardians/{id}/consents} (docs/cortes/guardians-consent.md): the
 * consent of a guardian to receive communications, by channel and by purpose, and the recording of a grant or a
 * withdrawal with its evidence. The permission each route needs is decided before the controller runs, by the
 * permission decider over {@code PermissionedEndpoints}: {@code guardians:read} for the read and {@code
 * guardians:update} for the change. The institution and the actor are the ones of the token; the time of the event is
 * the server's. Both routes are rate limited per client address and per authenticated account like the other
 * administrative routes, and they take no {@code Idempotency-Key} (they move no money; repeating the current action is
 * itself idempotent). A rule of the request is answered {@code validation-failed} with the field in {@code errors}.
 * The answers carry no e-mail, phone or name.
 */
@RestController
@RequestMapping("/api/v1/guardians/{id}/consents")
public class GuardianConsentController {

    private static final String TAG = "guardians";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final ManageGuardianConsents consents;

    public GuardianConsentController(ManageGuardianConsents consents) {
        this.consents = consents;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-read")
    @RateLimited(policy = "admin-read-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "getGuardianConsents", tags = TAG, summary = "Reads the consents of a guardian",
            description = "The standing of the six combinations of channel (EMAIL, SMS, WHATSAPP) and purpose "
                    + "(INSTITUTIONAL, MARKETING) of the guardian, derived from the latest event of each: GRANTED, "
                    + "WITHDRAWN or NEVER_GRANTED (nothing is pre-marked), with the instant, the policy version and "
                    + "the evidence; and the history of events, the newest first, at most 100. Needs the "
                    + "permission guardians:read.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:read")))
    @ApiResponse(responseCode = "200", description = "The consents of the guardian",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = GuardianConsentsResponse.class)))
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
    public GuardianConsentsResponse get(@PathVariable("id") UUID id) {
        return toResponse(consents.of(RequestContext.actor(), RequestContext.requestId(), id));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = "admin-write")
    @RateLimited(policy = "admin-write-account", key = RateLimitKey.ACCOUNT)
    @Operation(operationId = "recordGuardianConsent", tags = TAG, summary = "Records a consent or a withdrawal",
            description = "Records, on behalf of the guardian, that it grants or withdraws its consent for a channel "
                    + "and a purpose, with the evidence (SIGNED_PAPER_FORM or ADMIN_REGISTERED) and, to grant, the "
                    + "version of the privacy policy. The transactional purpose is not consentable. A grant needs an "
                    + "ACTIVE guardian with the contact data of the channel (an e-mail for EMAIL, a phone for SMS and "
                    + "WHATSAPP); a withdrawal is always allowed and immediate. Repeating the action that already is "
                    + "the current state writes no event and answers the current standing with changed false. The "
                    + "time of the event is the server's. Needs the permission guardians:update.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH),
            extensions = @Extension(properties =
                    @ExtensionProperty(name = "x-required-permission", value = "guardians:update")))
    @ApiResponse(responseCode = "200", description = "The standing of the combination after the change",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ConsentChangeResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body or the identifier is not valid; errors names the field (validation-failed)",
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
            description = "A grant for a guardian that is not active (guardian-not-active), or for a channel the "
                    + "guardian has no contact data for (consent-channel-unavailable)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address or account (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public ConsentChangeResponse record(@PathVariable("id") UUID id, @Valid @RequestBody RecordConsentRequest request) {
        try {
            ConsentChangeView change = consents.record(RequestContext.actor(), RequestContext.requestId(), id,
                    request.channel(), request.purpose(), request.action(), request.evidenceSource(),
                    request.policyVersion());
            GuardianConsentsView.Standing standing = change.standing();
            return new ConsentChangeResponse(standing.channel(), standing.purpose(), standing.state(),
                    standing.since(), standing.policyVersion(), standing.evidenceSource(), change.changed());
        } catch (RejectedConsentException e) {
            // The field and the keyword of the rule, never a value of the request.
            throw new ProblemCodeException(ProblemCode.VALIDATION_FAILED,
                    List.of(new FieldViolation(e.field(), e.reason())));
        }
    }

    private static GuardianConsentsResponse toResponse(GuardianConsentsView view) {
        return new GuardianConsentsResponse(view.guardianId(),
                view.states().stream().map(state -> new ConsentStandingResponse(state.channel(), state.purpose(),
                        state.state(), state.since(), state.policyVersion(), state.evidenceSource())).toList(),
                view.history().stream().map(event -> new ConsentEventResponse(event.id(), event.channel(),
                        event.purpose(), event.action(), event.evidenceSource(), event.policyVersion(),
                        event.occurredAt())).toList());
    }
}

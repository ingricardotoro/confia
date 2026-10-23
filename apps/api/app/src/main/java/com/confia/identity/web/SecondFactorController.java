package com.confia.identity.web;

import com.confia.identity.application.CompleteSecondFactor;
import com.confia.identity.application.CompleteSecondFactorCommand;
import com.confia.identity.application.CompleteSecondFactorDecision;
import com.confia.identity.application.EnrollTotpSecondFactor;
import com.confia.identity.application.EnrollTotpSecondFactorResult;
import com.confia.identity.application.SecondFactorOutcome;
import com.confia.shared.security.PendingSecondFactor;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.delay.Delayed;
import com.confia.shared.web.delay.RequiredDelayMaterializer;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemCodeException;
import com.confia.shared.web.ratelimit.RateLimitFailures;
import com.confia.shared.web.ratelimit.RateLimited;
import com.confia.shared.web.session.SessionCookies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/mfa/enrollments} and {@code POST /api/v1/auth/mfa/verifications}: the two
 * routes of the restricted class (session-endpoints-cookie-and-csrf design.md, decisions 6 and 13).
 * Both take a restricted second-factor token and nothing else: the authentication filter verified it
 * and bound the {@link PendingSecondFactor} this controller reads, and bound no session actor. What a
 * token of each purpose may do is decided in the use case, from the state of the credential, and the
 * answer says nothing about why a proof was refused.
 *
 * <p><b>Neither can be reached without the limiter.</b> Both handlers declare {@code @RateLimited(policy
 * = "admin-mfa")}, so the interceptor of the edge answers {@code 429} before the body is read and before
 * any transaction opens, and {@code SessionRoutesRateLimitedRuleTest} fails the build for a handler of
 * this package that declares none. A refused proof is counted as a failure of the address for the
 * second layer of the limiter, inside the supplier of the delay materializer, after the commit and
 * before the wait, so it counts even if the client leaves during the wait. An enrollment counts
 * nothing: it proves nothing.
 *
 * <p>The verification answers like the login does: a session with its two cookies, or {@code
 * second-factor-invalid}, the same for a wrong code, a used one and a proof the purpose does not
 * accept. The enrollment answers {@code 201} with the secret and the ten recovery codes, shown once,
 * and neither opens a session nor writes a cookie. Neither route reads the session cookie or asks for
 * the CSRF token: they carry a bearer token, the allowed {@code Origin} is still required, and the JSON
 * content type is required where there is a body. The approved answers are declared one by one with
 * {@code @ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class SecondFactorController {

    static final String POLICY = "admin-mfa";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;
    private static final String CHALLENGE = "The Bearer challenge";

    private final CompleteSecondFactor complete;
    private final EnrollTotpSecondFactor enrollment;
    private final RequiredDelayMaterializer delays;
    private final RateLimitFailures failures;
    private final SessionCookies cookies;

    public SecondFactorController(CompleteSecondFactor complete, EnrollTotpSecondFactor enroll,
            RequiredDelayMaterializer delays, RateLimitFailures failures, SessionCookies cookies) {
        this.complete = complete;
        this.enrollment = enroll;
        this.delays = delays;
        this.failures = failures;
        this.cookies = cookies;
    }

    @PostMapping(path = "/verifications", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = POLICY)
    @Operation(operationId = "completeSecondFactor", tags = "auth-mfa",
            summary = "Verifies a second-factor proof and starts the session",
            description = "Takes the restricted token of the login (mfa-verify, or mfa-enroll for "
                    + "the first verification) and exactly one of a TOTP code or a recovery code. "
                    + "The first verification of a credential accepts only a TOTP code, activates "
                    + "the credential and ends the other sessions of the account. Answers with a "
                    + "session and the two cookies, or with second-factor-invalid, which does not "
                    + "say why the proof was refused.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "A session was started",
            headers = @Header(name = "Set-Cookie", schema = @Schema(type = "string"),
                    description = "The session cookie and the CSRF cookie, two headers"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AuthenticatedSessionResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid, or does not carry exactly one proof "
                    + "(validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), a restricted token that is "
                    + "expired (token-expired) or not accepted by this route (token-invalid), or a "
                    + "proof that was not accepted (second-factor-invalid)",
            headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string"),
                    description = CHALLENGE),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The Origin is missing or not allowed (csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "415",
            description = "The content type is not JSON (unsupported-media-type)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "503",
            description = "No capacity to count the client or to hold one more waiting request "
                    + "(capacity-exceeded)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AuthenticatedSessionResponse verify(@Valid @RequestBody SecondFactorVerificationRequest request,
            HttpServletResponse response) {
        PendingSecondFactor pending = pendingOfTheRequest();
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));
        CompleteSecondFactorCommand command = new CompleteSecondFactorCommand(pending.accountId(),
                pending.institutionId(), pending.purpose(), presentOrNull(request.code()),
                presentOrNull(request.recoveryCode()), origin.clientAddress(), origin.userAgent(),
                origin.requestId().toString());

        SecondFactorOutcome outcome = delays.execute(() -> {
            CompleteSecondFactorDecision decision = complete.execute(command);
            if (decision.outcome() instanceof SecondFactorOutcome.Rejected) {
                failures.record(POLICY);
            }
            return new Delayed<>(decision.outcome(), decision.requiredDelay());
        });

        return switch (outcome) {
            case SecondFactorOutcome.Started started -> {
                cookies.issue(response, started.session().refreshToken());
                yield AuthenticatedSessionResponse.of(started.session());
            }
            case SecondFactorOutcome.Rejected rejected ->
                    throw new ProblemCodeException(ProblemCode.SECOND_FACTOR_INVALID);
        };
    }

    @PostMapping(path = "/enrollments", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = POLICY)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "enrollSecondFactor", tags = "auth-mfa",
            summary = "Enrolls the TOTP second factor, or replaces the secret of one never verified",
            description = "Takes the restricted token of the login for the enrollment (mfa-enroll) "
                    + "and no body. Creates the credential, or replaces the secret of a credential "
                    + "that was never verified and retires its recovery codes, and answers with the "
                    + "secret in base32 and ten recovery codes, shown once. A verified credential is "
                    + "never replaced. The enrollment activates nothing and opens no session.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH))
    @ApiResponse(responseCode = "201", description = "The credential was written",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = EnrollmentResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a restricted token that is "
                    + "expired (token-expired) or not accepted by this route (token-invalid)",
            headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string"),
                    description = CHALLENGE),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The Origin is missing or not allowed (csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "409",
            description = "The credential is already verified (mfa-already-enrolled)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "503",
            description = "No capacity to count the client (capacity-exceeded)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public EnrollmentResponse enroll() {
        PendingSecondFactor pending = pendingOfTheRequest();
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));

        EnrollTotpSecondFactorResult result = enrollment.execute(pending, origin.requestId().toString());

        return switch (result) {
            case EnrollTotpSecondFactorResult.Enrolled enrolled -> EnrollmentResponse.of(enrolled);
            case EnrollTotpSecondFactorResult.AlreadyEnrolled already ->
                    throw new ProblemCodeException(ProblemCode.MFA_ALREADY_ENROLLED);
        };
    }

    /** The holder of the restricted token the filter verified; a route of the class is never served without one. */
    private static PendingSecondFactor pendingOfTheRequest() {
        return PendingSecondFactor.current().orElseThrow(() -> new IllegalStateException(
                "no restricted second-factor principal is bound to this request"));
    }

    private static String presentOrNull(String proof) {
        return proof == null || proof.isBlank() ? null : proof;
    }
}

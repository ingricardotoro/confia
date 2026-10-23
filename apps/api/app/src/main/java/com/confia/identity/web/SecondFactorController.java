package com.confia.identity.web;

import com.confia.identity.application.EnrollTotpSecondFactor;
import com.confia.identity.application.EnrollTotpSecondFactorResult;
import com.confia.shared.security.PendingSecondFactor;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemCodeException;
import com.confia.shared.web.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/mfa/enrollments}: a route of the restricted class
 * (session-endpoints-cookie-and-csrf design.md, decisions 6 and 13). It takes a restricted
 * second-factor token of purpose {@code mfa-enroll} and nothing else: the authentication filter
 * verified it and bound the {@link PendingSecondFactor} this controller reads, and bound no session
 * actor. What the enrollment does depends on the state of the credential and is decided in the use
 * case.
 *
 * <p><b>It cannot be reached without the limiter.</b> The handler declares {@code @RateLimited(policy
 * = "admin-mfa")}, so the interceptor of the edge answers {@code 429} before any transaction opens,
 * and {@code SessionRoutesRateLimitedRuleTest} fails the build for a handler of this package that
 * declares none. An enrollment counts nothing as a failure: it proves nothing.
 *
 * <p>It answers {@code 201} with the secret and the ten recovery codes, shown once, and neither
 * opens a session nor writes a cookie. It reads no session cookie and asks for no CSRF token: it
 * carries a bearer token, and the allowed {@code Origin} is still required. The approved answers are
 * declared one by one with {@code @ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
public class SecondFactorController {

    static final String POLICY = "admin-mfa";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;
    private static final String CHALLENGE = "The Bearer challenge";

    private final EnrollTotpSecondFactor enrollment;

    public SecondFactorController(EnrollTotpSecondFactor enrollment) {
        this.enrollment = enrollment;
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
}

package com.confia.identity.web;

import com.confia.identity.application.LogIn;
import com.confia.identity.application.LoginCommand;
import com.confia.identity.application.LoginDecision;
import com.confia.identity.application.LoginOutcome;
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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/sessions}: the login (session-endpoints-cookie-and-csrf design.md
 * decisions 10, 11 and 12; condition H1 of docs/03-seguridad.md section 4.4).
 *
 * <p><b>It cannot be reached without the limiter.</b> The handler declares {@code @RateLimited(policy
 * = "admin-login")}, so the interceptor of the edge answers {@code 429} before the body is read,
 * before the materializer takes a permit, before any transaction opens and before Argon2id runs.
 * {@code SessionRoutesRateLimitedRuleTest} fails the build for a state-changing handler of this
 * package that declares no policy, and {@code LoginRateLimitClosesH1IT} proves the eleventh request
 * of a minute is refused.
 *
 * <p><b>What it does.</b> It builds the command from the body and the origin of the request (the
 * address the request filter resolved and the agent it sanitized), runs the use case inside the
 * delay materializer, which waits for the delay of the backoff after the use case committed, and
 * answers by outcome. A {@code Failed} outcome is counted as a failure of the address for the second
 * layer of the limiter inside the materializer's supplier, after the commit and before the wait, so
 * it counts even if the client leaves during the wait; {@code Started} and {@code ChallengeIssued}
 * count nothing, because the credential was right. Only {@code Started} writes cookies, and it is the
 * first {@code Set-Cookie} of production. A {@code Failed} outcome ends the request with {@code
 * authentication-failed}, the same answer for an account that exists and one that does not.
 *
 * <p>The route is not a cookie route: it neither reads the session cookie nor asks for the CSRF
 * token (the allowed {@code Origin}, the JSON content type and {@code SameSite=Strict} protect it),
 * and a cookie the browser already held is overwritten, not revoked. It takes no {@code
 * Idempotency-Key}. The approved answers are declared one by one with {@code @ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class LoginController {

    static final String POLICY = "admin-login";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final LogIn logIn;
    private final RequiredDelayMaterializer delays;
    private final RateLimitFailures failures;
    private final SessionCookies cookies;

    public LoginController(LogIn logIn, RequiredDelayMaterializer delays, RateLimitFailures failures,
            SessionCookies cookies) {
        this.logIn = logIn;
        this.delays = delays;
        this.failures = failures;
        this.cookies = cookies;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = POLICY)
    @Operation(operationId = "logIn", tags = "auth-sessions",
            summary = "Logs in with an identifier and a password",
            description = "Starts a session (status authenticated, with the session cookie and the "
                    + "CSRF cookie) or, when the account owes a second factor, answers with a "
                    + "restricted token and no cookie (status second-factor-required or "
                    + "second-factor-enrollment-required). Needs no credential, an allowed Origin "
                    + "and a JSON body. The answer for a wrong password is the same for an account "
                    + "that exists and one that does not.")
    @ApiResponse(responseCode = "200",
            description = "A session was started, or a second factor is owed",
            headers = @Header(name = "Set-Cookie", schema = @Schema(type = "string"),
                    description = "Only with status authenticated: the session cookie and the CSRF "
                            + "cookie, two headers"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = LoginResponse.class)))
    @ApiResponse(responseCode = "400",
            description = "The body is not valid (validation-failed)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "401",
            description = "The credentials were not accepted (authentication-failed)",
            headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string"),
                    description = "The Bearer challenge"),
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
    public LoginResponse login(@Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));
        LoginCommand command = new LoginCommand(request.identifier(), request.password(),
                origin.clientAddress(), origin.userAgent(), origin.requestId().toString());

        LoginOutcome outcome = delays.execute(() -> {
            LoginDecision decision = logIn.execute(command);
            if (decision.outcome() instanceof LoginOutcome.Failed) {
                failures.record(POLICY);
            }
            return new Delayed<>(decision.outcome(), decision.requiredDelay());
        });

        return switch (outcome) {
            case LoginOutcome.Started started -> {
                cookies.issue(response, started.session().refreshToken());
                yield AuthenticatedSessionResponse.of(started.session());
            }
            case LoginOutcome.ChallengeIssued issued ->
                    SecondFactorPendingResponse.of(issued.challenge());
            case LoginOutcome.Failed failed ->
                    throw new ProblemCodeException(ProblemCode.AUTHENTICATION_FAILED);
        };
    }
}

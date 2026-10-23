package com.confia.identity.web;

import com.confia.identity.application.RefreshSession;
import com.confia.identity.application.RotateRefreshTokenCommand;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemCodeException;
import com.confia.shared.web.ratelimit.RateLimitFailures;
import com.confia.shared.web.ratelimit.RateLimited;
import com.confia.shared.web.session.SessionCookie;
import com.confia.shared.web.session.SessionCookies;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/auth/sessions/refresh}: the rotation of the refresh token held in the session
 * cookie (session-endpoints-cookie-and-csrf design.md decisions 5, 14 and 18; scenarios I186 to
 * I190, I219, I220, WE63 and WE92).
 *
 * <p><b>It cannot be reached without the limiter nor without the CSRF defence.</b> The handler
 * declares {@code @RateLimited(policy = "admin-session")}, and the route is a cookie route of {@code
 * CsrfOriginFilter}: a request that carries the session cookie needs the double-submit token and an
 * allowed {@code Origin}, and one that carries two session cookies never reaches this class. A
 * request without the cookie needs only the {@code Origin} (there is no ambient credential to
 * protect) and ends as any other rejection.
 *
 * <p><b>What it does.</b> It reads the refresh token from the cookie, runs the rotation and answers
 * by outcome. A rotation, fresh or a retry inside the grace window, writes both cookies again (the
 * same successor on a retry, with a CSRF token bound to it) and answers the session. <b>Every other
 * outcome is one answer</b>: the cookies are deleted, one failure is counted against the address for
 * the second layer of the limiter, and the request ends with {@code session-expired}. The use case
 * hands this class no reason, so it cannot tell an unknown token from a reused one. The deletion
 * survives the exception: headers added before it are kept. The route takes no access token, no
 * body and no {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class SessionRefreshController {

    static final String POLICY = "admin-session";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final RefreshSession refresh;
    private final RateLimitFailures failures;
    private final SessionCookies cookies;

    public SessionRefreshController(RefreshSession refresh, RateLimitFailures failures,
            SessionCookies cookies) {
        this.refresh = refresh;
        this.failures = failures;
        this.cookies = cookies;
    }

    @PostMapping(path = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimited(policy = POLICY)
    @Operation(operationId = "refreshSession", tags = "auth-sessions",
            summary = "Rotates the refresh token of the session cookie",
            description = "Reads the refresh token only from the session cookie. With the cookie it "
                    + "needs the CSRF cookie repeated in the X-CSRF-Token header; without it, only an "
                    + "allowed Origin. Answers with a new access token and writes both cookies again, "
                    + "or, for any token that is not accepted, with session-expired and the deletion "
                    + "of both cookies, without saying why.",
            parameters = {
                @Parameter(name = "__Secure-confia_admin_sid", in = ParameterIn.COOKIE,
                        description = "The session cookie: the refresh token", required = false,
                        schema = @Schema(type = "string")),
                @Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER,
                        description = "The value of the CSRF cookie, required with the session cookie",
                        required = false, schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "200", description = "The session was refreshed",
            headers = @Header(name = "Set-Cookie", schema = @Schema(type = "string"),
                    description = "The session cookie and the CSRF cookie, two headers"),
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AuthenticatedSessionResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "The refresh token was not accepted (session-expired), whatever the "
                    + "reason; both cookies are deleted",
            headers = {
                @Header(name = "Set-Cookie", schema = @Schema(type = "string"),
                        description = "The deletion of the session cookie and the CSRF cookie"),
                @Header(name = "WWW-Authenticate", schema = @Schema(type = "string"),
                        description = "The Bearer challenge")},
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The Origin is missing or not allowed, or the CSRF token does not match "
                    + "the session cookie (csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "503",
            description = "No capacity to count the client (capacity-exceeded)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public AuthenticatedSessionResponse refresh(HttpServletRequest request,
            HttpServletResponse response) {
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));

        if (cookies.read(request) instanceof SessionCookie.Present present) {
            RefreshSession.RefreshResult result = refresh.execute(new RotateRefreshTokenCommand(
                    present.text(), origin.userAgent(), origin.clientAddress(),
                    origin.requestId().toString()));
            if (result instanceof RefreshSession.Refreshed refreshed) {
                cookies.issue(response, refreshed.session().refreshToken());
                return AuthenticatedSessionResponse.of(refreshed.session());
            }
        }
        cookies.clear(response);
        failures.record(POLICY);
        throw new ProblemCodeException(ProblemCode.SESSION_EXPIRED);
    }
}

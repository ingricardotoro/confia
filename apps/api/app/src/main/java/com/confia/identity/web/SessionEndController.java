package com.confia.identity.web;

import com.confia.identity.application.EndAllSessions;
import com.confia.identity.application.EndCurrentSession;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.openapi.ContractSchemas;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code DELETE /api/v1/auth/sessions/current} and {@code DELETE /api/v1/auth/sessions}: the two ways
 * a session ends on purpose (session-endpoints-cookie-and-csrf design.md decision 15; scenarios I194
 * to I197, I199 to I202, I227 and WE20).
 *
 * <p><b>Neither can be reached without the limiter nor without the origin defence.</b> Both handlers
 * declare {@code @RateLimited(policy = "admin-session")}, and every non-safe method of the chain
 * needs an allowed {@code Origin} before anything else runs. The end of the current session is a
 * cookie route of {@code CsrfOriginFilter}: with the session cookie it needs the double-submit CSRF
 * token, and it takes no access token at all, so it works for a client whose access token expired.
 * Ending every session is authenticated with the bearer access token of a live session and, as any
 * route that does not read a cookie, needs no CSRF token.
 *
 * <p><b>The current session always answers {@code 204}</b> and always deletes both cookies: with the
 * cookie of a live family, of a revoked or ended one, of an unknown one, of a text that cannot be a
 * token, and with no cookie at all. The use case writes nothing when there is nothing to close, so
 * the answer does not tell the cases apart. Closings are not failures: the handlers never report to
 * the limiter of failures, so a client that closes many sessions is not restricted. Ending every
 * session revokes the live families of the account of the token, the current one among them.
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class SessionEndController {

    static final String POLICY = "admin-session";

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;
    private static final String CLEARED = "The deletion of the session cookie and the CSRF cookie";

    private final EndCurrentSession endCurrent;
    private final EndAllSessions endAll;
    private final SessionCookies cookies;

    public SessionEndController(EndCurrentSession endCurrent, EndAllSessions endAll,
            SessionCookies cookies) {
        this.endCurrent = endCurrent;
        this.endAll = endAll;
        this.cookies = cookies;
    }

    @DeleteMapping(path = "/current")
    @RateLimited(policy = POLICY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "endCurrentSession", tags = "auth-sessions",
            summary = "Ends the session of the session cookie",
            description = "Reads the refresh token only from the session cookie and revokes its "
                    + "family with the reason logout. Needs no access token. With the session "
                    + "cookie it needs the CSRF cookie repeated in the X-CSRF-Token header; always "
                    + "an allowed Origin. Answers 204 and deletes both cookies whatever the token "
                    + "was, also with no cookie at all.",
            parameters = {
                @Parameter(name = "__Secure-confia_admin_sid", in = ParameterIn.COOKIE,
                        description = "The session cookie: the refresh token", required = false,
                        schema = @Schema(type = "string")),
                @Parameter(name = "X-CSRF-Token", in = ParameterIn.HEADER,
                        description = "The value of the CSRF cookie, required with the session cookie",
                        required = false, schema = @Schema(type = "string"))})
    @ApiResponse(responseCode = "204", description = "The session ended, or there was none to end",
            headers = @Header(name = "Set-Cookie", schema = @Schema(type = "string"),
                    description = CLEARED))
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
    public void endCurrent(HttpServletRequest request, HttpServletResponse response) {
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));
        if (cookies.read(request) instanceof SessionCookie.Present present) {
            endCurrent.execute(present.text(), origin.requestId().toString());
        }
        cookies.clear(response);
    }

    @DeleteMapping
    @RateLimited(policy = POLICY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "endAllSessions", tags = "auth-sessions",
            summary = "Ends every live session of the account of the access token",
            description = "Revokes with the reason logout every live family of the account, the "
                    + "current one included, and writes one entry with their number. Needs the "
                    + "access token of a live session and an allowed Origin, and no CSRF token. "
                    + "Answers 204 and deletes both cookies.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH))
    @ApiResponse(responseCode = "204", description = "Every session of the account ended",
            headers = @Header(name = "Set-Cookie", schema = @Schema(type = "string"),
                    description = CLEARED))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted: expired (token-expired), or altered, restricted, of a revoked or "
                    + "unknown session (token-invalid)",
            headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string"),
                    description = "The Bearer challenge"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "403",
            description = "The Origin is missing or not allowed (csrf-invalid)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "429",
            description = "Too many requests from this address (too-many-requests)",
            headers = @Header(name = "Retry-After", schema = @Schema(type = "integer"),
                    description = "Seconds to wait"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "503",
            description = "No capacity to count the client (capacity-exceeded)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public void endAll(HttpServletResponse response) {
        AuthenticatedActor actor = AuthenticatedActor.current().orElseThrow(
                () -> new IllegalStateException("no authenticated actor is bound to this request"));
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));
        endAll.execute(actor, origin.requestId().toString());
        cookies.clear(response);
    }
}

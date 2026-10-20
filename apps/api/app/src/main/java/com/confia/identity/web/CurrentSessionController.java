package com.confia.identity.web;

import com.confia.identity.application.CurrentSessionView;
import com.confia.identity.application.DescribeCurrentSession;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.web.authentication.AccessTokenRejectedException;
import com.confia.shared.web.openapi.ContractSchemas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/auth/sessions/current}: the session the bearer token belongs to
 * (session-tokens-and-web-layer design.md decision 13), the one production route of this change and
 * the style of resources that {@code session-endpoints-cookie-and-csrf} inherits.
 *
 * <p>The controller never reads a token, a header or a parameter. The authentication filter has
 * verified the token and checked, because the route is declared {@code LIVE_SESSION} in {@code
 * AuthenticatedEndpoints}, that the session is live; what is left here is to take the principal that
 * filter bound, ask the use case and copy its answer into the DTO. The route needs no permission of
 * any role: every authenticated actor may read their own session, so a {@code 403} is not reachable.
 * There is no rate limiter on it either: it is a cheap read, and the limits of the session routes
 * are decided by {@code session-endpoints-cookie-and-csrf}.
 *
 * <p>The approved answers are declared one by one with {@code @ApiResponse}, and {@code
 * springdoc.override-with-generic-response} is {@code false}, so the contract snapshot holds exactly
 * these three and not whatever the global translator could answer (probe S-4).
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class CurrentSessionController {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PROBLEM_DETAIL_REF =
            "#/components/schemas/" + ContractSchemas.PROBLEM_DETAIL;

    private final DescribeCurrentSession describe;

    public CurrentSessionController(DescribeCurrentSession describe) {
        this.describe = describe;
    }

    @GetMapping(path = "/current", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getCurrentSession", tags = "auth-sessions",
            summary = "Reads the session of the bearer token",
            description = "Returns the session the access token belongs to and when each of its "
                    + "three ends comes. Needs an access token of a live session and no "
                    + "permission of any role. Reads only: it does not extend the session.",
            security = @SecurityRequirement(name = ContractSchemas.BEARER_AUTH))
    @ApiResponse(responseCode = "200", description = "The session of the token",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = CurrentSessionResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "No credential (authentication-required), or a credential that cannot be "
                    + "accepted: expired (token-expired), or altered, restricted, of a revoked or "
                    + "unknown session (token-invalid). Always with WWW-Authenticate.",
            headers = @Header(name = "WWW-Authenticate", schema = @Schema(type = "string"),
                    description = "The Bearer challenge; with error=\"invalid_token\" when a "
                            + "credential was presented and rejected"),
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    @ApiResponse(responseCode = "500", description = "Unexpected failure (internal-error)",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(ref = PROBLEM_DETAIL_REF)))
    public CurrentSessionResponse current() {
        AuthenticatedActor actor = AuthenticatedActor.current().orElseThrow(
                () -> new IllegalStateException("no authenticated actor is bound to this request"));
        CurrentSessionView view = describe.execute(actor)
                // The session ended between the filter's check and this read: the credential no
                // longer stands, like any other rejected one.
                .orElseThrow(AccessTokenRejectedException::new);
        return new CurrentSessionResponse(view.sessionId(), view.accountId(), view.institutionId(),
                view.authenticationMethods(), view.accessTokenExpiresAt(), view.sessionExpiresAt(),
                view.idleExpiresAt());
    }
}

package com.confia.shared.web.authentication;

import com.confia.shared.security.SecondFactorPurpose;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;

/**
 * The closed, immutable list of routes of the administrative chain that need an authenticated actor
 * and no permission of any role, parallel to {@code PublicEndpoints} and defined in this one place
 * (session-tokens-and-web-layer design.md, decision 5). A route on neither list is denied, so adding
 * a controller never opens a route by itself. The matrix of roles and permissions replaces this list
 * in change 8.
 *
 * <p>In production the list holds the two routes of the session read and of the end of every
 * session (tasks 5.2 and 7b); the public
 * constructor exists for the test harnesses, which add routes of their own and nothing else.
 */
public final class AuthenticatedEndpoints {

    private final List<AuthenticatedEndpoint> endpoints;
    private final List<SecondFactorEndpoint> secondFactorEndpoints;

    public AuthenticatedEndpoints(List<AuthenticatedEndpoint> endpoints) {
        this(endpoints, List.of());
    }

    public AuthenticatedEndpoints(List<AuthenticatedEndpoint> endpoints,
            List<SecondFactorEndpoint> secondFactorEndpoints) {
        this.endpoints = List.copyOf(endpoints);
        this.secondFactorEndpoints = List.copyOf(secondFactorEndpoints);
    }

    /**
     * The list of the administrative process: the current session and the end of every session, both of
     * which ask for the live-session
     * check (design.md decisions 7 and 13).
     */
    public static AuthenticatedEndpoints forAdmin() {
        return new AuthenticatedEndpoints(List.of(
                new AuthenticatedEndpoint(HttpMethod.GET, "/api/v1/auth/sessions/current",
                        SessionCheck.LIVE_SESSION),
                new AuthenticatedEndpoint(HttpMethod.DELETE, "/api/v1/auth/sessions",
                        SessionCheck.LIVE_SESSION)),
                List.of(new SecondFactorEndpoint(HttpMethod.POST, "/api/v1/auth/mfa/verifications",
                                Set.of(SecondFactorPurpose.VERIFY, SecondFactorPurpose.ENROLL)),
                        new SecondFactorEndpoint(HttpMethod.POST, "/api/v1/auth/mfa/enrollments",
                                Set.of(SecondFactorPurpose.ENROLL))));
    }

    /** The routes, in a list that cannot be modified. */
    public List<AuthenticatedEndpoint> endpoints() {
        return endpoints;
    }

    /**
     * The routes of the restricted class, which take a restricted second-factor token and no other
     * (session-endpoints-cookie-and-csrf design.md, decision 6), in a list that cannot be modified.
     * In production the list is empty until the two second-factor endpoints exist.
     */
    public List<SecondFactorEndpoint> secondFactorEndpoints() {
        return secondFactorEndpoints;
    }
}

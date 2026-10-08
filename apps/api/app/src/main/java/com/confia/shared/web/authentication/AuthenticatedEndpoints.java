package com.confia.shared.web.authentication;

import java.util.List;

/**
 * The closed, immutable list of routes of the administrative chain that need an authenticated actor
 * and no permission of any role, parallel to {@code PublicEndpoints} and defined in this one place
 * (session-tokens-and-web-layer design.md, decision 5). A route on neither list is denied, so adding
 * a controller never opens a route by itself. The matrix of roles and permissions replaces this list
 * in change 8.
 *
 * <p>In production the list is empty until the session endpoint arrives (task 5.2); the public
 * constructor exists for the test harnesses, which add routes of their own and nothing else.
 */
public final class AuthenticatedEndpoints {

    private final List<AuthenticatedEndpoint> endpoints;

    public AuthenticatedEndpoints(List<AuthenticatedEndpoint> endpoints) {
        this.endpoints = List.copyOf(endpoints);
    }

    /** The list of the administrative process: empty until the session endpoint exists. */
    public static AuthenticatedEndpoints forAdmin() {
        return new AuthenticatedEndpoints(List.of());
    }

    /** The routes, in a list that cannot be modified. */
    public List<AuthenticatedEndpoint> endpoints() {
        return endpoints;
    }
}

package com.confia.shared.web.csrf;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * A route whose credential is a cookie the browser sends on its own, and so the only kind of route
 * that needs the double-submit token (session-endpoints-cookie-and-csrf design.md, decision 2). It is
 * matched by method and by exact path. Production lists two, from {@link #forAdmin()}; a test
 * harness adds its own, as it does for {@code AuthenticatedEndpoints}.
 *
 * @param method the HTTP method
 * @param path the exact request path
 */
public record CookieCredentialRoute(HttpMethod method, String path) {

    /** The prefix of the session routes: the single constant the cookie helper will share. */
    public static final String SESSIONS_PREFIX = "/api/v1/auth/sessions";

    public CookieCredentialRoute {
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a route path starts with a slash");
        }
    }

    /** The refresh and the end of the current session. */
    public static List<CookieCredentialRoute> forAdmin() {
        List<CookieCredentialRoute> routes = new ArrayList<>();
        routes.add(new CookieCredentialRoute(HttpMethod.POST, SESSIONS_PREFIX + "/refresh"));
        routes.add(new CookieCredentialRoute(HttpMethod.DELETE, SESSIONS_PREFIX + "/current"));
        return routes;
    }

    boolean matches(HttpServletRequest request) {
        return method.name().equals(request.getMethod()) && path.equals(request.getRequestURI());
    }
}

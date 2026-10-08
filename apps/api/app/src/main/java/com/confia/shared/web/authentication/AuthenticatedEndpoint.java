package com.confia.shared.web.authentication;

import java.util.Objects;
import org.springframework.http.HttpMethod;

/**
 * One route that needs an authenticated actor and nothing more: an HTTP method, a path pattern in
 * Spring's path pattern syntax, and the session check it asks for. A route is on the list only for
 * the method it names, so another method on the same path is denied like any other request.
 *
 * @param method the one HTTP method the entry covers
 * @param pattern the path pattern
 * @param check what the filter verifies about the session before the controller runs
 */
public record AuthenticatedEndpoint(HttpMethod method, String pattern, SessionCheck check) {

    public AuthenticatedEndpoint {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(check, "check");
    }
}

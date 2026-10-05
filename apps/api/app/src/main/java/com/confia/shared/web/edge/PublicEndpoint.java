package com.confia.shared.web.edge;

import org.springframework.http.HttpMethod;

/**
 * One route the security chain lets through without a credential: an HTTP method and a path
 * pattern (web-edge-foundations design.md, decision 5). A route is public only for the method it
 * names, so a different method on the same path is denied like any other request.
 *
 * @param method the one HTTP method that is allowed
 * @param pattern the path pattern, in Spring's path pattern syntax
 */
public record PublicEndpoint(HttpMethod method, String pattern) {
}

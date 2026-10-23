package com.confia.shared.web.authentication;

import com.confia.shared.security.SecondFactorPurpose;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpMethod;

/**
 * One route of the restricted class: an HTTP method, a path pattern in Spring's path pattern syntax,
 * and the purposes of restricted token it admits. The filter decides only the purpose; what a purpose
 * may prove in the use case behind the route is the use case's decision. A route is on the list only
 * for the method it names.
 *
 * @param method the one HTTP method the entry covers
 * @param pattern the path pattern
 * @param purposes the purposes of token the route admits; never empty
 */
public record SecondFactorEndpoint(HttpMethod method, String pattern,
        Set<SecondFactorPurpose> purposes) {

    public SecondFactorEndpoint {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(purposes, "purposes");
        purposes = Set.copyOf(purposes);
        if (purposes.isEmpty()) {
            throw new IllegalArgumentException("a restricted route admits at least one purpose");
        }
    }
}

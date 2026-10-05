package com.confia.shared.web.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers every anonymous request the chain denies with {@code 401 authentication-required}
 * (web-edge-foundations design.md, decision 5). The cause of the denial is never inspected, so a
 * missing credential, a malformed one and a route that does not exist all look the same.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponses problems;

    public ProblemAuthenticationEntryPoint(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        problems.write(request, response, ProblemCode.AUTHENTICATION_REQUIRED);
    }
}

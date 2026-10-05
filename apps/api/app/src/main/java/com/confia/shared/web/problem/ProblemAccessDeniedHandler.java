package com.confia.shared.web.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers an authenticated principal the chain denies with {@code 403 forbidden}
 * (web-edge-foundations design.md, decision 5). No route grants a permission yet, so every
 * authenticated request outside the allow-list ends here.
 */
public final class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponses problems;

    public ProblemAccessDeniedHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        problems.write(request, response, ProblemCode.FORBIDDEN);
    }
}

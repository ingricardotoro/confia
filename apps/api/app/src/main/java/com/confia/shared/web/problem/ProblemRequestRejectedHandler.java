package com.confia.shared.web.problem;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

/**
 * Answers a request the strict firewall rejects ({@code ;}, {@code /.}, {@code /..}, encoded
 * separators) with {@code 400 validation-failed} and no violation list (web-edge-foundations
 * design.md, decision 5). The rejection reason names the offending bytes, so it is never echoed;
 * the request path itself still reaches {@code instance}, JSON-escaped and capped by
 * {@link ProblemResponses}, without its query string.
 * Registered as a bean, which Spring Security picks up and gives to its filter chain proxy.
 */
public final class ProblemRequestRejectedHandler implements RequestRejectedHandler {

    private final ProblemResponses problems;

    public ProblemRequestRejectedHandler(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            RequestRejectedException requestRejectedException) throws IOException {
        problems.write(request, response, ProblemCode.VALIDATION_FAILED);
    }
}

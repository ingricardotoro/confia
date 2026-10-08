package com.confia.shared.web.problem;

import com.confia.shared.web.authentication.AccessTokenExpiredException;
import com.confia.shared.web.authentication.AccessTokenRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers every request the chain denies for want of an authentication with a {@code 401}, in one of
 * three cases and by the type of the exception alone (session-tokens-and-web-layer design.md,
 * decision 5): an {@link AccessTokenExpiredException} is {@code token-expired}, an {@link
 * AccessTokenRejectedException} is {@code token-invalid}, and anything else, the anonymous request
 * the chain denied, is {@code authentication-required}.
 *
 * <p>The uniformity of the anonymous denial does not change: its cause is never inspected, so a
 * missing credential and a route that does not exist look the same. Only a credential that was
 * actually presented and judged earns a more specific answer. No message of the exception reaches
 * the body.
 */
public final class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponses problems;

    public ProblemAuthenticationEntryPoint(ProblemResponses problems) {
        this.problems = problems;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        problems.write(request, response, codeOf(authException));
    }

    private static ProblemCode codeOf(AuthenticationException exception) {
        if (exception instanceof AccessTokenExpiredException) {
            return ProblemCode.TOKEN_EXPIRED;
        }
        if (exception instanceof AccessTokenRejectedException) {
            return ProblemCode.TOKEN_INVALID;
        }
        return ProblemCode.AUTHENTICATION_REQUIRED;
    }
}

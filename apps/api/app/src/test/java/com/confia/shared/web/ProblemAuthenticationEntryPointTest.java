package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.authentication.AccessTokenExpiredException;
import com.confia.shared.web.authentication.AccessTokenRejectedException;
import com.confia.shared.web.problem.ProblemAuthenticationEntryPoint;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specs/web-edge, "El punto de entrada distingue tres casos" (session-tokens-and-web-layer design.md,
 * decision 5): the answer follows the type of the exception and nothing else, an exception's message
 * never reaches the body, and the anonymous denial keeps its single code.
 */
class ProblemAuthenticationEntryPointTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static MockHttpServletResponse answer(AuthenticationException exception)
            throws IOException {
        StaticMessageSource messages = new StaticMessageSource();
        for (ProblemCode code : ProblemCode.values()) {
            messages.addMessage(code.titleKey(), java.util.Locale.forLanguageTag("es-HN"), "t");
            messages.addMessage(code.detailKey(), java.util.Locale.forLanguageTag("es-HN"), "d");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ProblemAuthenticationEntryPoint(new ProblemResponses(messages))
                .commence(new MockHttpServletRequest("GET", "/x"), response, exception);
        return response;
    }

    private static String typeOf(MockHttpServletResponse response) throws IOException {
        return JSON.readTree(response.getContentAsString()).get("type").asString();
    }

    @Test
    void anExpiredTokenIsTokenExpired() throws IOException {
        MockHttpServletResponse response = answer(new AccessTokenExpiredException());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(typeOf(response)).isEqualTo("https://confia.hn/problems/token-expired");
    }

    @Test
    void aRejectedTokenIsTokenInvalid() throws IOException {
        MockHttpServletResponse response = answer(new AccessTokenRejectedException());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(typeOf(response)).isEqualTo("https://confia.hn/problems/token-invalid");
    }

    @Test
    void anyOtherAuthenticationFailureIsTheUniformAnonymousDenial() throws IOException {
        for (AuthenticationException other : new AuthenticationException[] {
                new InsufficientAuthenticationException("SECRETO-MENSAJE"),
                new BadCredentialsException("SECRETO-MENSAJE")}) {
            MockHttpServletResponse response = answer(other);

            assertThat(typeOf(response))
                    .isEqualTo("https://confia.hn/problems/authentication-required");
            assertThat(response.getContentAsString()).doesNotContain("SECRETO-MENSAJE");
        }
    }

    @Test
    void theTwoTokenExceptionsCarryAFixedMessageAndNoStackTrace() {
        assertThat(new AccessTokenRejectedException().getStackTrace()).isEmpty();
        assertThat(new AccessTokenExpiredException().getStackTrace()).isEmpty();
        assertThat(new AccessTokenRejectedException().getMessage())
                .isEqualTo(new AccessTokenExpiredException().getMessage()).isNotBlank();
        assertThat(new AccessTokenExpiredException()).isInstanceOf(AccessTokenRejectedException.class);
    }
}

package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Specs/web-edge, "El portal no tiene filtro de autenticación" (session-tokens-and-web-layer
 * design.md, decision 5): the portal chain does not read a bearer credential at all, so whatever
 * arrives in {@code Authorization} is as anonymous as no credential, and its {@code 401} still
 * carries the challenge that RFC 9110 requires of every one.
 */
class PortalChainBearerTest {

    private static HarnessProcess portal;

    @BeforeAll
    static void start() {
        portal = HarnessProcess.startAsPortal();
    }

    @AfterAll
    static void stop() {
        portal.close();
    }

    @Test
    void thePortalChainHasNoBearerFilter() {
        assertThat(portal.securityFilters())
                .as("non-vacuous: this is the real chain, which translates exceptions")
                .isNotEmpty()
                .doesNotHaveAnyElementsOfTypes(AccessTokenAuthenticationFilter.class);
    }

    @Test
    void aBearerCredentialIsIgnoredAndTheDenialIsTheAnonymousOneWithTheChallenge() {
        HttpResponse<String> response = portal.get("/x", "Authorization", "Bearer SECRETO.NO.TOKEN");

        assertProblem(response, 401, "authentication-required");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertProblem(portal.get("/x"), 401, "authentication-required");
    }
}

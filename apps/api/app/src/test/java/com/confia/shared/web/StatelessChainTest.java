package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.harness.HarnessProcess;
import jakarta.servlet.Filter;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.savedrequest.RequestCacheAwareFilter;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Specs/web-edge, "La cadena administrativa no tiene estado" (design.md decision 5): the chain
 * keeps nothing between requests, so no session exists, no cookie is set and no security context
 * is saved anywhere but the request itself. {@code AdminSecurityChainTest} proves the visible
 * effect, no session and no {@code Set-Cookie}. This class proves the two settings that make it
 * so, one by one, because the effect alone cannot tell them apart: {@code STATELESS} installs a
 * request cache that saves nothing, and disabling the request cache keeps a request from being
 * saved, so removing either one alone leaves every response unchanged and only the pair shows. A
 * login that arrives later and saves a context would then open a session through the one that is
 * left missing.
 */
class StatelessChainTest {

    private static HarnessProcess process;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    @Test
    void theChainSavesNoRequestToReplayAfterALogin() {
        List<Filter> filters = process.securityFilters();

        assertThat(filters).as("non-vacuous: this is the real chain, which translates exceptions")
                .hasAtLeastOneElementOfType(ExceptionTranslationFilter.class);
        assertThat(filters).as("a request cache filter would save the request in a session")
                .doesNotHaveAnyElementsOfTypes(RequestCacheAwareFilter.class);
    }

    @Test
    void theSecurityContextLivesOnlyInTheRequestAndNeverInASession() {
        SecurityContextHolderFilter holder = process.securityFilters().stream()
                .filter(SecurityContextHolderFilter.class::isInstance)
                .map(SecurityContextHolderFilter.class::cast).findFirst().orElseThrow();

        assertThat(ReflectionTestUtils.getField(holder, "securityContextRepository"))
                .as("any other repository keeps the context in the HTTP session")
                .isExactlyInstanceOf(RequestAttributeSecurityContextRepository.class);
    }
}

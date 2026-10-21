package com.confia.shared.web.edge;

import com.confia.shared.security.SessionValidity;
import com.confia.shared.security.csrf.CsrfTokens;
import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.csrf.AllowedOrigins;
import com.confia.shared.web.csrf.CookieCredentialRoute;
import com.confia.shared.web.csrf.CsrfOriginFilter;
import com.confia.shared.web.problem.ProblemAuthenticationEntryPoint;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.WebEdgeProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * The security chain of the administrative process: deny by default, with the allow-list of {@link
 * PublicEndpoints#forAdmin(Environment)}, the routes of {@link AuthenticatedEndpoints#forAdmin()},
 * the bearer authentication of {@link AccessTokenAuthenticationFilter} (web-edge-foundations
 * design.md, decision 5; session-tokens-and-web-layer design.md, decision 5) and the {@code Origin}
 * and CSRF defence of {@link CsrfOriginFilter} (session-endpoints-cookie-and-csrf design.md,
 * decision 2). Imported explicitly by {@code AdminApplication} (ADR-0024); the portal imports {@link
 * PortalSecurityConfiguration} instead and the worker has no chain at all.
 *
 * <p>Both filters are built here with {@code new} and are not beans, so the servlet container does
 * not register them a second time outside the chain and no other process can acquire them. The
 * token filter stands before the anonymous filter: a request that carries a credential is judged
 * before anyone is made anonymous. The CSRF filter stands before the token filter, so a request it
 * refuses verifies no token, reads no session and spends no quota of the limiter.
 */
@Configuration(proxyBeanMethods = false)
public class AdminSecurityConfiguration {

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, Environment environment,
            ProblemResponses problems, AccessTokenVerifier verifier,
            SessionValidity sessionValidity, CsrfTokens csrfTokens, WebEdgeProperties webEdge)
            throws Exception {
        AuthenticatedEndpoints authenticated = AuthenticatedEndpoints.forAdmin();
        HttpSecurity chain = SecurityChains.denyByDefault(http, PublicEndpoints.forAdmin(environment),
                authenticated, problems);
        addAuthenticationFilters(chain,
                new AccessTokenAuthenticationFilter(verifier, authenticated, sessionValidity,
                        new ProblemAuthenticationEntryPoint(problems)),
                new CsrfOriginFilter(AllowedOrigins.parse(webEdge.allowedOrigins()),
                        CookieCredentialRoute.forAdmin(), csrfTokens, problems));
        return chain.build();
    }

    /**
     * Adds the two filters in the one order that is proved: the token filter before the anonymous
     * filter, then the CSRF filter before the token filter. The real chain and the test harness
     * both call this, so the order cannot differ between them.
     */
    public static void addAuthenticationFilters(HttpSecurity http,
            AccessTokenAuthenticationFilter tokenFilter, CsrfOriginFilter csrfFilter) {
        http.addFilterBefore(tokenFilter, AnonymousAuthenticationFilter.class);
        http.addFilterBefore(csrfFilter, AccessTokenAuthenticationFilter.class);
    }
}

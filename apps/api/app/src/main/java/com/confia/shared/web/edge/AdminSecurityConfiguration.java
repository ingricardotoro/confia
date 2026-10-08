package com.confia.shared.web.edge;

import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.problem.ProblemAuthenticationEntryPoint;
import com.confia.shared.web.problem.ProblemResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * The security chain of the administrative process: deny by default, with the allow-list of {@link
 * PublicEndpoints#forAdmin(Environment)}, the routes of {@link AuthenticatedEndpoints#forAdmin()}
 * and the bearer authentication of {@link AccessTokenAuthenticationFilter} (web-edge-foundations
 * design.md, decision 5; session-tokens-and-web-layer design.md, decision 5). Imported explicitly by
 * {@code AdminApplication} (ADR-0024); the portal imports {@link PortalSecurityConfiguration}
 * instead and the worker has no chain at all.
 *
 * <p>The filter is built here with {@code new} and is not a bean, so the servlet container does not
 * register it a second time outside the chain and no other process can acquire it. It stands before
 * the anonymous filter: a request that carries a credential is judged before anyone is made
 * anonymous.
 */
@Configuration(proxyBeanMethods = false)
public class AdminSecurityConfiguration {

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, Environment environment,
            ProblemResponses problems, AccessTokenVerifier verifier) throws Exception {
        AuthenticatedEndpoints authenticated = AuthenticatedEndpoints.forAdmin();
        return SecurityChains
                .denyByDefault(http, PublicEndpoints.forAdmin(environment), authenticated, problems)
                .addFilterBefore(new AccessTokenAuthenticationFilter(verifier, authenticated,
                        new ProblemAuthenticationEntryPoint(problems)),
                        AnonymousAuthenticationFilter.class)
                .build();
    }
}

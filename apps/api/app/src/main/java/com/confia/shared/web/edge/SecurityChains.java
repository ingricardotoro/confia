package com.confia.shared.web.edge;

import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.authorization.PermissionDecider;
import com.confia.shared.web.authorization.PermissionedEndpoints;
import com.confia.shared.web.problem.ProblemAccessDeniedHandler;
import com.confia.shared.web.problem.ProblemAuthenticationEntryPoint;
import com.confia.shared.web.problem.ProblemResponses;
import java.util.List;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;

/**
 * The one definition of the deny-by-default chain, applied to the administrative process, to the
 * portal process and to the test harnesses (web-edge-foundations design.md, decision 5).
 *
 * <p>The chain has no state: no session, no cookie, no saved request. CSRF protection is off
 * because no credential travels in a cookie in this change; the session change decides it
 * (design.md, decision 5). Spring Security's header writer is off because {@code
 * SecurityHeadersFilter} is the single owner of the headers (decision 7), and HTTP Basic, form
 * login and logout are off because there is nothing to log in to yet.
 *
 * <p>The routes of {@link PublicEndpoints} are permitted, each for its own method only, and the
 * last rule denies everything else, so the answer never depends on whether a controller exists. An
 * anonymous request that is denied is sent to the entry point ({@code 401}); an authenticated one
 * goes to the access-denied handler ({@code 403}). Both answer with Problem Details.
 *
 * <p>The administrative chain adds a second list between the two (session-tokens-and-web-layer
 * design.md, decision 5): the routes of {@link AuthenticatedEndpoints}, each for its own method,
 * which any authenticated actor may use, with no role, permission or second factor involved. The
 * order is public routes, authenticated routes, then the final denial, so an authenticated actor on a
 * route that is on neither list gets {@code 403}.
 *
 * <p>The administrative chain adds a third list (rbac-permission-matrix-and-audit-integration design.md
 * D6): the routes of {@code PermissionedEndpoints}, after the restricted ones and before the final
 * denial, each decided by the manager the permission decider builds for its entry. It is the only place
 * that applies the {@code access} rule.
 */
public final class SecurityChains {

    private SecurityChains() {
    }

    /**
     * Applies the chain to {@code http} and returns it, so a test harness can add a filter of its
     * own before building. A production configuration builds it as it is.
     */
    public static HttpSecurity denyByDefault(HttpSecurity http, PublicEndpoints endpoints,
            ProblemResponses problems) throws Exception {
        return denyByDefault(http, endpoints, new AuthenticatedEndpoints(List.of()), problems);
    }

    /**
     * Like the chain without permission routes: the portal and the harnesses that have none.
     */
    public static HttpSecurity denyByDefault(HttpSecurity http, PublicEndpoints endpoints,
            AuthenticatedEndpoints authenticated, ProblemResponses problems) throws Exception {
        return denyByDefault(http, endpoints, authenticated, new PermissionedEndpoints(List.of()),
                null, problems);
    }

    /**
     * The administrative chain: like the one with authenticated routes, with the routes of {@code
     * permissioned} after the restricted ones and before the final denial, each decided by the
     * manager that {@code decider} builds for its entry (rbac-permission-matrix-and-audit-integration
     * design.md D6). {@code decider} may be {@code null} only when the list is empty.
     */
    public static HttpSecurity denyByDefault(HttpSecurity http, PublicEndpoints endpoints,
            AuthenticatedEndpoints authenticated, PermissionedEndpoints permissioned,
            PermissionDecider decider, ProblemResponses problems) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(RequestCacheConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(new ProblemAuthenticationEntryPoint(problems))
                        .accessDeniedHandler(new ProblemAccessDeniedHandler(problems)))
                .authorizeHttpRequests(requests -> {
                    endpoints.endpoints().forEach(endpoint -> requests
                            .requestMatchers(endpoint.method(), endpoint.pattern()).permitAll());
                    authenticated.endpoints().forEach(endpoint -> requests
                            .requestMatchers(endpoint.method(), endpoint.pattern())
                            .authenticated());
                    authenticated.secondFactorEndpoints().forEach(endpoint -> requests
                            .requestMatchers(endpoint.method(), endpoint.pattern())
                            .authenticated());
                    permissioned.endpoints().forEach(endpoint -> requests
                            .requestMatchers(endpoint.method(), endpoint.pattern())
                            .access(decider.forEndpoint(endpoint)));
                    requests.anyRequest().denyAll();
                });
    }
}

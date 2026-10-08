package com.confia.shared.web.harness;

import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.authentication.AuthenticatedEndpoint;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.authentication.SessionCheck;
import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import com.confia.shared.observability.metrics.ObservabilityMetricsConfiguration;
import com.confia.shared.security.RateLimiterConfiguration;
import com.confia.shared.web.edge.SecurityChains;
import com.confia.shared.web.edge.ThrottlingConfiguration;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.problem.ProblemAuthenticationEntryPoint;
import com.confia.shared.web.problem.ProblemResponses;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * A web process without a database, for the tests of the real security chain (web-edge-foundations
 * design.md, decision 5): the real {@link WebEdgeConfiguration} and the real {@link
 * SecurityChains#denyByDefault}, the same exclusions the real entry points carry, and test-only
 * controllers. The allow-list is the real one for the process named by {@value #PROCESS_PROPERTY}
 * plus the routes only this harness adds, and a stand-in principal filter lets a test be
 * authenticated, which no production code can do yet.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
@Import({WebEdgeConfiguration.class, RateLimiterConfiguration.class,
        ThrottlingConfiguration.class, ObservabilityMetricsConfiguration.class,
        HarnessController.class, LimitedController.class, UnknownPolicyController.class})
class WebEdgeHarness {

    /** {@code admin} (the default) or {@code portal}: which real allow-list the chain starts from. */
    static final String PROCESS_PROPERTY = "harness.process";

    @Bean
    Calls calls() {
        return new Calls();
    }

    @Bean
    SessionCounter sessionCounter() {
        return new SessionCounter();
    }

    @Bean
    HarnessTokens harnessTokens() {
        return new HarnessTokens();
    }

    @Bean
    SecurityFilterChain harnessChain(HttpSecurity http, Environment environment,
            ProblemResponses problems, HarnessTokens tokens) throws Exception {
        boolean portal = "portal".equals(environment.getProperty(PROCESS_PROPERTY));
        PublicEndpoints real = portal
                ? PublicEndpoints.forPortal(environment)
                : PublicEndpoints.forAdmin(environment);
        List<PublicEndpoint> endpoints = new ArrayList<>(real.endpoints());
        endpoints.add(new PublicEndpoint(HttpMethod.GET, "/test/open"));
        endpoints.add(new PublicEndpoint(HttpMethod.GET, "/test/actor"));
        endpoints.add(new PublicEndpoint(HttpMethod.GET, "/test/boom"));
        endpoints.add(new PublicEndpoint(HttpMethod.GET, "/test/origin"));
        endpoints.add(new PublicEndpoint(HttpMethod.GET, "/test/limited"));
        endpoints.add(new PublicEndpoint(HttpMethod.HEAD, "/test/limited"));
        endpoints.add(new PublicEndpoint(HttpMethod.GET, "/test/unlimited"));
        endpoints.add(new PublicEndpoint(HttpMethod.POST, "/test/validated"));
        for (String path : List.of("/test/bounded", "/test/constraint-violation", "/test/required", "/test/sql-reset", "/test/post-only", "/test/status", "/test/access-denied",
                "/test/unmapped",
                "/test/domain-known", "/test/domain-unknown", "/test/disconnected")) {
            endpoints.add(new PublicEndpoint(HttpMethod.GET, path));
        }
        if (portal) {
            return SecurityChains.denyByDefault(http, new PublicEndpoints(endpoints), problems)
                    .addFilterBefore(new TestPrincipalFilter(), AuthorizationFilter.class)
                    .build();
        }
        AuthenticatedEndpoints authenticated = new AuthenticatedEndpoints(List.of(
                new AuthenticatedEndpoint(HttpMethod.GET, "/test/whoami", SessionCheck.TOKEN_ONLY),
                new AuthenticatedEndpoint(HttpMethod.GET, "/test/live", SessionCheck.LIVE_SESSION)));
        return SecurityChains
                .denyByDefault(http, new PublicEndpoints(endpoints), authenticated, problems)
                .addFilterBefore(new AccessTokenAuthenticationFilter(tokens.verifier(),
                        authenticated, new ProblemAuthenticationEntryPoint(problems)),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new TestPrincipalFilter(), AuthorizationFilter.class)
                .build();
    }
}

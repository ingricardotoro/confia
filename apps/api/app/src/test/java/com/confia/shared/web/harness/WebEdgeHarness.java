package com.confia.shared.web.harness;

import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import com.confia.shared.web.edge.SecurityChains;
import com.confia.shared.web.edge.WebEdgeConfiguration;
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

/**
 * A web process without a database, for the tests of the real security chain (web-edge-foundations
 * design.md, decision 5): the real {@link WebEdgeConfiguration} and the real {@link
 * SecurityChains#denyByDefault}, the same exclusions the real entry points carry, and test-only
 * controllers. The allow-list is the real administrative one plus a route only this harness adds,
 * and a stand-in principal filter lets a test be authenticated, which no production code can do
 * yet.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class, ErrorMvcAutoConfiguration.class})
@Import({WebEdgeConfiguration.class, HarnessController.class})
class WebEdgeHarness {

    @Bean
    Calls calls() {
        return new Calls();
    }

    @Bean
    SecurityFilterChain harnessChain(HttpSecurity http, Environment environment,
            ProblemResponses problems) throws Exception {
        List<PublicEndpoint> endpoints = new ArrayList<>(
                PublicEndpoints.forAdmin(environment).endpoints());
        endpoints.add(new PublicEndpoint(HttpMethod.GET, "/test/open"));
        return SecurityChains.denyByDefault(http, new PublicEndpoints(endpoints), problems)
                .addFilterBefore(new TestPrincipalFilter(), AuthorizationFilter.class)
                .build();
    }
}

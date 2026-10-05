package com.confia.shared.web.edge;

import com.confia.shared.web.problem.ProblemResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The security chain of the portal process: the same deny-by-default chain as the administrative
 * one, with the allow-list of {@link PublicEndpoints#forPortal(Environment)}, which is empty unless
 * springdoc is on (web-edge-foundations design.md, decision 5). The portal registers no
 * application route yet, so with the default configuration it answers {@code 401} to everything.
 * Imported explicitly by {@code PortalApplication} (ADR-0024).
 */
@Configuration(proxyBeanMethods = false)
public class PortalSecurityConfiguration {

    @Bean
    SecurityFilterChain portalSecurityFilterChain(HttpSecurity http, Environment environment,
            ProblemResponses problems) throws Exception {
        return SecurityChains.denyByDefault(http, PublicEndpoints.forPortal(environment), problems)
                .build();
    }
}

package com.confia.shared.web.edge;

import com.confia.shared.web.problem.ProblemResponses;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The security chain of the administrative process: deny by default, with the allow-list of {@link
 * PublicEndpoints#forAdmin(Environment)} (web-edge-foundations design.md, decision 5). Imported
 * explicitly by {@code AdminApplication} (ADR-0024); the portal imports {@link
 * PortalSecurityConfiguration} instead and the worker has no chain at all.
 */
@Configuration(proxyBeanMethods = false)
public class AdminSecurityConfiguration {

    @Bean
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, Environment environment,
            ProblemResponses problems) throws Exception {
        return SecurityChains.denyByDefault(http, PublicEndpoints.forAdmin(environment), problems)
                .build();
    }
}

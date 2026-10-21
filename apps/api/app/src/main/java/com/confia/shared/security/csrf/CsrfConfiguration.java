package com.confia.shared.security.csrf;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The public configuration of the CSRF token (session-endpoints-cookie-and-csrf design.md,
 * decision 3; ADR-0024). It registers {@link CsrfTokens} over the keys read from the process
 * environment, with every startup check, so a missing or bad key stops the process while the
 * context is created and never fails the first request.
 *
 * <p>Only {@code AdminApplication} imports it. The portal and the worker must never hold the token
 * component: {@code ProcessBeanPolicy} lists this package as forbidden for both, and {@code
 * SigningKeyPlacementGuard} refuses the key properties in their environment.
 */
@Configuration(proxyBeanMethods = false)
public class CsrfConfiguration {

    @Bean
    CsrfTokens csrfTokens(Environment environment) {
        return new CsrfTokens(CsrfKeys.load(environment));
    }
}

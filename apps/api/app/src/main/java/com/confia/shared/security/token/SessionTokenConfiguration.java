package com.confia.shared.security.token;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The public configuration of the administrative signing keys (session-tokens-and-web-layer
 * design.md, decision 3; ADR-0024). It registers the key ring, built from the process environment
 * with every startup check, and nothing else yet: the issuer and the verifier of the access token
 * are added by the tasks that create them. The properties are read with {@link
 * Environment#getProperty(String)} and never with {@code @Value}, so that a failure never prints a
 * value (CLAUDE.md, regla 11).
 *
 * <p>Only {@code AdminApplication} imports it. The portal and the worker must never hold a ring:
 * this package is absent from the allow-list of both in {@code ProcessBeanPolicy}, so a ring there
 * fails the build; task 1.2b adds the explicit prohibition and the startup guard (ADR-0005, check 14).
 */
@Configuration(proxyBeanMethods = false)
public class SessionTokenConfiguration {

    @Bean
    SigningKeyRing signingKeyRing(Environment environment) {
        return SigningKeyRing.fromEnvironment(environment);
    }
}

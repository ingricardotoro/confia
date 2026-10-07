package com.confia.shared.security.token;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The public configuration of the administrative tokens (session-tokens-and-web-layer design.md,
 * decisions 3 and 4; ADR-0024). It registers the key ring, built from the process environment with
 * every startup check, the codec over that ring, and the issuer and the verifier of the access
 * token, which share the codec and the process clock. The properties are read with {@link
 * Environment#getProperty(String)} and never with {@code @Value}, so that a failure never prints a
 * value (CLAUDE.md, regla 11).
 *
 * <p>Only {@code AdminApplication} imports it. The portal and the worker must never hold a ring, an
 * issuer or a verifier: {@code ProcessBeanPolicy} lists this package as forbidden for both (ADR-0005,
 * check 14).
 */
@Configuration(proxyBeanMethods = false)
public class SessionTokenConfiguration {

    @Bean
    SigningKeyRing signingKeyRing(Environment environment) {
        return SigningKeyRing.fromEnvironment(environment);
    }

    @Bean
    CompactJws compactJws(SigningKeyRing ring) {
        return new CompactJws(ring);
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(CompactJws jws, Clock clock) {
        return new AccessTokenIssuer(jws, clock);
    }

    @Bean
    AccessTokenVerifier accessTokenVerifier(CompactJws jws, Clock clock) {
        return new AccessTokenVerifier(jws, clock);
    }
}

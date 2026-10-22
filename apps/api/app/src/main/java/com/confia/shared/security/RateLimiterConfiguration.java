package com.confia.shared.security;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the rate limiters of the process and exposes each only as the {@link RateLimiter} port
 * (web-edge-foundations design.md, decision 17; review S-6). The web layer receives the port and
 * never names {@link InMemoryRateLimiter}, so replacing the interim in-memory limiter with the
 * Redis-backed one of change 11 is an edit of this class alone, and the allow-list of the types of
 * {@code shared.security} that a web class may use does not include the implementation.
 *
 * <p><b>The name of a limiter bean is the name of its policy.</b> The registry of the web edge
 * collects every {@link RateLimiter} bean by bean name, so a policy is added by declaring a limiter
 * here under the name that {@code @RateLimited(policy = ...)} uses, with the values of its policy
 * bean. Imported explicitly by {@code AdminApplication} (ADR-0024), the only process with a rate
 * limiter.
 */
@Configuration(proxyBeanMethods = false)
public class RateLimiterConfiguration {

    /**
     * The limiter of the administrative login, named after its policy. The policies are told apart
     * by their bean name, which a qualifier states because the build does not keep parameter names.
     */
    @Bean("admin-login")
    RateLimiter adminLoginRateLimiter(
            @Qualifier("adminLoginRateLimitPolicy") RateLimitPolicy adminLoginRateLimitPolicy) {
        return new InMemoryRateLimiter(adminLoginRateLimitPolicy);
    }

    /** The limiter of the verification and enrollment of the second factor. */
    @Bean("admin-mfa")
    RateLimiter adminMfaRateLimiter(
            @Qualifier("adminMfaRateLimitPolicy") RateLimitPolicy adminMfaRateLimitPolicy) {
        return new InMemoryRateLimiter(adminMfaRateLimitPolicy);
    }

    /** The limiter of the refresh and the two ends of a session. */
    @Bean("admin-session")
    RateLimiter adminSessionRateLimiter(
            @Qualifier("adminSessionRateLimitPolicy") RateLimitPolicy adminSessionRateLimitPolicy) {
        return new InMemoryRateLimiter(adminSessionRateLimitPolicy);
    }
}

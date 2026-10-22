package com.confia.shared.web.edge;

import com.confia.shared.security.RateLimitPolicy;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.web.ratelimit.RateLimitFailures;
import com.confia.shared.web.ratelimit.RateLimitInterceptor;
import com.confia.shared.web.ratelimit.RateLimitMetrics;
import com.confia.shared.web.ratelimit.RateLimitPolicyCheck;
import com.confia.shared.web.ratelimit.RateLimitProperties;
import com.confia.shared.web.ratelimit.RateLimiterRegistry;
import java.util.Map;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The rate limiter of the administrative process at the edge (web-edge-foundations design.md,
 * decisions 15 to 17; session-endpoints-cookie-and-csrf decision 7): the three policies of the
 * administrative process read from configuration (login, second factor and session), the
 * registry of the limiters of the process, the interceptor that asks one before a {@code
 * @RateLimited} controller runs, and the check that stops the start on a policy no limiter holds.
 * Imported explicitly by {@code AdminApplication} (ADR-0024); the portal and the worker never load
 * it, and the class needs a {@link RateLimitMetrics} bean, so a process that wires it without the
 * port does not start.
 *
 * <p>The limiters themselves are built in {@code com.confia.shared.security} and arrive here as the
 * {@link RateLimiter} port, one bean per policy named after it (review S-6): this class never
 * names an implementation. The policies are registered and none is applied to a production
 * route yet: the controllers that carry the annotation arrive with the session endpoints. They
 * are the interim, per-process limiters (docs/03-seguridad.md, dated notes of sections 4.4 and 10),
 * which the Redis-backed one replaces behind the same port in change 11.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class ThrottlingConfiguration {

    /** The values of the policy of the administrative login, for the limiter built from them. */
    @Bean
    RateLimitPolicy adminLoginRateLimitPolicy(RateLimitProperties properties) {
        return properties.adminLogin().policy();
    }

    /** The values of the policy of the second factor, for the limiter built from them. */
    @Bean
    RateLimitPolicy adminMfaRateLimitPolicy(RateLimitProperties properties) {
        return properties.adminMfa().policy();
    }

    /** The values of the policy of the refresh and the two ends of a session, for its limiter. */
    @Bean
    RateLimitPolicy adminSessionRateLimitPolicy(RateLimitProperties properties) {
        return properties.adminSession().policy();
    }

    /** Reports a failure of the client of the request to the limiter of a policy. */
    @Bean
    RateLimitFailures rateLimitFailures(RateLimiterRegistry limiters) {
        return new RateLimitFailures(limiters);
    }

    /** Every limiter of the process by the name of its policy, which is the name of its bean. */
    @Bean
    RateLimiterRegistry rateLimiterRegistry(Map<String, RateLimiter> limitersByPolicy) {
        return new RateLimiterRegistry(limitersByPolicy);
    }

    @Bean
    RateLimitInterceptor rateLimitInterceptor(RateLimiterRegistry limiters,
            RateLimitMetrics metrics) {
        return new RateLimitInterceptor(limiters, metrics);
    }

    @Bean
    RateLimitPolicyCheck rateLimitPolicyCheck(RateLimiterRegistry limiters,
            ListableBeanFactory beans) {
        return new RateLimitPolicyCheck(limiters, beans);
    }

    /** Puts the interceptor in front of every handler; it acts only on {@code @RateLimited}. */
    @Bean
    WebMvcConfigurer rateLimitInterceptors(RateLimitInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}

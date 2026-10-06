package com.confia.shared.web.edge;

import com.confia.shared.security.RateLimitPolicy;
import com.confia.shared.security.RateLimiter;
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
 * decisions 15 to 17): the policy of the administrative login read from configuration, the
 * registry of the limiters of the process, the interceptor that asks one before a {@code
 * @RateLimited} controller runs, and the check that stops the start on a policy no limiter holds.
 * Imported explicitly by {@code AdminApplication} (ADR-0024); the portal and the worker never load
 * it, and the class needs a {@link RateLimitMetrics} bean, so a process that wires it without the
 * port does not start.
 *
 * <p>The limiters themselves are built in {@code com.confia.shared.security} and arrive here as the
 * {@link RateLimiter} port, one bean per policy named after it (review S-6): this class never
 * names an implementation. The policy {@code admin-login} is registered and is applied to no
 * production route: the login controller that carries the annotation arrives with the session
 * change. It is the interim, per-process limiter (docs/03-seguridad.md, dated notes of sections 4.4
 * and 10), which the Redis-backed one replaces behind the same port in change 11.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
public class ThrottlingConfiguration {

    /** The values of the policy of the administrative login, for the limiter built from them. */
    @Bean
    RateLimitPolicy adminLoginRateLimitPolicy(RateLimitProperties adminLogin) {
        return adminLogin.policy();
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

package com.confia.shared.web.edge;

import com.confia.shared.security.IdempotentExecutor;
import com.confia.shared.web.idempotency.IdempotencyKeyInterceptor;
import com.confia.shared.web.idempotency.IdempotentRequestHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The HTTP edge of idempotency in the administrative process (web-edge-foundations design.md,
 * decision 19): the interceptor that requires and validates the {@code Idempotency-Key} header of
 * an {@code @IdempotentWrite} method before its controller runs, and the handler that runs the
 * use case under that key and translates what {@link IdempotentExecutor} returns. Imported
 * explicitly by {@code AdminApplication} (ADR-0024); the portal and the worker never load it, and
 * the class needs the executor of {@code SharedPlatformConfiguration}, so a process that wires it
 * without that configuration does not start.
 *
 * <p>It is applied to no production endpoint: the only one that uses it is the demonstration
 * controller of the test tree. This configuration is the only class outside {@code
 * com.confia.shared.web.idempotency} that names the package (rule W5).
 */
@Configuration(proxyBeanMethods = false)
public class IdempotencyEdgeConfiguration {

    @Bean
    IdempotencyKeyInterceptor idempotencyKeyInterceptor() {
        return new IdempotencyKeyInterceptor();
    }

    @Bean
    IdempotentRequestHandler idempotentRequestHandler(IdempotentExecutor executor) {
        return new IdempotentRequestHandler(executor);
    }

    /** Puts the interceptor in front of every handler; it acts only on {@code @IdempotentWrite}. */
    @Bean
    WebMvcConfigurer idempotencyInterceptors(IdempotencyKeyInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}

package com.confia.shared.observability.metrics;

import com.confia.shared.web.ratelimit.RateLimitMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The adapters of the metrics ports of the web edge (web-edge-foundations design.md, decision 17;
 * docs/07-observabilidad-y-operaciones.md section 1.1). Today the one port is {@link
 * RateLimitMetrics} and its adapter writes a log event; the observability change replaces this
 * class with the Prometheus one. Imported explicitly by {@code AdminApplication} (ADR-0024), the
 * only process with a rate limiter.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityMetricsConfiguration {

    @Bean
    RateLimitMetrics rateLimitMetrics() {
        return new LogRateLimitMetrics();
    }
}

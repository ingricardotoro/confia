package com.confia.shared.observability.health;

import java.util.Collection;
import java.util.Set;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.actuate.endpoint.web.WebOperation;
import org.springframework.boot.actuate.endpoint.web.WebOperationRequestPredicate;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.webmvc.actuate.endpoint.web.AdditionalHealthEndpointPathsWebMvcHandlerMapping;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serves the two health probes at the exact routes {@code GET /health/live} and {@code GET /health/ready}
 * (dockerfiles-and-local-compose design.md decision 4.5; scenarios CR09, CR13, WE94).
 *
 * <p>Spring Boot 4.1.1 only accepts an {@code additional-path} with one segment (probe P-2:
 * {@code AdditionalHealthEndpointPath} refuses {@code server:/health/live} with "'value' must contain
 * only one segment"), so the groups declare {@code server:/live} and {@code server:/ready} and the
 * framework's own mapping for them is excluded by name from the two web entry points. This mapping
 * registers the same operations of the health endpoint under the {@value #BASE_PATH} prefix instead. The
 * group is still resolved by the framework from the last segment, exactly as for an additional path, so
 * the body, the status code and the absence of components and details stay the framework's.
 *
 * <p>The routes are exact patterns, never {@code /health/{*path}}: the closed allow-list compares patterns
 * by equality (WE97), and a route nobody listed must fail that test. There is no {@code /actuator/**}
 * mapping because the management port is disabled. Imported explicitly by the administrative and the portal
 * entry points (ADR-0024); the worker has no web server.
 */
@Configuration(proxyBeanMethods = false)
public class HealthProbeRoutesConfiguration {

    /** The prefix of both probe routes. */
    static final String BASE_PATH = "/health";

    @Bean
    HealthProbeHandlerMapping healthProbeHandlerMapping(WebEndpointsSupplier endpoints, HealthEndpointGroups groups) {
        ExposableWebEndpoint health = endpoints.getEndpoints().stream()
                .filter(endpoint -> HealthEndpoint.ID.equals(endpoint.getEndpointId())).findFirst().orElse(null);
        return new HealthProbeHandlerMapping(health, groups.getAllWithAdditionalPath(WebServerNamespace.SERVER));
    }

    /** The mapping of the probes: the framework's additional-path mapping with a prefix on each route. */
    static final class HealthProbeHandlerMapping extends AdditionalHealthEndpointPathsWebMvcHandlerMapping {

        private final ExposableWebEndpoint health;
        private final Collection<HealthEndpointGroup> probes;

        HealthProbeHandlerMapping(ExposableWebEndpoint health, Set<HealthEndpointGroup> probes) {
            super(health, probes);
            this.health = health;
            this.probes = probes;
        }

        @Override
        protected void initHandlerMethods() {
            if (health == null) {
                return;
            }
            for (WebOperation operation : health.getOperations()) {
                WebOperationRequestPredicate predicate = operation.getRequestPredicate();
                if (predicate.getMatchAllRemainingPathSegmentsVariable() == null) {
                    continue;
                }
                for (HealthEndpointGroup probe : probes) {
                    AdditionalHealthEndpointPath path = probe.getAdditionalPath();
                    if (path != null) {
                        registerMapping(health, predicate, operation, route(path.getValue()));
                    }
                }
            }
        }

        /** {@code /live} becomes {@code /health/live}. */
        static String route(String additionalPath) {
            return BASE_PATH + (additionalPath.startsWith("/") ? additionalPath : "/" + additionalPath);
        }
    }
}

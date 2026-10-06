package com.confia.shared.web.edge;

import com.confia.shared.web.delay.DelayProperties;
import com.confia.shared.web.delay.RequiredDelayMaterializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The delay materializer of the administrative process (web-edge-foundations design.md, decision
 * 18). Imported explicitly by {@code AdminApplication} (ADR-0024); the portal and the worker never
 * load it. It is applied to no production endpoint yet: the login that asks it for a delay arrives
 * with the session change.
 *
 * <p>The start stops, with a message that names the property, when the permits are not positive
 * ({@link DelayProperties}), when {@code server.tomcat.max-connections} is not positive (Tomcat
 * reads -1 as no limit), when they are more than half of {@code server.tomcat.max-connections}
 * (so that the waits can never use up the sockets of the requests that do not wait), or when {@code
 * spring.threads.virtual.enabled} is off (a wait on a platform thread would hold a thread of the
 * server's pool).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DelayProperties.class)
public class RequiredDelayConfiguration {

    private static final String MAX_CONNECTIONS = "server.tomcat.max-connections";
    private static final int TOMCAT_DEFAULT_MAX_CONNECTIONS = 8192;
    private static final String VIRTUAL_THREADS = "spring.threads.virtual.enabled";

    @Bean
    RequiredDelayMaterializer requiredDelayMaterializer(DelayProperties properties,
            Environment environment) {
        if (!environment.getProperty(VIRTUAL_THREADS, Boolean.class, false)) {
            throw new IllegalStateException(VIRTUAL_THREADS + " must be true: the required delay "
                    + "is waited on a virtual thread, never on a thread of the server's pool");
        }
        int maxConnections = environment.getProperty(MAX_CONNECTIONS, Integer.class,
                TOMCAT_DEFAULT_MAX_CONNECTIONS);
        if (maxConnections <= 0) {
            // -1 is "no limit" to Tomcat, and half of no limit bounds nothing.
            throw new IllegalStateException(MAX_CONNECTIONS + " must be positive, so that the "
                    + "waits can be bounded to half of it; it is " + maxConnections);
        }
        if ((long) properties.maxConcurrentWaits() * 2 > maxConnections) {
            throw new IllegalStateException(DelayProperties.MAX_CONCURRENT_WAITS + " is "
                    + properties.maxConcurrentWaits() + ", which is more than half of "
                    + MAX_CONNECTIONS + " (" + maxConnections + "): the waits would use up the "
                    + "connections of the requests that do not wait");
        }
        return new RequiredDelayMaterializer(properties.maxConcurrentWaits());
    }
}

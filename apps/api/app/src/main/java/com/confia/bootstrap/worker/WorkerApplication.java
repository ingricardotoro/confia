package com.confia.bootstrap.worker;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Background worker process entry point (ADR-0003, ADR-0013, ADR-0024), started without an HTTP
 * server ({@code ConfiaApplication} forces {@code WebApplicationType.NONE}). It imports nothing:
 * the worker declares its own modules here and never inherits the administrative bean graph, so
 * it carries none of the OpenAPI surface the web processes import. No business module or
 * background job is registered yet (db-scheduler tasks arrive with the modules that need them,
 * ADR-0016).
 *
 * <p>{@link DataSourceAutoConfiguration} stays excluded (decision D5 of web-edge-foundations): the
 * worker has no database until the db-scheduler jobs of ADR-0016 arrive with change 9. Only the
 * administrative process has a {@code DataSource}.
 *
 * <p>{@link SecurityAutoConfiguration} and {@link UserDetailsServiceAutoConfiguration} are
 * excluded (decision 4 of web-edge-foundations): the worker has no web edge, and Spring
 * Security's autoconfiguration would still leave three inert beans in it. An inert bean today is a
 * surface someone wires up tomorrow, and a context with no security bean is the plainest proof
 * that the worker has no security chain.
 *
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
        SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
public class WorkerApplication {
}

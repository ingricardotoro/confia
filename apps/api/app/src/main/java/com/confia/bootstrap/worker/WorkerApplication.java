package com.confia.bootstrap.worker;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

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
 * <p>The class is public only so the launcher in the parent package can start it; nothing else
 * may reference it (enforced by ArchUnit).
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
public class WorkerApplication {
}

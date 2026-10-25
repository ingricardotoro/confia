package com.confia.shared.jobs.infrastructure.wiring;

import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.TransactionRunnerConfiguration;
import com.confia.shared.security.TransactionalDataSource;
import java.time.Clock;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The data access of the worker process, and nothing more (background-jobs-with-db-scheduler
 * design.md decision 12). The worker does not reuse {@code SharedPlatformConfiguration}: that one
 * carries the column-encryption master key, the idempotency executor and the request-origin
 * decorator of the audit writer, none of which a background process has (no key, no request).
 * It declares the {@link DSLContext} with statement logging off, the single transactional component
 * with its proxy, the clock and the plain audit writer.
 *
 * <p>The process starts without a reachable database: the pool of Spring Boot's {@code
 * DataSource} is lazy, so a database that is down is reported on the first use. The sensitive log
 * guard is already in the Logback chain when this configuration creates its first bean (it is
 * installed by an initializer of the entry point), which is what makes it safe for this process
 * to hold a {@code DataSource} at all (condition 4 of the foundations plan).
 *
 * <p>The package ends in {@code infrastructure.wiring} because ArchUnit confines jOOQ to {@code
 * ..infrastructure..} packages (ADR-0015 rule 4).
 */
@Configuration(proxyBeanMethods = false)
@Import(TransactionRunnerConfiguration.class)
public class WorkerPlatformConfiguration {

    @Bean
    DSLContext dslContext(TransactionalDataSource transactionalDataSource) {
        // Statement logging is off whatever the level of any logger: jOOQ logs a statement with its
        // bind values inlined at DEBUG, and a value must never reach a log (CLAUDE.md regla 11).
        return DSL.using(new DefaultConfiguration()
                .set(new DataSourceConnectionProvider(transactionalDataSource.proxy()))
                .set(SQLDialect.POSTGRES)
                .set(new Settings().withExecuteLogging(false)));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The plain writer: a background run has no request whose origin could be recorded. */
    @Bean
    AuditLogWriter auditLogWriter(DSLContext dsl) {
        return new JooqAuditLogWriter(dsl);
    }
}

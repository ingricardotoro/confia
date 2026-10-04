package com.confia.support;

import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Test-only {@code @SpringBootConfiguration} for {@link PostgresIntegrationTest} and its
 * subclasses (design.md decision 7). {@code @SpringBootTest} without {@code classes} searches for
 * a {@code @SpringBootConfiguration} by walking up the test's own package, and the three real
 * entry points ({@code AdminApplication}, {@code PortalApplication}, {@code WorkerApplication})
 * live in per-process sub-packages of {@code com.confia.bootstrap}, so none of them would ever be
 * found from {@code com.confia.support}, and loading one would drag in a process wiring. This
 * class brings the data source, Flyway and a {@link DSLContext} and nothing else: no component
 * scanning, no web server ({@code webEnvironment = NONE} on the test classes that use it).
 *
 * <p><b>{@link DSLContext} is declared explicitly, not autoconfigured.</b> Spring Boot's jOOQ
 * autoconfiguration, present through the 3.x line, does not exist in Spring Boot 4.1.1 (verified
 * by inspecting {@code spring-boot-autoconfigure-4.1.1.jar}: zero classes under {@code
 * org/springframework/boot/autoconfigure/jooq/}, zero entries in {@code
 * AutoConfiguration.imports}; apply-progress.md task 1.1, S1.6). The {@link DataSourceConnectionProvider}
 * wrapping a {@link TransactionAwareDataSourceProxy} below is the same mechanism the retired
 * autoconfiguration used, and was verified empirically to join a Spring-managed {@code
 * @Transactional} test transaction: a {@code set_config(..., true)} issued through this {@link
 * DSLContext} is visible to a later query issued through the same {@link DSLContext} inside the
 * same transaction (apply-progress.md task 1.1, S1.6).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class IntegrationTestApplication {

    @Bean
    DSLContext dslContext(DataSource dataSource) {
        DataSourceConnectionProvider connectionProvider =
                new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource));
        return DSL.using(new DefaultConfiguration()
                .set(connectionProvider)
                .set(SQLDialect.POSTGRES));
    }
}

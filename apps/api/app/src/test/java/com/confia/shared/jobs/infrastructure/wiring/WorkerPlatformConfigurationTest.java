package com.confia.shared.jobs.infrastructure.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.security.TransactionalDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DataSourceConnectionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The {@code DSLContext} of the worker (background-jobs-with-db-scheduler design.md decision 12): it
 * sits on the process's single transaction-aware proxy and never logs a statement with its bind
 * values, whatever level any logger has (CLAUDE.md regla 11).
 */
class WorkerPlatformConfigurationTest {

    @Test
    void theWorkerDslContextHasStatementLoggingOffAndSitsOnTheSharedProxy() {
        TransactionalDataSource holder = new TransactionalDataSource(
                new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never"));

        DSLContext dsl = new WorkerPlatformConfiguration().dslContext(holder);

        assertThat(dsl.settings().isExecuteLogging()).isFalse();
        assertThat(dsl.dialect()).isEqualTo(SQLDialect.POSTGRES);
        assertThat(dsl.configuration().connectionProvider())
                .isInstanceOfSatisfying(DataSourceConnectionProvider.class,
                        provider -> assertThat(provider.dataSource()).isSameAs(holder.proxy()));
    }
}

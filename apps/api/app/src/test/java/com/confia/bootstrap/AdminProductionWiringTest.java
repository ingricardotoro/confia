package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.IdempotentExecutor;
import com.confia.shared.security.TransactionRunner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Specs/build-integrity of web-edge-foundations: the administrative process registers its
 * production data access (decision 2), starts with a database nothing listens on, and never runs
 * a migration at startup; the portal and the worker still have no {@code DataSource}. Every
 * context is started through the production launcher with {@link TestProcessArguments}, so no
 * container is needed and this stays a {@code *Test}.
 */
class AdminProductionWiringTest {

    @Test
    void theAdminContextHoldsOneBeanOfEachProductionWiringType() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(DSLContext.class)).hasSize(1);
            assertThat(context.getBeansOfType(TransactionRunner.class)).hasSize(1);
            assertThat(context.getBeansOfType(Clock.class)).hasSize(1);
            assertThat(context.getBeansOfType(AuditLogWriter.class))
                    .as("exactly one audit writer, so a later decorator cannot create a second")
                    .hasSize(1);
            assertThat(context.getBeansOfType(IdempotentExecutor.class)).hasSize(1);
            assertThat(context.getBeansOfType(ColumnEncryptionService.class)).hasSize(1);
        } finally {
            context.close();
        }
    }

    @Test
    void theAdminContextRunsNoMigrationAtStartup() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeansOfType(FlywayMigrationInitializer.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void theAdminContextUsesUtcAsItsClock() {
        ConfigurableApplicationContext context = start("admin");
        try {
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
        } finally {
            context.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"portal", "worker"})
    void portalAndWorkerHoldNoDataSource(String process) {
        ConfigurableApplicationContext context = start(process);
        try {
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(DSLContext.class)).isEmpty();
            assertThat(context.getBeansOfType(TransactionRunner.class)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void aMissingMasterKeyStopsTheAdminProcessAndNamesThePropertyWithoutAnyValue() {
        assertThatThrownBy(() -> ConfiaApplication.launch(
                TestProcessArguments.adminWithoutMasterKey(), "admin"))
                .satisfies(failure -> {
                    String trace = stackTraceOf(failure);
                    assertThat(trace).contains(TestProcessArguments.MASTER_KEY_PROPERTY);
                    assertThat(trace).contains("IllegalStateException");
                    assertThat(trace).doesNotContain(TestProcessArguments.MASTER_KEY_PROPERTY + "=");
                });
    }

    @Test
    void anInvalidMasterKeyStopsTheAdminProcessWithoutEchoingIt() {
        String notBase64 = "not-a-valid-base64-key!";

        assertThatThrownBy(() -> ConfiaApplication.launch(
                TestProcessArguments.adminWithMasterKey(notBase64), "admin"))
                .satisfies(failure -> assertThat(stackTraceOf(failure)).doesNotContain(notBase64));
    }

    private static ConfigurableApplicationContext start(String process) {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess(process), process);
        assertThat(outcome.context()).as("the %s process must start", process).isNotNull();
        return outcome.context();
    }

    private static String stackTraceOf(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}

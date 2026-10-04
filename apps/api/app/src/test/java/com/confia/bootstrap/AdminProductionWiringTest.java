package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.zaxxer.hikari.HikariDataSource;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.IdempotentExecutor;
import com.confia.shared.security.TransactionRunner;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.sql.SQLException;
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
    void theAdminPoolIsLazyAndHasNotConnectedAfterStartup() throws SQLException {
        ConfigurableApplicationContext context = start("admin");
        try {
            HikariDataSource pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
            assertThat(pool.getHikariPoolMXBean())
                    .as("a pool that has started would have a MXBean: the start must be lazy")
                    .isNull();
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
                    assertThat(stackTraceOf(failure)).contains(TestProcessArguments.MASTER_KEY_PROPERTY);
                    assertThat(stackTraceOf(failure)).contains("IllegalStateException");
                    assertNoSecretFragment(failure, "");
                });
    }

    @Test
    void anInvalidMasterKeyStopsTheAdminProcessWithoutEchoingIt() {
        String notBase64 = "Zq9#Xw7!Lm2@Pk4$";

        assertThatThrownBy(() -> ConfiaApplication.launch(
                TestProcessArguments.adminWithMasterKey(notBase64), "admin"))
                .satisfies(failure -> {
                    assertThat(stackTraceOf(failure))
                            .contains(TestProcessArguments.MASTER_KEY_PROPERTY);
                    assertNoSecretFragment(failure, notBase64);
                });
    }

    /**
     * Walks the whole cause chain: no decoder exception may be chained (its message carries a
     * fragment of the value), and no message or trace may contain the value or any substring of it
     * longer than three characters.
     */
    private static void assertNoSecretFragment(Throwable failure, String secret) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = failure; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        assertThat(chain).as("no IllegalArgumentException anywhere in the cause chain")
                .noneMatch(IllegalArgumentException.class::isInstance);
        String everything = stackTraceOf(failure);
        for (Throwable t : chain) {
            everything += " | " + t.getMessage();
        }
        assertThat(everything).doesNotContain("Illegal base64");
        for (int length = 4; length <= secret.length(); length++) {
            for (int start = 0; start + length <= secret.length(); start++) {
                assertThat(everything).as("fragment of the value")
                        .doesNotContain(secret.substring(start, start + length));
            }
        }
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

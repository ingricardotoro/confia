package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.logging.SensitiveDataLoggingTest;
import com.confia.shared.logging.SensitiveLogGuard;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.SharedPostgresContainer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Condition 4 of the foundations plan, closed by background-jobs-with-db-scheduler task 1.5 (specs
 * BI89 and BI90; design.md decisions 12 and 13, probe P-11): the real worker process, started by
 * the production launcher against a real PostgreSQL, with the root logger and every protected
 * prefix at {@code TRACE}, runs a first statement whose bound value is distinctive. The value goes
 * through {@code set_config} (the security context of {@link TransactionRunner}) and through a
 * query of the real {@link DSLContext}. With the guard in place no log event carries it.
 *
 * <p>The negative control takes the guard away and runs the same statements: the value then does
 * appear, in the PostgreSQL driver at least, so the test above reaches the dangerous case instead
 * of passing over silence. The prefixes whose loggers really leaked are printed by the control as
 * "proven"; the rest of the list is preventive.
 */
class WorkerSensitiveLoggingIT {

    private static final String VALUE = "valor-distintivo-7f3a";

    @Test
    void theBoundValueNeverAppearsInAnyLogEventOfTheRealWorker() {
        List<ILoggingEvent> events = runStatementsInTheWorker(true);

        assertThat(events).as("non-vacuous: the worker did log while the statements ran")
                .isNotEmpty();
        assertThat(SensitiveDataLoggingTest.violations(events, List.of(VALUE))).isEmpty();
    }

    @Test
    void withoutTheGuardTheBoundValueLeaks() {
        List<ILoggingEvent> events = runStatementsInTheWorker(false);

        List<String> leaks = SensitiveDataLoggingTest.violations(events, List.of(VALUE));
        TreeSet<String> proven = new TreeSet<>();
        for (String prefix : SensitiveLogGuard.protectedPrefixes()) {
            if (leaks.stream().anyMatch(leak -> leak.startsWith(prefix + ".")
                    || leak.startsWith(prefix + " "))) {
                proven.add(prefix);
            }
        }
        TreeSet<String> preventive = new TreeSet<>(SensitiveLogGuard.protectedPrefixes());
        preventive.removeAll(proven);
        System.out.println("PROVEN prefixes (leak without the guard): " + proven);
        System.out.println("PREVENTIVE prefixes (no leak observed): " + preventive);

        assertThat(leaks).as("without the guard the value reaches the log").isNotEmpty();
        assertThat(proven).as("the PostgreSQL driver writes the bound parameters").contains(
                "org.postgresql");
    }

    private static List<ILoggingEvent> runStatementsInTheWorker(boolean withGuard) {
        // Testcontainers' own URL carries loggerLevel=OFF, which silences the driver; the real
        // worker connects without it.
        String url = "jdbc:postgresql://" + SharedPostgresContainer.instance().getHost() + ":"
                + SharedPostgresContainer.instance().getMappedPort(5432) + "/confia_test";
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.workerAgainst(url,
                "confia_admin_app", "test-only-not-a-secret"), "worker");
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the worker must start").isNotNull();

        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = logback.getLogger(Logger.ROOT_LOGGER_NAME);
        Map<Logger, Level> before = new LinkedHashMap<>();
        List<Logger> raised = new ArrayList<>(List.of(root));
        SensitiveLogGuard.protectedPrefixes().forEach(prefix -> raised.add(logback.getLogger(prefix)));
        raised.forEach(logger -> before.put(logger, logger.getLevel()));
        SensitiveLogGuard guard = withGuard ? null : context.getBean(SensitiveLogGuard.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(logback);
        appender.start();
        try {
            if (!withGuard) {
                logback.getTurboFilterList().remove(guard);
            }
            root.addAppender(appender);
            raised.forEach(logger -> logger.setLevel(Level.TRACE));

            TransactionRunner runner = context.getBean(TransactionRunner.class);
            DSLContext dsl = context.getBean(DSLContext.class);
            runner.execute(new SecurityContext(VALUE, "system", UUID.randomUUID().toString(),
                    UUID.randomUUID().toString()), () -> {
                        dsl.fetch("select ? as bound", VALUE);
                        return dsl.fetchOne("select current_setting('app.actor_id') as actor");
                    });
        } finally {
            root.detachAppender(appender);
            before.forEach(Logger::setLevel);
            if (!withGuard) {
                logback.addTurboFilter(guard);
            }
            context.close();
        }
        return new ArrayList<>(appender.list);
    }
}

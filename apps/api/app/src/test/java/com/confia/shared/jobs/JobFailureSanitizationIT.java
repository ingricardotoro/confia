package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.jobs.SchedulingFixtures.Probe;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Scenario BJ60 (background-jobs-with-db-scheduler design.md decision 10): a handler that throws with
 * a distinctive value in its message and a {@link SQLException} in its cause leaves that value in no
 * log event, the {@code ERROR} of the library included. The events keep the task name, the class of
 * the original exception and the {@code SQLState}.
 */
class JobFailureSanitizationIT extends WorkerJobIT {

    private static final String DISTINCTIVE = "valor-distintivo-91c4";
    static final JobType<Probe> POISONED = fastType("identity.poisoned", Probe.class, 1);

    @Configuration(proxyBeanMethods = false)
    static class Fixtures {

        @Bean
        JobDefinition poisoned() {
            return new OneTimeJob<>(POISONED, (payload, context) -> {
                throw new IllegalStateException("could not process " + DISTINCTIVE,
                        new SQLException("duplicate key value: Key (email)=(" + DISTINCTIVE + ")", "23505"));
            });
        }
    }

    /** Every piece of text of an event that could carry the value: message, arguments, MDC and trace. */
    private static List<String> textsOf(ILoggingEvent event) {
        List<String> texts = new ArrayList<>();
        texts.add(event.getFormattedMessage());
        texts.add(event.getMessage());
        if (event.getArgumentArray() != null) {
            for (Object argument : event.getArgumentArray()) {
                texts.add(String.valueOf(argument));
            }
        }
        texts.add(String.valueOf(event.getMDCPropertyMap()));
        for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
            texts.add(proxy.getClassName());
            texts.add(String.valueOf(proxy.getMessage()));
            for (var element : proxy.getStackTraceElementProxyArray()) {
                texts.add(element.getStackTraceElement().toString());
            }
        }
        return texts;
    }

    @Test
    void theDistinctiveValueReachesNoLogEventAndTheSafeFactsRemain() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        JobInstanceId instance;
        try (ConfigurableApplicationContext worker =
                WorkerHarness.start(Fixtures.class, SchedulingFixtures.INSTITUTION)) {
            // After the start: Spring Boot re-initializes Logback while the context is prepared and
            // would detach an appender added before it.
            root.addAppender(appender);
            instance = scheduleProbe(POISONED);
            Eventually.within(Duration.ofSeconds(90), () -> {
                assertThat(rowOf(instance)).isNotNull();
                assertThat(rowOf(instance).consecutiveFailures()).isEqualTo(1);
            });
        } finally {
            root.detachAppender(appender);
        }

        List<ILoggingEvent> events = List.copyOf(appender.list);
        assertThat(events).as("non-vacuous: the worker logged the failure").anyMatch(
                e -> e.getFormattedMessage().startsWith("job_failed"));
        assertThat(events.stream().flatMap(e -> textsOf(e).stream()))
                .noneMatch(text -> text.contains(DISTINCTIVE))
                .noneMatch(text -> text.contains("Key (email)"));
        List<String> failureTexts = events.stream().filter(e -> e.getThrowableProxy() != null)
                .flatMap(e -> textsOf(e).stream()).toList();
        assertThat(failureTexts).as("the library's own ERROR keeps the task, the class and the SQLState")
                .anyMatch(text -> text.contains("identity.poisoned") && text.contains("IllegalStateException")
                        && text.contains("23505"));
    }
}

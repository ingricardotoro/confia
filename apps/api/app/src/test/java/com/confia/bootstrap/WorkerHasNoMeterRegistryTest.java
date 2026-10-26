package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Probe P-3 (background-jobs-with-db-scheduler design.md decision 2): {@code micrometer-core} arrives
 * with the db-scheduler starter, but nothing registers a {@code MeterRegistry} in any process,
 * because the metrics of the queue go through a log adapter until the observability changes (P8).
 */
class WorkerHasNoMeterRegistryTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "portal", "worker"})
    void noProcessHoldsAMeterRegistry(String process) {
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess(process),
                process);
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the %s process must start", process).isNotNull();
        try {
            assertThat(context.getBeansOfType(MeterRegistry.class)).isEmpty();
        } finally {
            context.close();
        }
    }
}

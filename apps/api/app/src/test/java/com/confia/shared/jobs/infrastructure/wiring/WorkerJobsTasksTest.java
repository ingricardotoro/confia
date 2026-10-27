package com.confia.shared.jobs.infrastructure.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.OneTimeJob;
import com.confia.shared.jobs.RetryPolicy;
import com.confia.shared.jobs.infrastructure.ConfiguredInstitutions;
import com.confia.shared.jobs.infrastructure.JobRunner;
import com.confia.shared.jobs.infrastructure.RecurringJobRunner;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Scenario BJ63: two job definitions with the same name stop the start of the worker. */
class WorkerJobsTasksTest {

    record Payload(UUID id) { }

    private static final RetryPolicy RETRY = new RetryPolicy(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1));
    private final JobRunner runner = new JobRunner(new TracedJobSerializer());
    private final RecurringJobRunner recurringRunner = new RecurringJobRunner(
            new ConfiguredInstitutions(List.of(new com.confia.kernel.InstitutionId(UUID.randomUUID()))), Clock.systemUTC());

    private static OneTimeJob<Payload> job(String name) {
        return new OneTimeJob<>(new JobType<>(name, Payload.class, RETRY), (payload, context) -> { });
    }

    @Test
    void oneTaskIsBuiltForEachDefinitionUnderItsName() {
        List<JobDefinition> definitions = List.of(job("identity.first-job"), job("identity.second-job"));

        assertThat(WorkerJobsConfiguration.tasksOf(definitions, runner, recurringRunner, Clock.systemUTC()))
                .extracting(task -> task.getName()).containsExactly("identity.first-job", "identity.second-job");
    }

    @Test
    void aRepeatedNameStopsTheStartNamingIt() {
        List<JobDefinition> definitions = List.of(job("identity.first-job"), job("identity.first-job"));

        assertThatThrownBy(() -> WorkerJobsConfiguration.tasksOf(definitions, runner, recurringRunner, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity.first-job");
    }

    @Test
    void noDefinitionsMeanNoTasks() {
        assertThat(WorkerJobsConfiguration.tasksOf(List.of(), runner, recurringRunner, Clock.systemUTC())).isEmpty();
    }
}

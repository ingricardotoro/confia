package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The declarations a module writes to describe its jobs, and the contexts a handler runs with
 * (background-jobs-with-db-scheduler design.md decisions 6 and 7). Nothing here is a bean and
 * nothing imports the scheduling library.
 */
class JobDefinitionsTest {

    record Payload(UUID id) { }

    private static final RetryPolicy RETRY =
            new RetryPolicy(3, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5));
    private static final UUID INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @ParameterizedTest
    @ValueSource(strings = {"identity.password-reset-issuance", "shared.purge-idempotency-keys", "a.b"})
    void aRecurringJobAcceptsAModuleQualifiedLowercaseName(String name) {
        assertThat(new RecurringJob(name, new JobCalendar.Every(Duration.ofSeconds(60)), (i, r) -> { }).name())
                .isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "single", "Identity.job", "identity..job", "identity.job name"})
    void aRecurringJobRejectsAnyOtherName(String name) {
        assertThatThrownBy(() -> new RecurringJob(name, new JobCalendar.Every(Duration.ofSeconds(60)), (i, r) -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aOneTimeJobTakesItsNameFromItsType() {
        JobType<Payload> type = new JobType<>("identity.job", Payload.class, RETRY);
        OneTimeJob<Payload> job = new OneTimeJob<>(type, (payload, context) -> { });

        assertThat(job.name()).isEqualTo("identity.job");
        assertThat(job.type()).isSameAs(type);
        assertThat(job).isInstanceOf(JobDefinition.class);
        assertThatThrownBy(() -> new OneTimeJob<>(null, (payload, context) -> { }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OneTimeJob<>(type, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRecurringJobRequiresItsCalendarAndItsHandler() {
        JobCalendar calendar = new JobCalendar.DailyAt(LocalTime.of(4, 30));

        assertThat(new RecurringJob("identity.job", calendar, (i, r) -> { })).isInstanceOf(JobDefinition.class);
        assertThatThrownBy(() -> new RecurringJob("identity.job", null, (i, r) -> { }))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecurringJob("identity.job", calendar, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theCalendarsRequireTheirFields() {
        assertThat(new JobCalendar.DailyAt(LocalTime.of(3, 0)).time()).isEqualTo(LocalTime.of(3, 0));
        assertThat(new JobCalendar.WeeklyAt(DayOfWeek.SUNDAY, LocalTime.of(3, 30)).day())
                .isEqualTo(DayOfWeek.SUNDAY);
        assertThat(new JobCalendar.Every(Duration.ofSeconds(60)).interval()).isEqualTo(Duration.ofSeconds(60));
        assertThatThrownBy(() -> new JobCalendar.DailyAt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobCalendar.WeeklyAt(null, LocalTime.NOON))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobCalendar.WeeklyAt(DayOfWeek.MONDAY, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobCalendar.Every(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobCalendar.Every(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JobCalendar.Every(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aOneTimeHandlerRunsAsTheSystemActorInTheInstitutionOfTheTask() {
        JobMeta meta = new JobMeta(UUID.randomUUID(), REQUEST, UUID.randomUUID(), ActorKind.STAFF, INSTITUTION,
                null);
        JobContext context = new JobContext("identity.job", JobInstanceId.derive("identity.job", REQUEST), meta, 2);

        SecurityContext security = context.systemContext();

        assertThat(security.actorId()).isEmpty();
        assertThat(security.actorKind()).isEqualTo("system");
        assertThat(security.institutionId()).isEqualTo(INSTITUTION.toString());
        assertThat(security.requestId()).isEqualTo(REQUEST.toString());
        assertThat(context.attempt()).isEqualTo(2);
    }

    @Test
    void aRecurringRunUsesItsRunIdAsTheRequestOfEveryInstitution() {
        UUID runId = UUID.randomUUID();
        RecurringRun run = new RecurringRun("identity.job", runId, Instant.parse("2026-10-25T09:30:00Z"));

        SecurityContext security = run.systemContextFor(new InstitutionId(INSTITUTION));

        assertThat(security.actorId()).isEmpty();
        assertThat(security.actorKind()).isEqualTo("system");
        assertThat(security.institutionId()).isEqualTo(INSTITUTION.toString());
        assertThat(security.requestId()).isEqualTo(runId.toString());
    }

    @Test
    void theContextsRequireTheirFields() {
        JobMeta meta = new JobMeta(UUID.randomUUID(), REQUEST, null, ActorKind.SYSTEM, INSTITUTION, null);
        JobInstanceId instance = JobInstanceId.derive("identity.job", REQUEST);

        assertThatThrownBy(() -> new JobContext(null, instance, meta, 1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobContext("identity.job", null, meta, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobContext("identity.job", instance, null, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new JobContext("identity.job", instance, meta, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecurringRun(null, UUID.randomUUID(), Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecurringRun("identity.job", null, Instant.EPOCH))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecurringRun("identity.job", UUID.randomUUID(), null))
                .isInstanceOf(NullPointerException.class);
    }
}

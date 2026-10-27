package com.confia.shared.jobs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.jobs.ActorKind;
import com.confia.shared.jobs.JobContext;
import com.confia.shared.jobs.JobInstanceId;
import com.confia.shared.jobs.JobMeta;
import com.confia.shared.jobs.JobType;
import com.confia.shared.jobs.OneTimeJob;
import com.confia.shared.jobs.RetryPolicy;
import com.confia.shared.jobs.TracedJob;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The runner of one execution (background-jobs-with-db-scheduler design.md decision 10; scenarios BJ16,
 * BJ54, BJ55 and BJ60): the MDC while the handler runs and none after, the strict conversion of the
 * payload, and the sanitized failure that carries no message and no cause.
 */
class JobRunnerTest {

    record Payload(UUID accountId) { }

    private static final String DISTINCTIVE = "valor-distintivo-55e1";
    private static final UUID CORRELATION = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000a7");
    private static final String INSTANCE = "0123456789abcdef0123456789abcdef";
    private static final JobType<Payload> TYPE = new JobType<>("identity.runner-probe", Payload.class,
            new RetryPolicy(3, Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1)));

    private final TracedJobSerializer serializer = new TracedJobSerializer();
    private final JobRunner runner = new JobRunner(serializer);

    private static TracedJob<?> data(Object payload) {
        return new TracedJob<>(payload, new JobMeta(CORRELATION, REQUEST, ACTOR, ActorKind.STAFF, INSTITUTION, null));
    }

    private TracedJob<?> storedForm(String payloadJson) {
        String meta = "{\"correlationId\":\"" + CORRELATION + "\",\"requestId\":\"" + REQUEST + "\",\"actorId\":null,"
                + "\"actorKind\":\"STAFF\",\"institutionId\":\"" + INSTITUTION + "\",\"traceparent\":null}";
        return serializer.deserialize(TYPE.name(),
                ("{\"payload\":" + payloadJson + ",\"meta\":" + meta + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void theHandlerReceivesTheConvertedPayloadAndAContextBuiltFromTheEnvelope() {
        List<Payload> payloads = new ArrayList<>();
        List<JobContext> contexts = new ArrayList<>();
        OneTimeJob<Payload> job = new OneTimeJob<>(TYPE, (payload, context) -> {
            payloads.add(payload);
            contexts.add(context);
        });

        runner.run(job, INSTANCE, storedForm("{\"accountId\":\"" + ACCOUNT + "\"}"), 2);

        assertThat(payloads).containsExactly(new Payload(ACCOUNT));
        JobContext context = contexts.get(0);
        assertThat(context.taskName()).isEqualTo("identity.runner-probe");
        assertThat(context.instance()).isEqualTo(new JobInstanceId(INSTANCE));
        assertThat(context.attempt()).isEqualTo(2);
        assertThat(context.meta().institutionId()).isEqualTo(INSTITUTION);
        assertThat(context.systemContext().actorKind()).isEqualTo("system");
    }

    @Test
    void theMdcCarriesTheContextWhileTheHandlerRunsAndIsEmptyAfterwards() {
        List<Map<String, String>> seen = new ArrayList<>();
        OneTimeJob<Payload> job = new OneTimeJob<>(TYPE, (payload, context) -> seen.add(MDC.getCopyOfContextMap()));
        MDC.put("leftover", "from-another-task");

        runner.run(job, INSTANCE, data(new Payload(ACCOUNT)), 1);

        assertThat(seen.get(0)).containsEntry("jobTask", "identity.runner-probe")
                .containsEntry("jobInstance", INSTANCE).containsEntry("correlationId", CORRELATION.toString())
                .containsEntry("requestId", REQUEST.toString()).containsEntry("institutionId", INSTITUTION.toString())
                .containsEntry("actorKind", "system").doesNotContainKey("actorId");
        assertThat(MDC.getCopyOfContextMap()).as("cleared in finally").isNullOrEmpty();
    }

    @Test
    void theMdcIsClearedWhenTheHandlerFails() {
        OneTimeJob<Payload> job = new OneTimeJob<>(TYPE, (payload, context) -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> runner.run(job, INSTANCE, data(new Payload(ACCOUNT)), 1))
                .isInstanceOf(JobExecutionFailedException.class);

        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void aFailureKeepsTheTaskTheClassAndTheSqlStateAndNothingElse() {
        OneTimeJob<Payload> job = new OneTimeJob<>(TYPE, (payload, context) -> {
            throw new IllegalStateException("failed for " + DISTINCTIVE,
                    new RuntimeException("wrapper", new SQLException("Key (email)=(" + DISTINCTIVE + ")", "23505")));
        });

        assertThatThrownBy(() -> runner.run(job, INSTANCE, data(new Payload(ACCOUNT)), 1))
                .isInstanceOfSatisfying(JobExecutionFailedException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("job 'identity.runner-probe' failed: "
                            + "java.lang.IllegalStateException (SQLState 23505)");
                    assertThat(e.getCause()).isNull();
                    assertThat(e.getSuppressed()).isEmpty();
                    assertThat(e.toString()).doesNotContain(DISTINCTIVE);
                    assertThat(e.taskName()).isEqualTo("identity.runner-probe");
                    assertThat(e.originalClass()).isEqualTo("java.lang.IllegalStateException");
                    assertThat(e.sqlState()).isEqualTo("23505");
                });
    }

    @Test
    void aFailureWithoutADatabaseErrorHasNoSqlState() {
        OneTimeJob<Payload> job = new OneTimeJob<>(TYPE, (payload, context) -> {
            throw new IllegalArgumentException("x");
        });

        assertThatThrownBy(() -> runner.run(job, INSTANCE, data(new Payload(ACCOUNT)), 1))
                .isInstanceOfSatisfying(JobExecutionFailedException.class, e -> {
                    assertThat(e.sqlState()).isNull();
                    assertThat(e.getMessage()).isEqualTo(
                            "job 'identity.runner-probe' failed: java.lang.IllegalArgumentException");
                });
    }

    @Test
    void anUnknownFieldInThePayloadFailsBeforeTheHandlerIsCalled() {
        List<Payload> payloads = new ArrayList<>();
        OneTimeJob<Payload> job = new OneTimeJob<>(TYPE, (payload, context) -> payloads.add(payload));

        assertThatThrownBy(() -> runner.run(job, INSTANCE,
                storedForm("{\"accountId\":\"" + ACCOUNT + "\",\"extra\":\"" + DISTINCTIVE + "\"}"), 1))
                .isInstanceOfSatisfying(JobExecutionFailedException.class, e -> {
                    assertThat(e.originalClass()).startsWith("com.confia.shared.jobs.infrastructure.JobData");
                    assertThat(e.toString()).doesNotContain(DISTINCTIVE);
                });
        assertThat(payloads).isEmpty();
    }

    @Test
    void aMalformedInstanceIdFailsVisiblyAndNeverReachesTheHandler() {
        List<Payload> payloads = new ArrayList<>();
        OneTimeJob<Payload> job = new OneTimeJob<>(TYPE, (payload, context) -> payloads.add(payload));

        assertThatThrownBy(() -> runner.run(job, "not-hex", data(new Payload(ACCOUNT)), 1))
                .isInstanceOf(JobExecutionFailedException.class);
        assertThat(payloads).isEmpty();
    }
}

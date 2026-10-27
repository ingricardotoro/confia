package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.TransactionalDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The refusals of {@link DbSchedulerJobScheduler} that happen before the database is touched
 * (background-jobs-with-db-scheduler design.md decision 6; scenarios BJ43, BJ52 and BJ53), checked
 * in the order the adapter promises. The data source points at a port nothing listens on, so a
 * refusal that reached the database would fail with a connection error instead of the expected one.
 * The refusals that need the engine (the institution of the transaction, an instance that already
 * exists) are in {@code JobSchedulingGuardsIT}.
 */
class JobSchedulingGuardsTest {

    record Allowed(UUID accountId) { }

    record ForbiddenText(String email) { }

    private static final RetryPolicy RETRY = new RetryPolicy(3, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5));
    private static final JobType<Allowed> ALLOWED_TYPE = new JobType<>("identity.guard-probe", Allowed.class, RETRY);
    private static final JobType<ForbiddenText> TEXT_TYPE =
            new JobType<>("identity.guard-text-probe", ForbiddenText.class, RETRY);
    private static final UUID INSTITUTION = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final TracedJobSerializer serializer = new TracedJobSerializer();
    private final DbSchedulerJobScheduler scheduler = new DbSchedulerJobScheduler(
            new TransactionalDataSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never")),
            serializer);

    @AfterEach
    void leaveNoTransactionFlagBehind() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static JobMeta meta(String traceparent) {
        return new JobMeta(UUID.randomUUID(), REQUEST, null, ActorKind.STAFF, INSTITUTION, traceparent);
    }

    private static JobInstanceId instance() {
        return JobInstanceId.derive("identity.guard-probe", INSTITUTION, REQUEST);
    }

    @Test
    void schedulingOutsideATransactionIsRefusedAndNamesTheJob() {
        assertThatThrownBy(() -> scheduler.schedule(ALLOWED_TYPE, instance(), new Allowed(UUID.randomUUID()),
                meta(null), Instant.now()))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schedule the job identity.guard-probe")
                .hasMessageContaining("outside a transaction");
    }

    @Test
    void theTransactionIsCheckedBeforeThePayload() {
        assertThatThrownBy(() -> scheduler.schedule(TEXT_TYPE, instance(), new ForbiddenText("a@example.org"),
                meta(null), Instant.now())).isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void aPayloadWithATextComponentIsRefusedInsideATransactionBeforeTheDatabaseIsTouched() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> scheduler.schedule(TEXT_TYPE, instance(), new ForbiddenText("a@example.org"),
                meta(null), Instant.now()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'email'")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("a@example.org"));
    }

    @Test
    void everyArgumentIsRequired() {
        Allowed payload = new Allowed(UUID.randomUUID());
        Instant now = Instant.now();

        assertThatThrownBy(() -> scheduler.schedule(null, instance(), payload, meta(null), now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.schedule(ALLOWED_TYPE, null, payload, meta(null), now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.schedule(ALLOWED_TYPE, instance(), null, meta(null), now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.schedule(ALLOWED_TYPE, instance(), payload, null, now))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scheduler.schedule(ALLOWED_TYPE, instance(), payload, meta(null), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theConstructorRequiresItsParts() {
        assertThatThrownBy(() -> new DbSchedulerJobScheduler(null, serializer))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DbSchedulerJobScheduler(
                new TransactionalDataSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never")),
                null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theAdapterWritesThroughTheOneProxyItWasGiven() {
        TransactionalDataSource holder =
                new TransactionalDataSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never"));

        assertThat(new DbSchedulerJobScheduler(holder, serializer).dataSource()).isSameAs(holder.proxy());
    }
}

package com.confia.shared.jobs;

import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The recurring job the integration tests register in the worker harness, and the reads and the push
 * they need on its row of {@code scheduled_tasks}. Its calendar is an hour, so no occurrence happens
 * by itself during a test: {@link #runNow} moves the next execution into the past, and the occurrence
 * that follows is the one the test observes.
 */
final class RecurringFixtures {

    /** What the handler saw in one institution during one occurrence. */
    record Seen(UUID institution, UUID runId, String correlationId, String requestId, String actorKind,
            String mdcInstitution) { }

    static final String NAME = "shared.recurring-probe";
    static final CopyOnWriteArrayList<Seen> SEEN = new CopyOnWriteArrayList<>();
    static final AtomicInteger RUNNING = new AtomicInteger();
    static final AtomicInteger MAX_CONCURRENT = new AtomicInteger();
    static volatile UUID failFor;
    static volatile long holdMillis;

    private RecurringFixtures() {
    }

    /** Forgets what the last test saw and deletes the row of the probe, so no test starts from another's state. */
    static void reset() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "delete from scheduled_tasks where task_name = ?")) {
            statement.setString(1, NAME);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        SEEN.clear();
        RUNNING.set(0);
        MAX_CONCURRENT.set(0);
        failFor = null;
        holdMillis = 0;
    }

    @Configuration(proxyBeanMethods = false)
    static class Registered {

        @Bean
        JobDefinition recurringProbe() {
            return new RecurringJob(NAME, new JobCalendar.Every(Duration.ofHours(1)), (institution, run) -> {
                int now = RUNNING.incrementAndGet();
                MAX_CONCURRENT.accumulateAndGet(now, Math::max);
                try {
                    SEEN.add(new Seen(institution.value(), run.runId(), MDC.get("correlationId"),
                            MDC.get("requestId"), MDC.get("actorKind"), MDC.get("institutionId")));
                    if (holdMillis > 0) {
                        Thread.sleep(holdMillis);
                    }
                    if (institution.value().equals(failFor)) {
                        throw new IllegalStateException("the probe fails for this institution");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    RUNNING.decrementAndGet();
                }
            });
        }
    }

    /**
     * A fixed delay runs its first occurrence at startup (the library's initial execution time is
     * now). Waits for that occurrence to finish in each of {@code institutions}, checks the next one is
     * an hour away, and forgets what it saw, so a test starts from a settled row.
     */
    static void settleStartupRun(int institutions) {
        Eventually.within(Duration.ofSeconds(60), () -> {
            org.assertj.core.api.Assertions.assertThat(SEEN).hasSize(institutions);
            State state = state();
            org.assertj.core.api.Assertions.assertThat(state).isNotNull();
            org.assertj.core.api.Assertions.assertThat(state.picked()).isFalse();
            org.assertj.core.api.Assertions.assertThat(state.executionTime())
                    .isAfter(Instant.now().plus(Duration.ofMinutes(50)));
        });
        SEEN.clear();
    }

    /** Moves the next occurrence of the probe one second into the past, so the next poll takes it. */
    static void runNow() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "update scheduled_tasks set execution_time = now() - interval '1 second' "
                                + "where task_name = ? and picked = false")) {
            statement.setString(1, NAME);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The row of the probe: its failures and when it runs next, or {@code null} while it does not exist. */
    record State(int consecutiveFailures, Instant executionTime, boolean picked, long rows) { }

    static State state() {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select consecutive_failures, execution_time, picked, count(*) over () "
                                + "from scheduled_tasks where task_name = ?")) {
            statement.setString(1, NAME);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? new State(result.getInt(1), result.getTimestamp(2).toInstant(),
                        result.getBoolean(3), result.getLong(4)) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<UUID> institutionsSeen() {
        return SEEN.stream().map(Seen::institution).toList();
    }
}

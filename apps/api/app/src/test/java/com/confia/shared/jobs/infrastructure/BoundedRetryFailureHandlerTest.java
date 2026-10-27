package com.confia.shared.jobs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.confia.shared.jobs.RetryPolicy;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.ExecutionOperations;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The failure policy of a one-time job (background-jobs-with-db-scheduler design.md decision 9;
 * scenarios BJ22, BJ23 and BJ59): it reschedules while attempts remain, parks when they are spent, and
 * never removes the execution.
 */
class BoundedRetryFailureHandlerTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final RetryPolicy POLICY = new RetryPolicy(3, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(5));

    private final BoundedRetryFailureHandler<Object> handler = new BoundedRetryFailureHandler<>(
            "identity.some-job", POLICY, Clock.fixed(NOW, ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private final ExecutionOperations<Object> operations = mock(ExecutionOperations.class);

    private static ExecutionComplete failureAfter(int previousFailures) {
        Execution execution = new Execution(NOW, new TaskInstance<>("identity.some-job", "x"), true, "w", null,
                null, previousFailures, null, 1L);
        return ExecutionComplete.failure(execution, NOW, NOW, new IllegalStateException("x"));
    }

    @Test
    void theFirstFailureReschedulesAfterTheFirstDelay() {
        ExecutionComplete complete = failureAfter(0);

        handler.onFailure(complete, operations);

        verify(operations).reschedule(complete, NOW.plusSeconds(30));
        verifyNoMoreInteractions(operations);
    }

    @Test
    void theSecondFailureWaitsTheDoubledDelay() {
        ExecutionComplete complete = failureAfter(1);

        handler.onFailure(complete, operations);

        verify(operations).reschedule(complete, NOW.plusSeconds(60));
    }

    @Test
    void theLastAttemptParksTheExecutionAndNeverRemovesIt() {
        ExecutionComplete complete = failureAfter(2);

        handler.onFailure(complete, operations);

        verify(operations).reschedule(complete, ParkedExecutions.PARKED_AT);
        verify(operations, never()).remove();
        verify(operations, never()).stop();
        verifyNoMoreInteractions(operations);
    }

    @Test
    void theParkingInstantIsTheYear9999AndIsRecognized() {
        assertThat(ParkedExecutions.PARKED_AT).isEqualTo(Instant.parse("9999-01-01T00:00:00Z"));
        assertThat(ParkedExecutions.isParked(ParkedExecutions.PARKED_AT)).isTrue();
        assertThat(ParkedExecutions.isParked(ParkedExecutions.PARKED_AT.minusSeconds(1))).isFalse();
        assertThat(ParkedExecutions.isParked(NOW)).isFalse();
    }
}

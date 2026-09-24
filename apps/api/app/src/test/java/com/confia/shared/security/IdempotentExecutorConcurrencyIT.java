package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bounded {@code lock_timeout} of {@link IdempotentExecutor} under real concurrency, and its
 * three distinguishable outcomes (design.md, decision 4, decision 5 and decision 6;
 * specs/build-integrity/spec.md, requirement "Espera acotada ante escritura concurrente del
 * marcador, con salidas distinguibles por {@code SQLState}"). PR C2c.
 *
 * <p>Two threads, each with its own {@link TransactionRunner}, its own connection (the pool is never
 * pinned to one connection here — unlike {@code TransactionRunnerContextIT}, this class needs two
 * genuinely independent backends to race), and its own {@link IdempotentExecutor}, synchronized with
 * a {@link CyclicBarrier} exactly like {@link TransactionRunnerRetryIT}: never a wall-clock race to
 * decide who reaches the critical section first, only to decide how long the winner then holds its
 * transaction open — the same pattern {@code JooqIdempotencyRecordStoreIT}'s own wait-exhaustion
 * scenario already established at the adapter level (task 2.4). Thread A always writes the marker
 * first, reaches the barrier from inside its own use case (proving the {@code INSERT} already ran),
 * and thread B waits on the very same barrier immediately before it ever calls {@link
 * IdempotentExecutor#execute}, so B's own attempt can only start once A's insert already happened.
 *
 * <p>Every scenario builds its two {@link IdempotentExecutor} instances with an explicit, non-default
 * {@code lockWait} (tasks.md, task 5.2) — never the constructor's 250&nbsp;ms default — because the
 * scenario itself is what has to bound the race, not an implicit constant shared with production.
 */
class IdempotentExecutorConcurrencyIT extends CommittingPostgresIntegrationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String ENDPOINT = "/api/v1/payments";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration RETENTION = Duration.ofHours(24);

    private IdempotencyRecordStore store() {
        return new JooqIdempotencyRecordStore(dsl);
    }

    private IdempotentExecutor newExecutor(Duration lockWait) {
        return new IdempotentExecutor(newIndependentRunner(), store(), new RequestPayloadHasher(),
                FIXED_CLOCK, dataSource(), lockWait, RETENTION);
    }

    private IdempotentExecutor newExecutor(IdempotencyRecordStore store, Duration lockWait) {
        return new IdempotentExecutor(newIndependentRunner(), store, new RequestPayloadHasher(),
                FIXED_CLOCK, dataSource(), lockWait, RETENTION);
    }

    private TransactionRunner newIndependentRunner() {
        return new TransactionRunner(transactionManager(), dataSource());
    }

    /**
     * Scenario (a) of the requirement: the first transaction stays open past the second's tiny
     * {@code lockWait}. The second must receive {@link IdempotencyConflictException} with {@link
     * IdempotencyConflictException.Reason#WAIT_EXHAUSTED}, distinguishable by {@code SQLState}
     * ({@code 55P03}) from the duplicate-key outcome, without having written its own marker nor
     * invoked its use case, and the first must continue and complete without interference.
     */
    @Test
    void secondRequestFailsWithWaitExhaustionWhileTheFirstStaysOpen() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"10.0000\"}");
        IdempotentResponse responseA = new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"a\"}"));

        CyclicBarrier markerWrittenByA = new CyclicBarrier(2);
        AtomicInteger insertAttemptsByB = new AtomicInteger();
        IdempotencyRecordStore countingStoreForB = countingInsertAttempts(store(), insertAttemptsByB);

        IdempotentExecutor executorA = newExecutor(Duration.ofSeconds(5));
        IdempotentExecutor executorB = newExecutor(countingStoreForB, Duration.ofMillis(150));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotentOutcome> futureA = pool.submit(() -> executorA.execute(
                    contextOf(institutionId), key, payload, () -> {
                        awaitUninterruptibly(markerWrittenByA);
                        sleepUninterruptibly(1000); // well past B's 150 ms lockWait
                        return responseA;
                    }));

            int[] invocationsByB = new int[1];
            Future<IdempotentOutcome> futureB = pool.submit(() -> {
                awaitUninterruptibly(markerWrittenByA);
                return executorB.execute(contextOf(institutionId), key, payload, () -> {
                    invocationsByB[0]++;
                    return responseA;
                });
            });

            assertThat(futureA.get(15, TimeUnit.SECONDS))
                    .as("the first transaction must complete without interference")
                    .isInstanceOf(IdempotentOutcome.Executed.class);

            assertThatThrownBy(() -> futureB.get(15, TimeUnit.SECONDS).response())
                    .hasCauseInstanceOf(IdempotencyConflictException.class);

            assertThat(invocationsByB[0])
                    .as("the second request must never invoke its own use case on wait exhaustion")
                    .isEqualTo(0);
            assertThat(insertAttemptsByB.get())
                    .as("the second request must attempt its own INSERT exactly once — a retry of "
                            + "the transactional body would attempt it again")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Scenario (b) of the requirement: the first transaction commits inside the second's (large)
     * lock window. The second must receive the exact response the first stored, through the replay
     * path (T3, a brand new read-only transaction opened after the {@code 23505} collision aborts the
     * second's own T1), without ever invoking its own use case.
     */
    @Test
    void secondRequestReplaysTheFirstsResponseWhenTheFirstCommitsWithinTheWindow() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"20.0000\"}");
        IdempotentResponse responseA = new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"b\"}"));

        CyclicBarrier markerWrittenByA = new CyclicBarrier(2);
        AtomicInteger insertAttemptsByB = new AtomicInteger();
        IdempotencyRecordStore countingStoreForB = countingInsertAttempts(store(), insertAttemptsByB);

        IdempotentExecutor executorA = newExecutor(Duration.ofSeconds(5));
        IdempotentExecutor executorB = newExecutor(countingStoreForB, Duration.ofSeconds(5));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotentOutcome> futureA = pool.submit(() -> executorA.execute(
                    contextOf(institutionId), key, payload, () -> {
                        awaitUninterruptibly(markerWrittenByA);
                        // A short, deterministic hold — not a race decider (the barrier already is
                        // one) — so B's own INSERT genuinely collides with A's still-uncommitted row
                        // instead of B's earlier lockExisting finding an already-committed one; well
                        // inside B's five-second lockWait.
                        sleepUninterruptibly(300);
                        return responseA;
                    }));

            int[] invocationsByB = new int[1];
            Future<IdempotentOutcome> futureB = pool.submit(() -> {
                awaitUninterruptibly(markerWrittenByA);
                return executorB.execute(contextOf(institutionId), key, payload, () -> {
                    invocationsByB[0]++;
                    return new IdempotentResponse(500, JSON_MAPPER.readTree("{}"));
                });
            });

            assertThat(futureA.get(15, TimeUnit.SECONDS))
                    .isInstanceOf(IdempotentOutcome.Executed.class);

            IdempotentOutcome outcomeB = futureB.get(15, TimeUnit.SECONDS);
            assertThat(outcomeB)
                    .as("the second request must be told this is a replay, not a fresh execution")
                    .isInstanceOf(IdempotentOutcome.Replayed.class);
            assertThat(outcomeB.response())
                    .as("the second request must receive exactly the first's stored response")
                    .isEqualTo(responseA);
            assertThat(invocationsByB[0])
                    .as("the second request must never invoke its own use case on replay")
                    .isEqualTo(0);
            assertThat(insertAttemptsByB.get())
                    .as("the second request must attempt its own INSERT exactly once")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Scenario (c) of the requirement: the first transaction rolls back inside the second's lock
     * window. The primary key is free again once the first's abort completes, so the second's own
     * {@code INSERT} succeeds and it executes its own use case.
     */
    @Test
    void secondRequestInsertsAndExecutesWhenTheFirstRollsBackWithinTheWindow() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"30.0000\"}");
        IdempotentResponse responseB = new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"c\"}"));

        CyclicBarrier markerWrittenByA = new CyclicBarrier(2);
        IdempotentExecutor executorA = newExecutor(Duration.ofSeconds(5));
        IdempotentExecutor executorB = newExecutor(Duration.ofSeconds(5));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IdempotentOutcome> futureA = pool.submit(() -> executorA.execute(
                    contextOf(institutionId), key, payload, () -> {
                        awaitUninterruptibly(markerWrittenByA);
                        throw new IllegalStateException(
                                "deterministic business failure, rolls the marker back with it");
                    }));

            int[] invocationsByB = new int[1];
            Future<IdempotentOutcome> futureB = pool.submit(() -> {
                awaitUninterruptibly(markerWrittenByA);
                return executorB.execute(contextOf(institutionId), key, payload, () -> {
                    invocationsByB[0]++;
                    return responseB;
                });
            });

            assertThatThrownBy(() -> futureA.get(15, TimeUnit.SECONDS))
                    .as("the first transaction must roll back with the deterministic failure")
                    .hasCauseInstanceOf(IllegalStateException.class);

            assertThat(futureB.get(15, TimeUnit.SECONDS))
                    .as("the key is free once the first rolled back: the second inserts and executes")
                    .isInstanceOf(IdempotentOutcome.Executed.class);
            assertThat(invocationsByB[0])
                    .as("the second request's use case must run exactly once")
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Task 5.4. The counter that actually distinguishes "did not retry" from "retried and failed
     * again the same way" — neither of the three scenarios above can prove this by exception type
     * alone, and the caller's own use-case invocation count cannot either (tasks.md, task 5.4): along
     * both the wait-exhaustion and the duplicate-key paths, {@link IdempotentExecutor} never reaches
     * the caller's use case at all, retried or not, so a counter inside it would read zero either
     * way. What has to be counted is how many times {@link TransactionRunner} invokes the
     * transactional body it was given — exercised here directly, with the exact two exception types
     * the adapter's {@code translate(...)} produces (design.md, decision 5), never by message text.
     * Expected green without any production change: the separation already exists in {@code
     * TransactionRunner.isRetryable(...)} since change 5, and task 2.5's wrapper sustains it — this
     * test is the counter-based proof that {@code TransactionRunnerRetryIT}'s own real-retry
     * scenarios do not already make true by coincidence.
     */
    @Test
    void transactionRunnerNeverRetriesWaitExhaustion() {
        AtomicInteger attempts = new AtomicInteger();
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());

        assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionId), () -> {
            attempts.incrementAndGet();
            throw new IdempotencyConflictException(IdempotencyConflictException.Reason.WAIT_EXHAUSTED,
                    new SQLException("canceling statement due to lock timeout", "55P03"));
        })).isInstanceOf(IdempotencyConflictException.class);

        assertThat(attempts.get())
                .as("exactly one attempt: a wait-exhaustion outcome must never be retried")
                .isEqualTo(1);
    }

    @Test
    void transactionRunnerNeverRetriesDuplicateKeyCollision() {
        AtomicInteger attempts = new AtomicInteger();
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());

        assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionId), () -> {
            attempts.incrementAndGet();
            throw new IdempotencyMarkerAlreadyExists(new SQLException(
                    "duplicate key value violates unique constraint", "23505"));
        })).isInstanceOf(IdempotencyMarkerAlreadyExists.class);

        assertThat(attempts.get())
                .as("exactly one attempt: a duplicate-key collision must never be retried")
                .isEqualTo(1);
    }

    private static void awaitUninterruptibly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("both sides of the race must reach the barrier", e);
        }
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }

    private static IdempotencyRecordStore countingInsertAttempts(IdempotencyRecordStore delegate,
            AtomicInteger counter) {
        return new IdempotencyRecordStore() {
            @Override
            public Optional<IdempotencyRecord> lockExisting(InstitutionId institutionId,
                    IdempotencyKey key) {
                return delegate.lockExisting(institutionId, key);
            }

            @Override
            public void insertInProgress(InstitutionId institutionId, IdempotencyKey key,
                    String requestHash, Instant createdAt, Instant expiresAt) {
                counter.incrementAndGet();
                delegate.insertInProgress(institutionId, key, requestHash, createdAt, expiresAt);
            }

            @Override
            public void restartExpired(InstitutionId institutionId, IdempotencyKey key,
                    String requestHash, Instant expiresAt) {
                delegate.restartExpired(institutionId, key, requestHash, expiresAt);
            }

            @Override
            public void complete(InstitutionId institutionId, IdempotencyKey key,
                    IdempotentResponse response, Instant completedAt) {
                delegate.complete(institutionId, key, response, completedAt);
            }
        };
    }
}

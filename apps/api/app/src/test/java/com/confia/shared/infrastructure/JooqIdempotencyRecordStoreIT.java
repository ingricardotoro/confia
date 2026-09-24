package com.confia.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.IdempotencyConflictException;
import com.confia.shared.security.IdempotencyKey;
import com.confia.shared.security.IdempotencyMarkerAlreadyExists;
import com.confia.shared.security.IdempotencyRecord;
import com.confia.shared.security.IdempotencyRecordStore;
import com.confia.shared.security.IdempotentResponse;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single jOOQ adapter of {@link IdempotencyRecordStore} (design.md, decision 5 and section
 * 6.3): the four port methods against a real PostgreSQL, and the two {@code SQLState} translations
 * — {@code 23505} and {@code 55P03} — proven by code, never by message text. No concurrency
 * scenario over the marker's own {@code lock_timeout} yet: that is C2c's own
 * {@code IdempotentExecutorConcurrencyIT}, once {@link com.confia.shared.security.IdempotentExecutor}
 * exists to set it as the first statement of its transaction (design.md, decision 4). Here, the
 * wait-exhaustion scenario sets {@code lock_timeout} directly to exercise only the adapter's own
 * translation, not the executor's ownership of that statement.
 */
class JooqIdempotencyRecordStoreIT extends CommittingPostgresIntegrationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String VALID_REQUEST_HASH = "0".repeat(64);
    private static final String ENDPOINT = "/api/v1/payments";

    private IdempotencyRecordStore store() {
        return new JooqIdempotencyRecordStore(dsl);
    }

    @Test
    void insertInProgressIsVisibleThroughLockExisting() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        Instant createdAt = truncatedNow();
        Instant expiresAt = createdAt.plus(24, ChronoUnit.HOURS);

        transactionRunner().execute(contextOf(institutionId), () -> {
            store().insertInProgress(institutionId, key, VALID_REQUEST_HASH, createdAt, expiresAt);
            return null;
        });

        Optional<IdempotencyRecord> found = transactionRunner().execute(contextOf(institutionId),
                () -> store().lockExisting(institutionId, key));

        assertThat(found).isPresent();
        IdempotencyRecord record = found.orElseThrow();
        assertThat(record.status()).isEqualTo("IN_PROGRESS");
        assertThat(record.requestHash()).isEqualTo(VALID_REQUEST_HASH);
        assertThat(record.createdAt()).isEqualTo(createdAt);
        assertThat(record.expiresAt()).isEqualTo(expiresAt);
        assertThat(record.responseStatus()).isNull();
        assertThat(record.responseBody()).isNull();
        assertThat(record.completedAt()).isNull();
    }

    @Test
    void lockExistingIsAbsentForAnUnknownKey() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());

        Optional<IdempotencyRecord> found = transactionRunner().execute(contextOf(institutionId),
                () -> store().lockExisting(institutionId, key));

        assertThat(found).isEmpty();
    }

    @Test
    void completeStoresTheResponseAndMarksTheRowCompleted() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        Instant createdAt = truncatedNow();
        Instant expiresAt = createdAt.plus(24, ChronoUnit.HOURS);
        Instant completedAt = createdAt.plusSeconds(1);
        IdempotentResponse response =
                new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"abc\"}"));

        transactionRunner().execute(contextOf(institutionId), () -> {
            store().insertInProgress(institutionId, key, VALID_REQUEST_HASH, createdAt, expiresAt);
            store().complete(institutionId, key, response, completedAt);
            return null;
        });

        IdempotencyRecord record = transactionRunner().execute(contextOf(institutionId),
                () -> store().lockExisting(institutionId, key).orElseThrow());

        assertThat(record.status()).isEqualTo("COMPLETED");
        assertThat(record.responseStatus()).isEqualTo(201);
        assertThat(record.responseBody()).isEqualTo("{\"id\": \"abc\"}");
        assertThat(record.completedAt()).isEqualTo(completedAt);
        // created_at is untouched by complete(...): the same instant the marker was inserted with.
        assertThat(record.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void restartExpiredReusesTheRowWithoutChangingCreatedAt() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        Instant createdAt = truncatedNow();
        Instant firstExpiresAt = createdAt.plus(24, ChronoUnit.HOURS);
        Instant completedAt = createdAt.plusSeconds(1);
        IdempotentResponse response = new IdempotentResponse(200, JSON_MAPPER.readTree("{}"));

        transactionRunner().execute(contextOf(institutionId), () -> {
            store().insertInProgress(institutionId, key, VALID_REQUEST_HASH, createdAt,
                    firstExpiresAt);
            store().complete(institutionId, key, response, completedAt);
            return null;
        });

        String newHash = "1".repeat(64);
        Instant newExpiresAt = completedAt.plus(24, ChronoUnit.HOURS);
        transactionRunner().execute(contextOf(institutionId), () -> {
            store().restartExpired(institutionId, key, newHash, newExpiresAt);
            return null;
        });

        IdempotencyRecord record = transactionRunner().execute(contextOf(institutionId),
                () -> store().lockExisting(institutionId, key).orElseThrow());

        assertThat(record.status()).isEqualTo("IN_PROGRESS");
        assertThat(record.requestHash()).isEqualTo(newHash);
        assertThat(record.expiresAt()).isEqualTo(newExpiresAt);
        // Never DELETE+INSERT (docs/03 section 6.1 grants no DELETE): created_at survives the reuse.
        assertThat(record.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void insertInProgressTranslatesADuplicateKeyBySqlStateNeverByMessageText() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        Instant createdAt = truncatedNow();
        Instant expiresAt = createdAt.plus(24, ChronoUnit.HOURS);

        transactionRunner().execute(contextOf(institutionId), () -> {
            store().insertInProgress(institutionId, key, VALID_REQUEST_HASH, createdAt, expiresAt);
            return null;
        });

        assertThatThrownBy(() -> transactionRunner().execute(contextOf(institutionId), () -> {
            store().insertInProgress(institutionId, key, VALID_REQUEST_HASH, createdAt, expiresAt);
            return null;
        }))
                .as("SQLState 23505 must translate to the internal marker-already-exists signal, "
                        + "never a generic jOOQ exception surfacing by message text")
                .isInstanceOf(IdempotencyMarkerAlreadyExists.class);
    }

    @Test
    void insertInProgressTranslatesWaitExhaustionBySqlStateNeverByMessageText() throws Exception {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        Instant createdAt = truncatedNow();
        Instant expiresAt = createdAt.plus(24, ChronoUnit.HOURS);
        CyclicBarrier bothSidesReady = new CyclicBarrier(2);

        TransactionRunner runnerA = new TransactionRunner(transactionManager(), dataSource());
        TransactionRunner runnerB = new TransactionRunner(transactionManager(), dataSource());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable[] caughtByB = new Throwable[1];
        int[] attemptsByB = new int[1];
        try {
            Future<?> a = pool.submit(() -> runnerA.execute(contextOf(institutionId), () -> {
                store().insertInProgress(institutionId, key, VALID_REQUEST_HASH, createdAt,
                        expiresAt);
                awaitUninterruptibly(bothSidesReady);
                sleepUninterruptibly(2000); // stays open well past B's tiny lock_timeout
                return null;
            }));
            Future<?> b = pool.submit(() -> {
                try {
                    return runnerB.execute(contextOf(institutionId), () -> {
                        attemptsByB[0]++;
                        dsl.execute("select set_config('lock_timeout', '100ms', true)");
                        awaitUninterruptibly(bothSidesReady);
                        store().insertInProgress(institutionId, key, VALID_REQUEST_HASH, createdAt,
                                expiresAt);
                        return null;
                    });
                } catch (RuntimeException e) {
                    caughtByB[0] = e;
                    throw e;
                }
            });

            a.get(15, TimeUnit.SECONDS);
            try {
                b.get(15, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
                // expected: B must fail with the wait-exhaustion translation
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(caughtByB[0])
                .as("SQLState 55P03 must translate to IdempotencyConflictException(WAIT_EXHAUSTED), "
                        + "distinguishable by code from the duplicate-key outcome, never by message "
                        + "text")
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(((IdempotencyConflictException) caughtByB[0]).reason())
                .isEqualTo(IdempotencyConflictException.Reason.WAIT_EXHAUSTED);
        assertThat(attemptsByB[0])
                .as("the transactional component must not retry a wait-exhaustion outcome")
                .isEqualTo(1);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }

    /** {@code TIMESTAMPTZ(6)} truncates beyond microseconds; matching here avoids a false mismatch. */
    private static Instant truncatedNow() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
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
}

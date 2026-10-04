package com.confia.shared.security;

import com.confia.kernel.InstitutionId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an idempotency marker and its business effect inside a single real transaction (design.md,
 * decision 6; specs/build-integrity/spec.md, requirement "Marcador y efecto de negocio en una única
 * transacción atómica"). {@code final}, with an explicit constructor and no Spring annotation — the
 * same pattern {@link TransactionRunner} and {@code JooqInstitutionRepository} already established:
 * the administrative process registers this as a bean in {@code SharedPlatformConfiguration}
 * (web-edge-foundations design.md, decision 2).
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): {@link #execute} delegates
 * every write to {@link TransactionRunner#execute(SecurityContext, Supplier)}, satisfying the rule
 * that confines transaction-opening code to {@code shared/security} by composition, not by an
 * exception carved out for this class.
 *
 * <p><b>PR C2c, the bounded wait and its two extra transactions</b> (design.md, decisions 1, 4, 6 and
 * 7). {@code lock_timeout} is bound as the very first statement of every transaction this component
 * opens ({@link #bindLockTimeout}), the literal pattern of {@link
 * TransactionRunner#applySecurityContext}: {@link DataSourceUtils#getConnection(DataSource)}, a
 * bound parameter, no jOOQ, no interpolation. When the primary-key {@code INSERT} collides with a
 * row a concurrent transaction already committed ({@code SQLState 23505}, translated by the adapter
 * to {@link IdempotencyMarkerAlreadyExists}), the transaction this component was inside is already
 * aborted, so {@link #execute} opens a second, read-only transaction ({@link
 * #replayInANewTransaction}) to reread the now-committed marker and return the original response.
 * {@link IdempotencyConflictException} with {@link IdempotencyConflictException.Reason#WAIT_EXHAUSTED}
 * ({@code SQLState 55P03}) propagates unchanged: the wait itself already happened, there is nothing
 * left to replay.
 */
public final class IdempotentExecutor {

    /** Design.md, decision 4. */
    private static final Duration DEFAULT_LOCK_WAIT = Duration.ofMillis(250);

    /** ADR-0010. */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final String SET_LOCK_TIMEOUT_SQL = "select set_config('lock_timeout', ?, true)";

    private final TransactionRunner runner;
    private final IdempotencyRecordStore store;
    private final RequestPayloadHasher hasher;
    private final Clock clock;
    private final DataSource dataSource;
    private final Duration lockWait;
    private final Duration retention;

    public IdempotentExecutor(TransactionRunner runner, IdempotencyRecordStore store,
            RequestPayloadHasher hasher, Clock clock, DataSource dataSource) {
        this(runner, store, hasher, clock, dataSource, DEFAULT_LOCK_WAIT, DEFAULT_RETENTION);
    }

    /**
     * @param dataSource the same pooled data source {@code TransactionRunner} opens its transactions
     *     against, needed to bind {@code lock_timeout} on the JDBC {@link Connection} already bound
     *     to the current transaction (design.md, decision 4; "por confirmar" point 6 — the design's
     *     original sketch in section 6.1 predates this need, elaborated here per task 5.3).
     * @param lockWait bound for the wait a colliding {@code SELECT ... FOR UPDATE} or {@code INSERT}
     *     may take before failing (design.md, decision 4).
     * @param retention how long a completed marker stays reusable before {@link
     *     IdempotencyRecordStore#restartExpired} treats it as caducated (design.md, decision 7).
     */
    public IdempotentExecutor(TransactionRunner runner, IdempotencyRecordStore store,
            RequestPayloadHasher hasher, Clock clock, DataSource dataSource, Duration lockWait,
            Duration retention) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.store = Objects.requireNonNull(store, "store");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.lockWait = Objects.requireNonNull(lockWait, "lockWait");
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    /**
     * Design.md, decision 6, the pseudocode flow. The hash is computed once, outside any
     * transaction — pure, over the {@link JsonNode} the caller already parsed, never over raw text
     * (design.md, decision 8).
     *
     * <p>{@code lock_timeout} is bound as the first statement of every transaction this method opens
     * — T1 here, and T3 inside {@link #replayInANewTransaction} — so the bound wait covers the whole
     * body, including the use case (design.md, decision 4). Only {@link
     * IdempotencyMarkerAlreadyExists} is caught: T1 is already aborted by the {@code 23505} that
     * raised it, so the only way to read the winner's committed response is a brand new transaction.
     * {@link IdempotencyConflictException} with {@link
     * IdempotencyConflictException.Reason#WAIT_EXHAUSTED} propagates unchanged — the wait already
     * happened, there is nothing left to replay (design.md, decision 6).
     */
    public IdempotentOutcome execute(SecurityContext context, IdempotencyKey key,
            JsonNode requestPayload, Supplier<IdempotentResponse> useCase) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(useCase, "useCase");
        String requestHash = hasher.hash(requestPayload);
        InstitutionId institutionId = institutionIdOf(context);

        try {
            return runner.execute(context, () -> {
                bindLockTimeout();
                Optional<IdempotencyRecord> existing = store.lockExisting(institutionId, key);

                if (existing.isEmpty()) {
                    return executeAndComplete(institutionId, key, requestHash, useCase, true);
                }

                IdempotencyRecord record = existing.get();
                if (isExpired(record)) {
                    return executeAndComplete(institutionId, key, requestHash, useCase, false);
                }

                if (!requestHash.equals(record.requestHash())) {
                    // The use case is never invoked: the requirement demands rejection be verifiable
                    // by a zero invocation count, not only by the exception type
                    // (specs/build-integrity/spec.md, "Rechazo de la misma clave con carga útil
                    // distinta...").
                    throw new IdempotencyPayloadMismatchException();
                }

                if (STATUS_COMPLETED.equals(record.status())) {
                    return new IdempotentOutcome.Replayed(toResponse(record));
                }

                // Defensive branch (design.md, section 6.1 flow): a marker confirmed IN_PROGRESS from
                // a transaction other than this one should never happen once the marker and its
                // business effect always share one transaction, but it is named rather than left to
                // fail unexplained if it ever does.
                throw new IdempotencyConflictException(
                        IdempotencyConflictException.Reason.MARKER_IN_PROGRESS);
            });
        } catch (IdempotencyMarkerAlreadyExists e) {
            return replayInANewTransaction(context, institutionId, key, requestHash);
        }
    }

    /**
     * T3 of design.md decision 6: opened only after a {@code 23505} collision aborted T1. The primary
     * key is now visible with the winner's committed row — reread it and either replay it or reject
     * it, the same checks T1 itself would have applied had it found the row present from the start.
     * No write happens here: the winner already completed the effect inside its own transaction.
     */
    private IdempotentOutcome replayInANewTransaction(SecurityContext context,
            InstitutionId institutionId, IdempotencyKey key, String requestHash) {
        return runner.execute(context, () -> {
            bindLockTimeout();
            IdempotencyRecord record = store.lockExisting(institutionId, key).orElseThrow(
                    () -> new IllegalStateException(
                            "the marker must exist after a duplicate-key collision on its own "
                                    + "primary key, but no row was found on reread"));

            if (!requestHash.equals(record.requestHash())) {
                throw new IdempotencyPayloadMismatchException();
            }
            if (STATUS_COMPLETED.equals(record.status())) {
                return new IdempotentOutcome.Replayed(toResponse(record));
            }
            throw new IdempotencyConflictException(
                    IdempotencyConflictException.Reason.MARKER_IN_PROGRESS);
        });
    }

    /**
     * The first statement of the transaction (design.md, decision 4), local to it via {@code
     * set_config(..., true)} so it never survives onto a connection the pool later hands to a
     * different transaction (sonda S5, design.md final section) — the literal pattern of {@link
     * TransactionRunner#applySecurityContext}.
     */
    private void bindLockTimeout() {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            try (PreparedStatement statement = connection.prepareStatement(SET_LOCK_TIMEOUT_SQL)) {
                statement.setString(1, lockWait.toMillis() + "ms");
                statement.execute();
            }
        } catch (SQLException e) {
            throw new UncategorizedSQLException("bind lock_timeout", SET_LOCK_TIMEOUT_SQL, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private IdempotentOutcome executeAndComplete(InstitutionId institutionId, IdempotencyKey key,
            String requestHash, Supplier<IdempotentResponse> useCase, boolean isNewKey) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(retention);
        if (isNewKey) {
            store.insertInProgress(institutionId, key, requestHash, now, expiresAt);
        } else {
            // Reuse of a caducated key is an UPDATE, never a DELETE+INSERT (docs/03-seguridad.md
            // section 6.1 grants no DELETE on this table; design.md, decision 7).
            store.restartExpired(institutionId, key, requestHash, expiresAt);
        }
        IdempotentResponse response = useCase.get();
        store.complete(institutionId, key, response, clock.instant());
        return new IdempotentOutcome.Executed(response);
    }

    /** Java's {@link Clock} decides caducity, never the engine's (design.md, decision 7). */
    private boolean isExpired(IdempotencyRecord record) {
        return !record.expiresAt().isAfter(clock.instant());
    }

    private static IdempotentResponse toResponse(IdempotencyRecord record) {
        return new IdempotentResponse(record.responseStatus(),
                JSON_MAPPER.readTree(record.responseBody()));
    }

    private static InstitutionId institutionIdOf(SecurityContext context) {
        return new InstitutionId(UUID.fromString(context.institutionId()));
    }
}

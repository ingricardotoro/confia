package com.confia.shared.security;

import com.confia.kernel.InstitutionId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes an idempotency marker and its business effect inside a single real transaction (design.md,
 * decision 6; specs/build-integrity/spec.md, requirement "Marcador y efecto de negocio en una única
 * transacción atómica"). {@code final}, with an explicit constructor and no Spring annotation — the
 * same pattern {@link TransactionRunner} and {@code JooqInstitutionRepository} already established:
 * no bootstrap process registers this as a bean yet.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): {@link #execute} delegates
 * every write to {@link TransactionRunner#execute(SecurityContext, Supplier)}, satisfying the rule
 * that confines transaction-opening code to {@code shared/security} by composition, not by an
 * exception carved out for this class.
 *
 * <p><b>PR C2b, no concurrency yet</b> (design.md, decisions 1, 6 and 7). This class implements the
 * four sequential paths a single caller can exercise inside one transaction: a new key, a use case
 * that fails after the marker is written (both writes roll back together), a replay of an
 * already-completed key, and the rejection of a different payload under the same key. It does
 * <strong>not yet</strong> bind {@code lock_timeout} as the first statement of its transaction, and
 * it does not yet catch {@link IdempotencyMarkerAlreadyExists} to open a second, read-only
 * transaction that replays a marker a concurrent transaction just committed — both are C2c's own
 * addition (design.md, decision 4; tasks.md, task 5.3), once {@code
 * IdempotentExecutorConcurrencyIT} exercises real concurrent collisions. Until then, {@link
 * IdempotencyMarkerAlreadyExists} and {@link IdempotencyConflictException} propagate unchanged out
 * of {@link #execute} — neither can occur along the sequential paths this cut tests.
 */
public final class IdempotentExecutor {

    /** Design.md, decision 4. Not yet applied to any transaction in this cut — see the class Javadoc. */
    private static final Duration DEFAULT_LOCK_WAIT = Duration.ofMillis(250);

    /** ADR-0010. */
    private static final Duration DEFAULT_RETENTION = Duration.ofHours(24);

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    private static final String STATUS_COMPLETED = "COMPLETED";

    private final TransactionRunner runner;
    private final IdempotencyRecordStore store;
    private final RequestPayloadHasher hasher;
    private final Clock clock;
    private final Duration lockWait;
    private final Duration retention;

    public IdempotentExecutor(TransactionRunner runner, IdempotencyRecordStore store,
            RequestPayloadHasher hasher, Clock clock) {
        this(runner, store, hasher, clock, DEFAULT_LOCK_WAIT, DEFAULT_RETENTION);
    }

    /**
     * @param lockWait bound for the wait a colliding {@code SELECT ... FOR UPDATE} or {@code INSERT}
     *     may take before failing (design.md, decision 4). Stored for C2c's own use; this cut does
     *     not yet bind it to any transaction (class Javadoc).
     * @param retention how long a completed marker stays reusable before {@link
     *     IdempotencyRecordStore#restartExpired} treats it as caducated (design.md, decision 7).
     */
    public IdempotentExecutor(TransactionRunner runner, IdempotencyRecordStore store,
            RequestPayloadHasher hasher, Clock clock, Duration lockWait, Duration retention) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.store = Objects.requireNonNull(store, "store");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lockWait = Objects.requireNonNull(lockWait, "lockWait");
        this.retention = Objects.requireNonNull(retention, "retention");
    }

    /**
     * Design.md, decision 6, the pseudocode flow (this cut's subset, class Javadoc). The hash is
     * computed once, outside any transaction — pure, over the {@link JsonNode} the caller already
     * parsed, never over raw text (design.md, decision 8).
     */
    public IdempotentOutcome execute(SecurityContext context, IdempotencyKey key,
            JsonNode requestPayload, Supplier<IdempotentResponse> useCase) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(useCase, "useCase");
        String requestHash = hasher.hash(requestPayload);
        InstitutionId institutionId = institutionIdOf(context);

        return runner.execute(context, () -> {
            Optional<IdempotencyRecord> existing = store.lockExisting(institutionId, key);

            if (existing.isEmpty()) {
                return executeAndComplete(institutionId, key, requestHash, useCase, true);
            }

            IdempotencyRecord record = existing.get();
            if (isExpired(record)) {
                return executeAndComplete(institutionId, key, requestHash, useCase, false);
            }

            if (!requestHash.equals(record.requestHash())) {
                // The use case is never invoked: the requirement demands rejection be verifiable by
                // a zero invocation count, not only by the exception type (specs/build-integrity/
                // spec.md, "Rechazo de la misma clave con carga útil distinta...").
                throw new IdempotencyPayloadMismatchException();
            }

            if (STATUS_COMPLETED.equals(record.status())) {
                return new IdempotentOutcome.Replayed(toResponse(record));
            }

            // Defensive branch (design.md, section 6.1 flow): a marker confirmed IN_PROGRESS from a
            // transaction other than this one should never happen once the marker and its business
            // effect always share one transaction, but it is named rather than left to fail
            // unexplained if it ever does.
            throw new IdempotencyConflictException(
                    IdempotencyConflictException.Reason.MARKER_IN_PROGRESS);
        });
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

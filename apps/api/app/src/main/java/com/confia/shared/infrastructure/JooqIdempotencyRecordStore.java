package com.confia.shared.infrastructure;

import static confia.generated.jooq.tables.SharedIdempotencyKey.SHARED_IDEMPOTENCY_KEY;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.IdempotencyConflictException;
import com.confia.shared.security.IdempotencyConflictException.Reason;
import com.confia.shared.security.IdempotencyKey;
import com.confia.shared.security.IdempotencyMarkerAlreadyExists;
import com.confia.shared.security.IdempotencyRecord;
import com.confia.shared.security.IdempotencyRecordStore;
import com.confia.shared.security.IdempotentResponse;
import confia.generated.jooq.tables.records.SharedIdempotencyKeyRecord;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;

/**
 * The single jOOQ adapter of {@link IdempotencyRecordStore} (design.md, decision 5; ADR-0015 rule
 * 4, R1: jOOQ confined to {@code infrastructure}; rule 3, R2: {@code SharedIdempotencyKey}'s
 * generated table type carries this module's own {@code Shared} prefix, confirmed by sonda S6).
 * {@code final}, with an explicit constructor over {@link DSLContext} and no Spring annotation, the
 * same pattern {@link com.confia.shared.infrastructure.JooqAuditLogReader} already established: the
 * administrative process registers this as a bean in {@code SharedPlatformConfiguration}
 * (web-edge-foundations design.md, decision 2).
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): every method assumes it
 * runs inside the transaction the single transactional component of {@code shared/security} already
 * opened, participating through the same {@link DSLContext} the caller's {@code
 * TransactionAwareDataSourceProxy}-backed data source binds to that transaction.
 *
 * <p><b>The only translation this class performs is by {@code SQLState}, walking the cause chain,
 * never by message text</b> (design.md, section 6.3): {@link #translate} recognizes {@code 23505}
 * (unique violation on the primary key — {@link IdempotencyMarkerAlreadyExists}) and {@code 55P03}
 * (lock wait exhausted — {@link IdempotencyConflictException} with {@link Reason#WAIT_EXHAUSTED}).
 * Neither returned type extends Spring's {@code ConcurrencyFailureException}, so {@code
 * TransactionRunner.isRetryable(...)} never retries either outcome even if a jOOQ exception
 * translator appeared later (design.md, decision 5).
 */
public final class JooqIdempotencyRecordStore implements IdempotencyRecordStore {

    private final DSLContext dsl;

    public JooqIdempotencyRecordStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<IdempotencyRecord> lockExisting(InstitutionId institutionId, IdempotencyKey key) {
        try {
            return dsl.selectFrom(SHARED_IDEMPOTENCY_KEY)
                    .where(SHARED_IDEMPOTENCY_KEY.INSTITUTION_ID.eq(institutionId.value()))
                    .and(SHARED_IDEMPOTENCY_KEY.ENDPOINT.eq(key.endpoint()))
                    .and(SHARED_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY.eq(key.value()))
                    .forUpdate()
                    .fetchOptional(JooqIdempotencyRecordStore::toRecord);
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public void insertInProgress(InstitutionId institutionId, IdempotencyKey key, String requestHash,
            Instant createdAt, Instant expiresAt) {
        try {
            dsl.insertInto(SHARED_IDEMPOTENCY_KEY)
                    .set(SHARED_IDEMPOTENCY_KEY.INSTITUTION_ID, institutionId.value())
                    .set(SHARED_IDEMPOTENCY_KEY.ENDPOINT, key.endpoint())
                    .set(SHARED_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY, key.value())
                    .set(SHARED_IDEMPOTENCY_KEY.REQUEST_HASH, requestHash)
                    .set(SHARED_IDEMPOTENCY_KEY.STATUS, "IN_PROGRESS")
                    .set(SHARED_IDEMPOTENCY_KEY.CREATED_AT, toOffsetDateTime(createdAt))
                    .set(SHARED_IDEMPOTENCY_KEY.EXPIRES_AT, toOffsetDateTime(expiresAt))
                    .execute();
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public void restartExpired(InstitutionId institutionId, IdempotencyKey key, String requestHash,
            Instant expiresAt) {
        try {
            dsl.update(SHARED_IDEMPOTENCY_KEY)
                    .set(SHARED_IDEMPOTENCY_KEY.REQUEST_HASH, requestHash)
                    .set(SHARED_IDEMPOTENCY_KEY.STATUS, "IN_PROGRESS")
                    .setNull(SHARED_IDEMPOTENCY_KEY.RESPONSE_STATUS)
                    .setNull(SHARED_IDEMPOTENCY_KEY.RESPONSE_BODY)
                    .setNull(SHARED_IDEMPOTENCY_KEY.COMPLETED_AT)
                    .set(SHARED_IDEMPOTENCY_KEY.EXPIRES_AT, toOffsetDateTime(expiresAt))
                    .where(SHARED_IDEMPOTENCY_KEY.INSTITUTION_ID.eq(institutionId.value()))
                    .and(SHARED_IDEMPOTENCY_KEY.ENDPOINT.eq(key.endpoint()))
                    .and(SHARED_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY.eq(key.value()))
                    .execute();
            // created_at is deliberately never touched here: reuse is an UPDATE, never a
            // DELETE+INSERT (docs/03 section 6.1 grants no DELETE on this table; design.md,
            // decision 7).
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    @Override
    public void complete(InstitutionId institutionId, IdempotencyKey key, IdempotentResponse response,
            Instant completedAt) {
        try {
            dsl.update(SHARED_IDEMPOTENCY_KEY)
                    .set(SHARED_IDEMPOTENCY_KEY.STATUS, "COMPLETED")
                    .set(SHARED_IDEMPOTENCY_KEY.RESPONSE_STATUS, response.responseStatus())
                    .set(SHARED_IDEMPOTENCY_KEY.RESPONSE_BODY,
                            JSONB.valueOf(response.responseBody().toString()))
                    .set(SHARED_IDEMPOTENCY_KEY.COMPLETED_AT, toOffsetDateTime(completedAt))
                    .where(SHARED_IDEMPOTENCY_KEY.INSTITUTION_ID.eq(institutionId.value()))
                    .and(SHARED_IDEMPOTENCY_KEY.ENDPOINT.eq(key.endpoint()))
                    .and(SHARED_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY.eq(key.value()))
                    .execute();
        } catch (RuntimeException e) {
            throw translate(e);
        }
    }

    private static IdempotencyRecord toRecord(SharedIdempotencyKeyRecord row) {
        return new IdempotencyRecord(row.getRequestHash(), row.getStatus(), row.getResponseStatus(),
                jsonbText(row.getResponseBody()), row.getCreatedAt().toInstant(),
                row.getCompletedAt() == null ? null : row.getCompletedAt().toInstant(),
                row.getExpiresAt().toInstant());
    }

    /** {@code null} exactly when the {@code jsonb} column itself is SQL {@code NULL}. */
    private static String jsonbText(JSONB value) {
        return value == null ? null : value.data();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * Never by message text: only by {@code SQLState}, walking the cause chain, exactly like {@code
     * TransactionRunner.isRetryable(...)} already does (design.md, section 6.3).
     */
    private static RuntimeException translate(RuntimeException original) {
        for (Throwable cause = original; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if ("23505".equals(sql.getSQLState())) {
                    return new IdempotencyMarkerAlreadyExists(original);
                }
                if ("55P03".equals(sql.getSQLState())) {
                    return new IdempotencyConflictException(Reason.WAIT_EXHAUSTED, original);
                }
            }
        }
        return original;
    }
}

package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityLoginBackoff.IDENTITY_LOGIN_BACKOFF;

import com.confia.identity.application.LoginBackoffStore;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record2;

/**
 * The single jOOQ adapter of {@link LoginBackoffStore} (design.md, decision 5; §10, sonda S3).
 * {@code final}, with an explicit constructor over {@link DSLContext} and no Spring annotation, the
 * same pattern {@code JooqIdempotencyRecordStore} already established: no bootstrap process
 * registers this as a bean yet.
 *
 * <p><b>{@link #claim} is the single reclaim statement design.md decision 5 fixes exactly</b>: an
 * {@code INSERT ... ON CONFLICT (institution_id, identifier_hash) DO UPDATE SET
 * consecutive_failures = identity_login_backoff.consecutive_failures RETURNING
 * consecutive_failures, last_attempt_at}. The {@code SET} assignment is deliberately idempotent —
 * it reassigns the row's own current value to itself — because its only purpose is to force the
 * {@code UPDATE} branch of {@code ON CONFLICT} to run (creating the row on the insert branch when
 * absent, locking the existing row on the update branch when present) so {@code RETURNING} hands
 * back the state that existed <b>before</b> this call, never the {@code (institution_id,
 * identifier_hash, 0, ?)} tuple the {@code VALUES} clause proposes. Sonda S3 (apply-progress.md)
 * confirmed both halves against a real {@code postgres:18-alpine}: the statement returns the prior
 * row, and a second session's identical claim blocks until the first commits — the row lock is
 * taken exactly where {@code ON CONFLICT} detects the unique-index collision, so design.md's own
 * documented fallback ({@code INSERT ... ON CONFLICT DO NOTHING} followed by a separate {@code
 * SELECT ... FOR UPDATE}) is never needed and is not implemented here.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): every method assumes it
 * runs inside the transaction {@link com.confia.shared.security.TransactionRunner} already opened.
 */
public final class JooqLoginBackoffStore implements LoginBackoffStore {

    private final DSLContext dsl;

    public JooqLoginBackoffStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public BackoffState claim(InstitutionId institutionId, IdentifierFingerprint fingerprint,
            Instant now) {
        Record2<Integer, OffsetDateTime> priorRow = dsl
                .insertInto(IDENTITY_LOGIN_BACKOFF)
                .set(IDENTITY_LOGIN_BACKOFF.INSTITUTION_ID, institutionId.value())
                .set(IDENTITY_LOGIN_BACKOFF.IDENTIFIER_HASH, fingerprint.value())
                .set(IDENTITY_LOGIN_BACKOFF.CONSECUTIVE_FAILURES, 0)
                .set(IDENTITY_LOGIN_BACKOFF.LAST_ATTEMPT_AT, toOffsetDateTime(now))
                .onConflict(IDENTITY_LOGIN_BACKOFF.INSTITUTION_ID,
                        IDENTITY_LOGIN_BACKOFF.IDENTIFIER_HASH)
                .doUpdate()
                .set(IDENTITY_LOGIN_BACKOFF.CONSECUTIVE_FAILURES,
                        IDENTITY_LOGIN_BACKOFF.CONSECUTIVE_FAILURES)
                .returningResult(IDENTITY_LOGIN_BACKOFF.CONSECUTIVE_FAILURES,
                        IDENTITY_LOGIN_BACKOFF.LAST_ATTEMPT_AT)
                .fetchOne();
        return new BackoffState(priorRow.value1(), priorRow.value2().toInstant());
    }

    @Override
    public void save(InstitutionId institutionId, IdentifierFingerprint fingerprint,
            BackoffState state) {
        dsl.update(IDENTITY_LOGIN_BACKOFF)
                .set(IDENTITY_LOGIN_BACKOFF.CONSECUTIVE_FAILURES, state.consecutiveFailures())
                .set(IDENTITY_LOGIN_BACKOFF.LAST_ATTEMPT_AT, toOffsetDateTime(state.lastAttemptAt()))
                .where(IDENTITY_LOGIN_BACKOFF.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_LOGIN_BACKOFF.IDENTIFIER_HASH.eq(fingerprint.value()))
                .execute();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

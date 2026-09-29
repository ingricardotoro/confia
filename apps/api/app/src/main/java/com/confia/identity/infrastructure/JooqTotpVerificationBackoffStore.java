package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityMfaTotpBackoff.IDENTITY_MFA_TOTP_BACKOFF;

import com.confia.identity.application.TotpVerificationBackoffStore;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.jooq.Record2;

/**
 * The single jOOQ adapter of {@link TotpVerificationBackoffStore}
 * (column-encryption-and-mfa-totp design.md, decision 8). Reproduces {@code
 * JooqLoginBackoffStore#claim} column for column, replacing only the key ({@link StaffAccountId}
 * instead of an identifier fingerprint) — no new constant, no new reclaim shape.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): every method assumes it
 * runs inside the transaction {@link com.confia.shared.security.TransactionRunner} already opened.
 */
public final class JooqTotpVerificationBackoffStore implements TotpVerificationBackoffStore {

    private final DSLContext dsl;

    public JooqTotpVerificationBackoffStore(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public BackoffState claim(InstitutionId institutionId, StaffAccountId accountId, Instant now) {
        Record2<Integer, OffsetDateTime> priorRow = dsl
                .insertInto(IDENTITY_MFA_TOTP_BACKOFF)
                .set(IDENTITY_MFA_TOTP_BACKOFF.INSTITUTION_ID, institutionId.value())
                .set(IDENTITY_MFA_TOTP_BACKOFF.ACCOUNT_ID, accountId.value())
                .set(IDENTITY_MFA_TOTP_BACKOFF.CONSECUTIVE_FAILURES, 0)
                .set(IDENTITY_MFA_TOTP_BACKOFF.LAST_ATTEMPT_AT, toOffsetDateTime(now))
                .onConflict(IDENTITY_MFA_TOTP_BACKOFF.INSTITUTION_ID,
                        IDENTITY_MFA_TOTP_BACKOFF.ACCOUNT_ID)
                .doUpdate()
                .set(IDENTITY_MFA_TOTP_BACKOFF.CONSECUTIVE_FAILURES,
                        IDENTITY_MFA_TOTP_BACKOFF.CONSECUTIVE_FAILURES)
                .returningResult(IDENTITY_MFA_TOTP_BACKOFF.CONSECUTIVE_FAILURES,
                        IDENTITY_MFA_TOTP_BACKOFF.LAST_ATTEMPT_AT)
                .fetchOne();
        return new BackoffState(priorRow.value1(), priorRow.value2().toInstant());
    }

    @Override
    public void save(InstitutionId institutionId, StaffAccountId accountId, BackoffState state) {
        dsl.update(IDENTITY_MFA_TOTP_BACKOFF)
                .set(IDENTITY_MFA_TOTP_BACKOFF.CONSECUTIVE_FAILURES, state.consecutiveFailures())
                .set(IDENTITY_MFA_TOTP_BACKOFF.LAST_ATTEMPT_AT, toOffsetDateTime(state.lastAttemptAt()))
                .where(IDENTITY_MFA_TOTP_BACKOFF.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_MFA_TOTP_BACKOFF.ACCOUNT_ID.eq(accountId.value()))
                .execute();
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

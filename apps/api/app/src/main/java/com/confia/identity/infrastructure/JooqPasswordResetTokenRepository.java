package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityPasswordResetToken.IDENTITY_PASSWORD_RESET_TOKEN;

import com.confia.identity.application.PasswordResetTokenRepository;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The single jOOQ adapter of {@link PasswordResetTokenRepository} (password-recovery-token design.md
 * decision 5; ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with an
 * explicit constructor over {@link DSLContext} and no Spring annotation, the same pattern as {@code
 * JooqRecoveryCodeRepository}.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7): every method runs inside the
 * transaction {@link com.confia.shared.security.TransactionRunner} already opened, and the row
 * policy of {@code V7} scopes it to one institution beneath the explicit predicate.
 *
 * <p><b>{@link #consume} is one conditional {@code UPDATE}</b>, never a read followed by a write:
 * the predicate alone decides the winner, the {@code markUsed} pattern of {@code
 * JooqRecoveryCodeRepository}.
 */
public final class JooqPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final DSLContext dsl;

    public JooqPasswordResetTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public long countIssuedSince(InstitutionId institutionId, StaffAccountId accountId,
            Instant since) {
        return dsl.selectCount()
                .from(IDENTITY_PASSWORD_RESET_TOKEN)
                .where(IDENTITY_PASSWORD_RESET_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_PASSWORD_RESET_TOKEN.ACCOUNT_ID.eq(accountId.value()))
                .and(IDENTITY_PASSWORD_RESET_TOKEN.ISSUED_AT.gt(toOffsetDateTime(since)))
                .fetchOne(0, long.class);
    }

    @Override
    public int supersedeOpen(InstitutionId institutionId, StaffAccountId accountId, Instant at) {
        return dsl.update(IDENTITY_PASSWORD_RESET_TOKEN)
                .set(IDENTITY_PASSWORD_RESET_TOKEN.SUPERSEDED_AT, toOffsetDateTime(at))
                .where(IDENTITY_PASSWORD_RESET_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_PASSWORD_RESET_TOKEN.ACCOUNT_ID.eq(accountId.value()))
                .and(IDENTITY_PASSWORD_RESET_TOKEN.CONSUMED_AT.isNull())
                .and(IDENTITY_PASSWORD_RESET_TOKEN.SUPERSEDED_AT.isNull())
                .execute();
    }

    @Override
    public void insert(InstitutionId institutionId, UUID id, StaffAccountId accountId,
            PasswordResetTokenHash hash, Instant issuedAt, Instant expiresAt) {
        dsl.insertInto(IDENTITY_PASSWORD_RESET_TOKEN)
                .set(IDENTITY_PASSWORD_RESET_TOKEN.INSTITUTION_ID, institutionId.value())
                .set(IDENTITY_PASSWORD_RESET_TOKEN.ID, id)
                .set(IDENTITY_PASSWORD_RESET_TOKEN.ACCOUNT_ID, accountId.value())
                .set(IDENTITY_PASSWORD_RESET_TOKEN.TOKEN_HASH, hash.value())
                .set(IDENTITY_PASSWORD_RESET_TOKEN.ISSUED_AT, toOffsetDateTime(issuedAt))
                .set(IDENTITY_PASSWORD_RESET_TOKEN.EXPIRES_AT, toOffsetDateTime(expiresAt))
                .execute();
    }

    @Override
    public Optional<PasswordResetTokenRow> findByHash(InstitutionId institutionId,
            PasswordResetTokenHash hash) {
        return dsl.selectFrom(IDENTITY_PASSWORD_RESET_TOKEN)
                .where(IDENTITY_PASSWORD_RESET_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_PASSWORD_RESET_TOKEN.TOKEN_HASH.eq(hash.value()))
                .fetchOptional(record -> new PasswordResetTokenRow(record.getId(),
                        new StaffAccountId(record.getAccountId()),
                        toInstant(record.getIssuedAt()), toInstant(record.getExpiresAt()),
                        toInstant(record.getConsumedAt()), toInstant(record.getSupersededAt())));
    }

    @Override
    public boolean consume(InstitutionId institutionId, UUID id, Instant at) {
        OffsetDateTime now = toOffsetDateTime(at);
        int updatedRows = dsl.update(IDENTITY_PASSWORD_RESET_TOKEN)
                .set(IDENTITY_PASSWORD_RESET_TOKEN.CONSUMED_AT, now)
                .where(IDENTITY_PASSWORD_RESET_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_PASSWORD_RESET_TOKEN.ID.eq(id))
                .and(IDENTITY_PASSWORD_RESET_TOKEN.CONSUMED_AT.isNull())
                .and(IDENTITY_PASSWORD_RESET_TOKEN.SUPERSEDED_AT.isNull())
                .and(IDENTITY_PASSWORD_RESET_TOKEN.EXPIRES_AT.gt(now))
                .execute();
        return updatedRows > 0;
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}

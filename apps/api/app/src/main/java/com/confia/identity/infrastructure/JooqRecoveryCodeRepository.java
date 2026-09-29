package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityMfaRecoveryCode.IDENTITY_MFA_RECOVERY_CODE;

import com.confia.identity.application.RecoveryCodeRepository;
import com.confia.identity.domain.RecoveryCodeRow;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The single jOOQ adapter of {@link RecoveryCodeRepository}
 * (column-encryption-and-mfa-totp design.md, decision 3, §4.1, §4.3; ADR-0015 rule 4, R1: jOOQ
 * confined to {@code infrastructure}). {@code final}, with an explicit constructor over {@link
 * DSLContext}, the same pattern {@code JooqTotpCredentialRepository} already established.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): every method assumes it
 * runs inside the transaction {@link com.confia.shared.security.TransactionRunner} already opened.
 *
 * <p><b>{@link #markUsed} is the single conditional statement design.md §4.3 step 3 fixes
 * exactly</b>: an {@code UPDATE ... WHERE id = ? AND used_at IS NULL}, never a {@code SELECT}
 * followed by a separate {@code UPDATE} — the same predicate-decides-everything shape {@code
 * JooqTotpCredentialRepository#acceptCounter} already applies to its own conditional update.
 */
public final class JooqRecoveryCodeRepository implements RecoveryCodeRepository {

    private final DSLContext dsl;

    public JooqRecoveryCodeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institutionId, StaffAccountId accountId, UUID id,
            StoredRecoveryCodeHash hash) {
        dsl.insertInto(IDENTITY_MFA_RECOVERY_CODE)
                .set(IDENTITY_MFA_RECOVERY_CODE.INSTITUTION_ID, institutionId.value())
                .set(IDENTITY_MFA_RECOVERY_CODE.ACCOUNT_ID, accountId.value())
                .set(IDENTITY_MFA_RECOVERY_CODE.ID, id)
                .set(IDENTITY_MFA_RECOVERY_CODE.CODE_HASH, hash.value())
                .execute();
    }

    @Override
    public List<RecoveryCodeRow> findUnusedByAccountId(InstitutionId institutionId,
            StaffAccountId accountId) {
        return dsl.select(IDENTITY_MFA_RECOVERY_CODE.ID, IDENTITY_MFA_RECOVERY_CODE.CODE_HASH)
                .from(IDENTITY_MFA_RECOVERY_CODE)
                .where(IDENTITY_MFA_RECOVERY_CODE.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_MFA_RECOVERY_CODE.ACCOUNT_ID.eq(accountId.value()))
                .and(IDENTITY_MFA_RECOVERY_CODE.USED_AT.isNull())
                .fetch(record -> new RecoveryCodeRow(record.value1(),
                        new StoredRecoveryCodeHash(record.value2())));
    }

    @Override
    public boolean markUsed(InstitutionId institutionId, UUID id, Instant usedAt) {
        int updatedRows = dsl.update(IDENTITY_MFA_RECOVERY_CODE)
                .set(IDENTITY_MFA_RECOVERY_CODE.USED_AT, toOffsetDateTime(usedAt))
                .where(IDENTITY_MFA_RECOVERY_CODE.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_MFA_RECOVERY_CODE.ID.eq(id))
                .and(IDENTITY_MFA_RECOVERY_CODE.USED_AT.isNull())
                .execute();
        return updatedRows > 0;
    }

    @Override
    public long countUnusedByAccountId(InstitutionId institutionId, StaffAccountId accountId) {
        return dsl.selectCount()
                .from(IDENTITY_MFA_RECOVERY_CODE)
                .where(IDENTITY_MFA_RECOVERY_CODE.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_MFA_RECOVERY_CODE.ACCOUNT_ID.eq(accountId.value()))
                .and(IDENTITY_MFA_RECOVERY_CODE.USED_AT.isNull())
                .fetchOne(0, long.class);
    }

    private static OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

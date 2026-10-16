package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityRefreshToken.IDENTITY_REFRESH_TOKEN;
import static confia.generated.jooq.tables.IdentityRefreshTokenFamily.IDENTITY_REFRESH_TOKEN_FAMILY;

import com.confia.identity.application.AccountSessionRevoker;
import com.confia.identity.application.SessionRevocationReason;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link AccountSessionRevoker} (session-tokens-and-web-layer design.md
 * decision 12; ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with an
 * explicit constructor over {@link DSLContext} and no Spring annotation.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7): it runs inside the one {@link
 * com.confia.shared.security.TransactionRunner} opened for the password reset. It locks the live
 * families with {@code ORDER BY id FOR UPDATE}, so two revocations (or a revocation and a rotation,
 * which locks the same row) take the locks in the same order and cannot deadlock, revokes exactly
 * the rows it locked, and empties the encrypted successors of those families' tokens. The
 * forward-only triggers of V8 allow all of that: {@code revoked_*} is set once, and the ciphertext
 * may only be cleared. A family that is already revoked is never selected, so its reason and its
 * instant stay as they were.
 */
public final class JooqAccountSessionRevoker implements AccountSessionRevoker {

    private final DSLContext dsl;

    public JooqAccountSessionRevoker(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public int revokeAllLive(InstitutionId institutionId, StaffAccountId accountId,
            SessionRevocationReason reason, Instant now) {
        List<UUID> liveFamilies = dsl.select(IDENTITY_REFRESH_TOKEN_FAMILY.ID)
                .from(IDENTITY_REFRESH_TOKEN_FAMILY)
                .where(IDENTITY_REFRESH_TOKEN_FAMILY.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_REFRESH_TOKEN_FAMILY.ACCOUNT_ID.eq(accountId.value()))
                .and(IDENTITY_REFRESH_TOKEN_FAMILY.REVOKED_AT.isNull())
                .orderBy(IDENTITY_REFRESH_TOKEN_FAMILY.ID)
                .forUpdate()
                .fetch(IDENTITY_REFRESH_TOKEN_FAMILY.ID);
        if (liveFamilies.isEmpty()) {
            return 0;
        }

        dsl.update(IDENTITY_REFRESH_TOKEN_FAMILY)
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.REVOKED_AT, now.atOffset(ZoneOffset.UTC))
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.REVOKED_REASON, reason.code())
                .where(IDENTITY_REFRESH_TOKEN_FAMILY.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_REFRESH_TOKEN_FAMILY.ID.in(liveFamilies))
                .execute();
        dsl.update(IDENTITY_REFRESH_TOKEN)
                .setNull(IDENTITY_REFRESH_TOKEN.SUCCESSOR_TOKEN_CIPHERTEXT)
                .where(IDENTITY_REFRESH_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_REFRESH_TOKEN.FAMILY_ID.in(liveFamilies))
                .and(IDENTITY_REFRESH_TOKEN.SUCCESSOR_TOKEN_CIPHERTEXT.isNotNull())
                .execute();
        return liveFamilies.size();
    }
}

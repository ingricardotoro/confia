package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityRefreshToken.IDENTITY_REFRESH_TOKEN;

import com.confia.identity.application.RefreshTokenRepository;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.identity.domain.RefreshTokenState;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.kernel.InstitutionId;
import confia.generated.jooq.tables.IdentityRefreshToken;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link RefreshTokenRepository} (session-tokens-and-web-layer design.md
 * decisions 9 and 10; ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with
 * an explicit constructor over {@link DSLContext} and no Spring annotation.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7): it runs inside the one the
 * issuance or the rotation opened. The row-level policy of {@code V8} scopes it to the institution
 * of that transaction; the explicit institution is the value written and the value looked up, and
 * the policy refuses and hides any other. The grants of {@code V8} are per column, and everything
 * this class updates ({@code consumed_at}, the fingerprint, {@code replaced_by} and the encrypted
 * successor) is among the columns granted.
 */
public final class JooqRefreshTokenRepository implements RefreshTokenRepository {

    /** The token that replaced the one being read, to know whether it was consumed in turn. */
    private static final IdentityRefreshToken SUCCESSOR = IDENTITY_REFRESH_TOKEN.as("successor");

    private final DSLContext dsl;

    public JooqRefreshTokenRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institutionId, RefreshTokenRow row) {
        dsl.insertInto(IDENTITY_REFRESH_TOKEN)
                .set(IDENTITY_REFRESH_TOKEN.INSTITUTION_ID, institutionId.value())
                .set(IDENTITY_REFRESH_TOKEN.ID, row.id())
                .set(IDENTITY_REFRESH_TOKEN.FAMILY_ID, row.familyId())
                .set(IDENTITY_REFRESH_TOKEN.TOKEN_HASH, row.hash().value())
                .set(IDENTITY_REFRESH_TOKEN.ISSUED_AT, row.issuedAt().atOffset(ZoneOffset.UTC))
                .set(IDENTITY_REFRESH_TOKEN.EXPIRES_AT, row.expiresAt().atOffset(ZoneOffset.UTC))
                .execute();
    }

    @Override
    public Optional<RefreshTokenState> findByHash(InstitutionId institutionId,
            RefreshTokenHash hash) {
        return find(institutionId, IDENTITY_REFRESH_TOKEN.TOKEN_HASH.eq(hash.value()));
    }

    @Override
    public Optional<RefreshTokenState> findById(InstitutionId institutionId, UUID tokenId) {
        return find(institutionId, IDENTITY_REFRESH_TOKEN.ID.eq(tokenId));
    }

    @Override
    public boolean consume(InstitutionId institutionId, UUID tokenId, Instant now,
            UserAgentFingerprint presenter, UUID successorId) {
        return dsl.update(IDENTITY_REFRESH_TOKEN)
                .set(IDENTITY_REFRESH_TOKEN.CONSUMED_AT, now.atOffset(ZoneOffset.UTC))
                .set(IDENTITY_REFRESH_TOKEN.CONSUMED_USER_AGENT_FINGERPRINT, presenter.value())
                .set(IDENTITY_REFRESH_TOKEN.REPLACED_BY, successorId)
                .where(IDENTITY_REFRESH_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_REFRESH_TOKEN.ID.eq(tokenId))
                .and(IDENTITY_REFRESH_TOKEN.CONSUMED_AT.isNull())
                .execute() == 1;
    }

    @Override
    public void clearSuccessors(InstitutionId institutionId, UUID familyId) {
        dsl.update(IDENTITY_REFRESH_TOKEN)
                .setNull(IDENTITY_REFRESH_TOKEN.SUCCESSOR_TOKEN_CIPHERTEXT)
                .where(IDENTITY_REFRESH_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_REFRESH_TOKEN.FAMILY_ID.eq(familyId))
                .and(IDENTITY_REFRESH_TOKEN.SUCCESSOR_TOKEN_CIPHERTEXT.isNotNull())
                .execute();
    }

    private Optional<RefreshTokenState> find(InstitutionId institutionId, Condition which) {
        Field<Boolean> ciphertextPresent =
                DSL.field(IDENTITY_REFRESH_TOKEN.SUCCESSOR_TOKEN_CIPHERTEXT.isNotNull());
        Field<Boolean> successorConsumed = DSL.field(DSL.exists(DSL.selectOne().from(SUCCESSOR)
                .where(SUCCESSOR.INSTITUTION_ID.eq(IDENTITY_REFRESH_TOKEN.INSTITUTION_ID))
                .and(SUCCESSOR.ID.eq(IDENTITY_REFRESH_TOKEN.REPLACED_BY))
                .and(SUCCESSOR.CONSUMED_AT.isNotNull())));
        return dsl.select(IDENTITY_REFRESH_TOKEN.ID, IDENTITY_REFRESH_TOKEN.FAMILY_ID,
                        IDENTITY_REFRESH_TOKEN.ISSUED_AT, IDENTITY_REFRESH_TOKEN.EXPIRES_AT,
                        IDENTITY_REFRESH_TOKEN.CONSUMED_AT,
                        IDENTITY_REFRESH_TOKEN.CONSUMED_USER_AGENT_FINGERPRINT, ciphertextPresent,
                        successorConsumed)
                .from(IDENTITY_REFRESH_TOKEN)
                .where(IDENTITY_REFRESH_TOKEN.INSTITUTION_ID.eq(institutionId.value()))
                .and(which)
                .fetchOptional(row -> stateOf(row, ciphertextPresent, successorConsumed));
    }

    private static RefreshTokenState stateOf(Record row, Field<Boolean> ciphertextPresent,
            Field<Boolean> successorConsumed) {
        String fingerprint = row.get(IDENTITY_REFRESH_TOKEN.CONSUMED_USER_AGENT_FINGERPRINT);
        return new RefreshTokenState(row.get(IDENTITY_REFRESH_TOKEN.ID),
                row.get(IDENTITY_REFRESH_TOKEN.FAMILY_ID),
                row.get(IDENTITY_REFRESH_TOKEN.ISSUED_AT).toInstant(),
                row.get(IDENTITY_REFRESH_TOKEN.EXPIRES_AT).toInstant(),
                row.get(IDENTITY_REFRESH_TOKEN.CONSUMED_AT) == null ? null
                        : row.get(IDENTITY_REFRESH_TOKEN.CONSUMED_AT).toInstant(),
                fingerprint == null ? null : new UserAgentFingerprint(fingerprint),
                row.get(ciphertextPresent), row.get(successorConsumed));
    }
}

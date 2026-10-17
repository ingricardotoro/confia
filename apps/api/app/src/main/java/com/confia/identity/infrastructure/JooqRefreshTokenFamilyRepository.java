package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityRefreshTokenFamily.IDENTITY_REFRESH_TOKEN_FAMILY;

import com.confia.identity.application.RefreshTokenFamilyRepository;
import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link RefreshTokenFamilyRepository} (session-tokens-and-web-layer design.md
 * decision 9; ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with an
 * explicit constructor over {@link DSLContext} and no Spring annotation.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7): it runs inside the one the
 * issuance opened, together with the token and the audit entry. {@code ip_first_seen} is {@code INET},
 * which the open-source generator models as {@code VARCHAR} (the {@code forcedTypes} of the build),
 * so the bind is cast to {@code inet} explicitly, the same way the audit writer does for {@code
 * source_ip}.
 */
public final class JooqRefreshTokenFamilyRepository implements RefreshTokenFamilyRepository {

    private final DSLContext dsl;

    public JooqRefreshTokenFamilyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institutionId, RefreshTokenFamily family) {
        dsl.insertInto(IDENTITY_REFRESH_TOKEN_FAMILY)
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.INSTITUTION_ID, institutionId.value())
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.ID, family.id())
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.ACCOUNT_ID, family.accountId().value())
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.SECOND_FACTOR_VERIFIED,
                        family.secondFactorVerified())
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.USER_AGENT_FINGERPRINT,
                        family.userAgentFingerprint().value())
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.IP_FIRST_SEEN, inet(family.ipFirstSeen()))
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.CREATED_AT,
                        family.createdAt().atOffset(ZoneOffset.UTC))
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.LAST_USED_AT,
                        family.lastUsedAt().atOffset(ZoneOffset.UTC))
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.ABSOLUTE_EXPIRES_AT,
                        family.absoluteExpiresAt().atOffset(ZoneOffset.UTC))
                .execute();
    }

    /**
     * One {@code SELECT ... FOR UPDATE}: the lock and the state come from the same statement, so the
     * state is the one visible after the wait for the lock, never one read before it. The row is not
     * filtered by {@code revoked_at}: a family revoked while the caller waited must come back as
     * revoked, not as missing.
     */
    @Override
    public Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId, UUID familyId) {
        return dsl.select(IDENTITY_REFRESH_TOKEN_FAMILY.ID, IDENTITY_REFRESH_TOKEN_FAMILY.ACCOUNT_ID,
                        IDENTITY_REFRESH_TOKEN_FAMILY.SECOND_FACTOR_VERIFIED,
                        IDENTITY_REFRESH_TOKEN_FAMILY.LAST_USED_AT,
                        IDENTITY_REFRESH_TOKEN_FAMILY.ABSOLUTE_EXPIRES_AT,
                        IDENTITY_REFRESH_TOKEN_FAMILY.REVOKED_AT)
                .from(IDENTITY_REFRESH_TOKEN_FAMILY)
                .where(IDENTITY_REFRESH_TOKEN_FAMILY.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_REFRESH_TOKEN_FAMILY.ID.eq(familyId))
                .forUpdate()
                .fetchOptional(row -> new RefreshTokenFamilyState(
                        row.get(IDENTITY_REFRESH_TOKEN_FAMILY.ID),
                        new StaffAccountId(row.get(IDENTITY_REFRESH_TOKEN_FAMILY.ACCOUNT_ID)),
                        row.get(IDENTITY_REFRESH_TOKEN_FAMILY.SECOND_FACTOR_VERIFIED),
                        row.get(IDENTITY_REFRESH_TOKEN_FAMILY.LAST_USED_AT).toInstant(),
                        row.get(IDENTITY_REFRESH_TOKEN_FAMILY.ABSOLUTE_EXPIRES_AT).toInstant(),
                        row.get(IDENTITY_REFRESH_TOKEN_FAMILY.REVOKED_AT) == null ? null
                                : row.get(IDENTITY_REFRESH_TOKEN_FAMILY.REVOKED_AT).toInstant()));
    }

    @Override
    public void recordUse(InstitutionId institutionId, UUID familyId, Instant now) {
        dsl.update(IDENTITY_REFRESH_TOKEN_FAMILY)
                .set(IDENTITY_REFRESH_TOKEN_FAMILY.LAST_USED_AT, now.atOffset(ZoneOffset.UTC))
                .where(IDENTITY_REFRESH_TOKEN_FAMILY.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_REFRESH_TOKEN_FAMILY.ID.eq(familyId))
                .execute();
    }

    /**
     * A bind of the text cast to {@code inet}. The template is a fixed literal and the address is a
     * single bind value through jOOQ's own substitution, never concatenated (CLAUDE.md regla 12); the
     * class is in the approved list of {@code NoUnapprovedPlainSqlTest} for exactly this cast.
     */
    private static Field<String> inet(String address) {
        return DSL.field("cast({0} as inet)", String.class, DSL.val(address));
    }
}

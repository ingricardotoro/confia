package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityRefreshTokenFamily.IDENTITY_REFRESH_TOKEN_FAMILY;

import com.confia.identity.application.RefreshTokenFamilyRepository;
import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.kernel.InstitutionId;
import java.time.ZoneOffset;
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
     * A bind of the text cast to {@code inet}. The template is a fixed literal and the address is a
     * single bind value through jOOQ's own substitution, never concatenated (CLAUDE.md regla 12); the
     * class is in the approved list of {@code NoUnapprovedPlainSqlTest} for exactly this cast.
     */
    private static Field<String> inet(String address) {
        return DSL.field("cast({0} as inet)", String.class, DSL.val(address));
    }
}

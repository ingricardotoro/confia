package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityRefreshToken.IDENTITY_REFRESH_TOKEN;

import com.confia.identity.application.RefreshTokenRepository;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.kernel.InstitutionId;
import java.time.ZoneOffset;
import org.jooq.DSLContext;

/**
 * The jOOQ adapter of {@link RefreshTokenRepository} (session-tokens-and-web-layer design.md
 * decision 9; ADR-0015 rule 4: jOOQ confined to {@code infrastructure}). {@code final}, with an
 * explicit constructor over {@link DSLContext} and no Spring annotation.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7): it runs inside the one the
 * issuance opened. The row-level policy of {@code V8} scopes it to the institution of that
 * transaction; the explicit institution is the value written, and the policy refuses any other.
 */
public final class JooqRefreshTokenRepository implements RefreshTokenRepository {

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
}

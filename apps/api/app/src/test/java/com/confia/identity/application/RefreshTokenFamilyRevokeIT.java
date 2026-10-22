package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.kernel.InstitutionId;
import java.time.Instant;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The write of {@link RefreshTokenFamilyRepository#revoke} on its own, against a real PostgreSQL
 * (session-endpoints-cookie-and-csrf design.md decision 15): one family, any reason of the catalogue,
 * and only while the family is not revoked yet. The use cases keep a revoked family from ever
 * reaching this call, so its condition is the safety net; these tests exercise the net directly.
 */
class RefreshTokenFamilyRevokeIT extends RevocationIntegrationTest {

    private static final String NOW = "2026-10-15T15:20:00Z";

    @ParameterizedTest
    @EnumSource(SessionRevocationReason.class)
    void everyReasonOfTheCatalogueIsWrittenExactlyAsItsCode(SessionRevocationReason reason) {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-15T15:00:00Z", "2026-10-15T15:10:00Z",
                "2026-10-16T03:00:00Z", null, null);

        boolean revoked = revoke(institutionId, family, reason, NOW);

        assertThat(revoked).isTrue();
        Record row = familyRow(family);
        assertThat(row.get("revoked_reason", String.class)).isEqualTo(reason.code());
        assertThat(instantOf(row, "revoked_at")).isEqualTo(Instant.parse(NOW));
    }

    @Test
    void aFamilyAlreadyRevokedForTheSameReasonAtTheSameInstantIsNotRevokedAgain() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-15T15:00:00Z", "2026-10-15T15:10:00Z",
                "2026-10-16T03:00:00Z", NOW, "logout");

        boolean revoked = revoke(institutionId, family, SessionRevocationReason.LOGOUT, NOW);

        assertThat(revoked).as("the call did nothing, so it does not claim to have revoked")
                .isFalse();
        Record row = familyRow(family);
        assertThat(row.get("revoked_reason", String.class)).isEqualTo("logout");
        assertThat(instantOf(row, "revoked_at")).isEqualTo(Instant.parse(NOW));
    }

    @Test
    void aFamilyAlreadyRevokedForAnotherReasonKeepsItsReasonAndItsInstant() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-15T13:00:00Z", "2026-10-15T13:30:00Z",
                "2026-10-16T01:00:00Z", "2026-10-15T13:30:00Z", "reuse_detected");

        boolean revoked = revoke(institutionId, family, SessionRevocationReason.LOGOUT, NOW);

        assertThat(revoked).isFalse();
        Record row = familyRow(family);
        assertThat(row.get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(instantOf(row, "revoked_at"))
                .isEqualTo(Instant.parse("2026-10-15T13:30:00Z"));
    }

    @Test
    void onlyTheNamedFamilyIsRevokedAndATokenIsNotTouched() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID named = seedFamily(ana, false, "2026-10-15T15:00:00Z", "2026-10-15T15:10:00Z",
                "2026-10-16T03:00:00Z", null, null);
        seedOpenToken(named, "2026-10-15T15:00:00Z", "2026-10-15T23:00:00Z");
        UUID sibling = seedFamily(ana, false, "2026-10-15T15:00:00Z", "2026-10-15T15:10:00Z",
                "2026-10-16T03:00:00Z", null, null);
        seedConsumedToken(sibling, "2026-10-15T15:00:00Z", "2026-10-15T23:00:00Z",
                "2026-10-15T15:05:00Z", AGENT_ONE, true);

        revoke(institutionId, named, SessionRevocationReason.LOGOUT, NOW);

        assertThat(familyRow(sibling).get("revoked_at")).isNull();
        assertThat(tokensOf(named)).singleElement()
                .satisfies(token -> assertThat(token.get("consumed_at")).isNull());
        assertThat(tokensOf(sibling)).anySatisfy(token ->
                assertThat(token.get("successor_token_ciphertext")).isNotNull());
    }

    @Test
    void aFamilyOfAnotherInstitutionIsNotRevoked() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-15T15:00:00Z", "2026-10-15T15:10:00Z",
                "2026-10-16T03:00:00Z", null, null);
        InstitutionId other = new InstitutionId(UUID.randomUUID());

        boolean revoked = revoke(other, family, SessionRevocationReason.LOGOUT, NOW);

        assertThat(revoked).isFalse();
        assertThat(familyRow(family).get("revoked_at")).isNull();
    }

    @Test
    void revokingForReuseKeepsWritingTheReuseReasonAndIsConditionalToo() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, "2026-10-15T15:00:00Z", "2026-10-15T15:10:00Z",
                "2026-10-16T03:00:00Z", null, null);

        boolean first = transactionRunner().execute(context(),
                () -> new JooqRefreshTokenFamilyRepository(dsl)
                        .revokeForReuse(institutionId, family, Instant.parse(NOW)));
        boolean second = transactionRunner().execute(context(),
                () -> new JooqRefreshTokenFamilyRepository(dsl)
                        .revokeForReuse(institutionId, family, Instant.parse(NOW)));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(familyRow(family).get("revoked_reason", String.class))
                .isEqualTo("reuse_detected");
    }

    private boolean revoke(InstitutionId institution, UUID family, SessionRevocationReason reason,
            String instant) {
        return transactionRunner().execute(context(),
                () -> new JooqRefreshTokenFamilyRepository(dsl)
                        .revoke(institution, family, reason, Instant.parse(instant)));
    }
}

package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Scenario I198 (session-endpoints-cookie-and-csrf design.md section 4): ending the current session
 * and rotating the same refresh token at the same time, in both orders of the lock of the family. Two
 * real transactions on a real PostgreSQL, each on its own connection. Whoever comes second finds the
 * outcome of whoever came first, and in either order the family ends revoked with {@code logout} and
 * no token of it can be presented.
 *
 * <p>No clock decides any order. The transaction that must be first is held with a {@link
 * CountDownLatch} right after it takes the lock of the family; the other is started, and the test
 * goes on when the database reports a backend waiting for a row lock on the family (a condition with
 * a bound, never a pause).
 */
class LogoutVersusRotationConcurrencyIT extends RevocationIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The rotation owns the lock; the closing arrives second and waits. The rotation completes and
     * inserts its successor, and the closing, which located the family through the token the rotation
     * just consumed, revokes it: the successor is born into a family that ends revoked.
     */
    @Test
    void aClosingThatWaitsForTheRotationOfTheSameTokenRevokesTheFamilyAndLeavesNoLiveToken()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        String token = session.refreshToken().value();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RotateRefreshToken holding = rotation(fixedClock("2026-10-15T15:05:00Z"),
                new JooqAuditLogWriter(dsl), institutionId,
                new HoldingFamilies(new JooqRefreshTokenFamilyRepository(dsl), locked, release));

        Race<RefreshOutcome, Boolean> race = race(locked, release,
                () -> holding.execute(context(), new RotateRefreshTokenCommand(token, AGENT_ONE)),
                () -> {
                    endCurrentSession("2026-10-15T15:06:00Z").execute(token,
                            UUID.randomUUID().toString());
                    return true;
                });

        assertThat(race.waiterWaited())
                .as("the closing waited for the family lock the rotation held").isTrue();
        assertThat(race.holder()).isInstanceOf(Rotated.class);
        assertThat(((Rotated) race.holder()).kind()).isEqualTo(RotationKind.FRESH);
        Record family = familyRow(session.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T15:06:00Z"));
        assertThat(tokensOf(session.sessionId())).as("the rotation did complete").hasSize(2);
        assertThat(liveTokenCount()).as("the new successor lives in a revoked family").isZero();
        assertThat(encryptedSuccessorsIn(institutionId, ana)).isZero();
        assertThat(rotate("2026-10-15T15:07:00Z",
                ((Rotated) race.holder()).session().refreshToken().value(), AGENT_ONE))
                .isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        assertThat(auditRows("identity.session.refreshed")).hasSize(1);
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).singleElement()
                .satisfies(row -> assertLogoutDocument(row, 1));
    }

    /**
     * The closing owns the lock; the rotation arrives second and waits. When the closing commits, the
     * rotation reads the family revoked: it consumes nothing, inserts no successor and is rejected as
     * revoked.
     */
    @Test
    void aRotationThatWaitsForTheClosingOfTheSameTokenIsRejectedAsRevokedAndConsumesNothing()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        String token = session.refreshToken().value();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        EndCurrentSession holding = endCurrentSession(institutionId, "2026-10-15T15:05:00Z",
                new JooqAuditLogWriter(dsl), new HoldingFamilies(
                        new JooqRefreshTokenFamilyRepository(dsl), locked, release));

        Race<Boolean, RefreshOutcome> race = race(locked, release,
                () -> {
                    holding.execute(token, UUID.randomUUID().toString());
                    return true;
                },
                () -> rotate("2026-10-15T15:06:00Z", token, AGENT_ONE));

        assertThat(race.waiterWaited())
                .as("the rotation waited for the family lock the closing held").isTrue();
        assertThat(race.waiter()).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        Record family = familyRow(session.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T15:05:00Z"));
        List<Record> tokens = tokensOf(session.sessionId());
        assertThat(tokens).as("the rotation inserted no successor").hasSize(1);
        assertThat(tokens.get(0).get("consumed_at")).as("and consumed nothing").isNull();
        assertThat(liveTokenCount()).isZero();
        assertThat(auditRows("identity.session.refreshed")).isEmpty();
        assertThat(auditRows("identity.session.refresh_rejected")).singleElement().satisfies(row ->
                assertThat(JSON.readTree(row.afterValue()).get("reason").asString())
                        .isEqualTo("family_revoked"));
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).singleElement()
                .satisfies(row -> assertLogoutDocument(row, 1));
    }

    private EndCurrentSession endCurrentSession(InstitutionId configured,
            String instant, JooqAuditLogWriter audit, RefreshTokenFamilyRepository families) {
        return new EndCurrentSession(transactionRunner(), () -> configured, families,
                new JooqRefreshTokenRepository(dsl), audit, fixedClock(instant));
    }
}

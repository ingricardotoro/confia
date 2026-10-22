package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * Scenario I198, for the closing of every session (session-endpoints-cookie-and-csrf design.md
 * section 4): ending every live session of an account and rotating one of its refresh tokens at the
 * same time, in both orders of the lock of the family. Two real transactions on a real PostgreSQL. The
 * closing locks the account and then its live families {@code ORDER BY id}; a rotation takes only the
 * lock of its family and never the account, so the two cannot wait for each other in a cycle. In either
 * order the family ends revoked with {@code logout} and no token of it can be presented.
 *
 * <p>No clock decides any order: the transaction that must be first is held with a {@link
 * CountDownLatch}, the other is started, and the test goes on when the database reports a backend
 * waiting for a row lock on the family (a condition with a bound, never a pause).
 */
class CloseAllVersusRotationConcurrencyIT extends RevocationIntegrationTest {

    /**
     * The rotation owns the lock of the family; the closing takes the account, then waits for the
     * family. The rotation completes and inserts its successor, and the closing then revokes the family
     * and empties the encrypted successor the rotation just stored.
     */
    @Test
    void aClosingOfEverySessionThatWaitsForARotationRevokesTheFamilyAndLeavesNoLiveToken()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        String token = session.refreshToken().value();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RotateRefreshToken holding = rotation(fixedClock("2026-10-15T15:05:00Z"),
                new JooqAuditLogWriter(dsl), institutionId,
                new HoldingFamilies(new JooqRefreshTokenFamilyRepository(dsl), locked, release));

        Race<RefreshOutcome, Integer> race = race(locked, release,
                () -> holding.execute(context(), new RotateRefreshTokenCommand(token, AGENT_ONE)),
                () -> endAllSessions("2026-10-15T15:06:00Z").execute(
                        actorOf(institutionId, ana, session.sessionId()),
                        UUID.randomUUID().toString()));

        assertThat(race.waiterWaited())
                .as("the closing waited for the family lock the rotation held").isTrue();
        assertThat(race.holder()).isInstanceOf(Rotated.class);
        assertThat(((Rotated) race.holder()).kind()).isEqualTo(RotationKind.FRESH);
        assertThat(race.waiter()).as("the closing revoked the one family").isEqualTo(1);
        Record family = familyRow(session.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T15:06:00Z"));
        assertThat(tokensOf(session.sessionId())).as("the rotation did complete").hasSize(2);
        assertThat(liveTokenCount()).as("the new successor lives in a revoked family").isZero();
        assertThat(encryptedSuccessorsIn(institutionId, ana))
                .as("the successor the rotation stored was emptied by the closing").isZero();
        assertThat(rotate("2026-10-15T15:07:00Z",
                ((Rotated) race.holder()).session().refreshToken().value(), AGENT_ONE))
                .isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        assertThat(auditRowsIn(institutionId, ACTION_REVOKED)).singleElement()
                .satisfies(row -> assertLogoutDocument(row, 1));
    }

    /**
     * The closing owns the locks of the account and of the family and is held after revoking, before
     * it commits; the rotation arrives second and waits for the family. When the closing commits, the
     * rotation reads the family revoked: it consumes nothing, inserts no successor and is rejected as
     * revoked.
     */
    @Test
    void aRotationThatWaitsForTheClosingOfEverySessionIsRejectedAsRevokedAndConsumesNothing()
            throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssuedSession session = issue("2026-10-15T15:00:00Z", ana);
        String token = session.refreshToken().value();
        CountDownLatch revoked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JooqAccountSessionRevoker real = new JooqAccountSessionRevoker(dsl);
        AccountSessionRevoker holdingAfterRevoking = (institution, account, reason, now) -> {
            int count = real.revokeAllLive(institution, account, reason, now);
            revoked.countDown();
            awaitRelease(release);
            return count;
        };
        EndAllSessions holding = endAllSessions("2026-10-15T15:05:00Z",
                new JooqStaffAccountRepository(dsl), holdingAfterRevoking,
                new JooqAuditLogWriter(dsl));

        Race<Integer, RefreshOutcome> race = race(revoked, release,
                () -> holding.execute(actorOf(institutionId, ana, session.sessionId()),
                        UUID.randomUUID().toString()),
                () -> rotate("2026-10-15T15:06:00Z", token, AGENT_ONE));

        assertThat(race.waiterWaited())
                .as("the rotation waited for the family lock the closing held").isTrue();
        assertThat(race.holder()).isEqualTo(1);
        assertThat(race.waiter()).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        Record family = familyRow(session.sessionId());
        assertThat(family.get("revoked_reason", String.class)).isEqualTo("logout");
        assertThat(instantOf(family, "revoked_at")).isEqualTo(Instant.parse("2026-10-15T15:05:00Z"));
        List<Record> tokens = tokensOf(session.sessionId());
        assertThat(tokens).as("the rotation inserted no successor").hasSize(1);
        assertThat(tokens.get(0).get("consumed_at")).as("and consumed nothing").isNull();
        assertThat(liveTokenCount()).isZero();
        assertThat(auditRows("identity.session.refreshed")).isEmpty();
    }
}

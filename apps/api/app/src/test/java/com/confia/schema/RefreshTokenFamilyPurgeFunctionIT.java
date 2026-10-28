package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules of {@code identity_purge_refresh_token_families} that belong to refresh-token families
 * and their tokens (background-jobs-with-db-scheduler design.md decision 16; identity scenarios
 * I244 to I249, I272 and I273 and build-integrity scenarios BI106 and BI107), as the real {@code
 * confia_admin_app} role. What every purge function shares is in {@link RetentionPurgeFunctionsIT}.
 * The database clock rules of that class apply here: every instant is anchored on the {@code now()}
 * of the transaction that seeds and calls.
 */
class RefreshTokenFamilyPurgeFunctionIT extends PostgresIntegrationTest {

    private static final String ADMIN = "confia_admin_app";
    private static final String PURGE =
            "select purged_families, purged_tokens from identity_purge_refresh_token_families(?, ?)";

    @Test
    void aFamilyRevokedTwentyNineDaysAgoIsKeptAndOneRevokedThirtyOneDaysAgoIsPurged()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime now = session.now();
            UUID account = session.seedAccount(institution);
            UUID recent = revokedFamily(session, institution, account, now.minusDays(29));
            UUID old = revokedFamily(session, institution, account, now.minusDays(31));

            long[] purged = purge(session, now.plusDays(1), 100);

            assertThat(purged).as("families and tokens purged").containsExactly(1, 1);
            assertThat(familyExists(session, recent)).isTrue();
            assertThat(familyExists(session, old)).isFalse();
        }
    }

    @Test
    void aFamilyPastItsAbsoluteExpiryIsPurgedWithoutBeingRevokedAndTheBoundaryIsExact()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime cutoff = session.now().minusDays(60);
            UUID account = session.seedAccount(institution);
            UUID before = expiredFamily(session, institution, account, cutoff.minusSeconds(1));
            UUID at = expiredFamily(session, institution, account, cutoff);
            UUID after = expiredFamily(session, institution, account, cutoff.plusSeconds(1));

            long[] purged = purge(session, cutoff, 100);

            assertThat(purged).containsExactly(1, 1);
            assertThat(familyExists(session, before)).isFalse();
            assertThat(familyExists(session, at)).as("the exact cut-off is kept").isTrue();
            assertThat(familyExists(session, after)).isTrue();
        }
    }

    @Test
    void aLiveFamilyAndOneInsideTheWindowKeepEveryToken() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime now = session.now();
            UUID account = session.seedAccount(institution);
            UUID live = session.seedFamily(institution, account, null, now.plusDays(5), now);
            session.seedOpenToken(institution, live);
            expiredFamily(session, institution, account, now.minusDays(29));
            revokedFamily(session, institution, account, now.minusDays(29));

            long[] purged = purge(session, now.plusDays(1), 100);

            assertThat(purged).as("nothing is old enough").containsExactly(0, 0);
            assertThat(session.count("select count(*) from identity_refresh_token")).isEqualTo(3);
            assertThat(session.count("select count(*) from identity_refresh_token_family"))
                    .isEqualTo(3);
        }
    }

    @Test
    void idlenessAloneNeverMakesAFamilyPurgeable() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime now = session.now();
            UUID idle = session.seedFamily(institution, session.seedAccount(institution), null,
                    now.plusDays(1), now.minusDays(400));
            session.seedOpenToken(institution, idle);

            long[] purged = purge(session, now.plusDays(1), 100);

            assertThat(purged).containsExactly(0, 0);
            assertThat(familyExists(session, idle)).as("last use 400 days ago, still valid")
                    .isTrue();
        }
    }

    @Test
    void theTokensGoBeforeTheirFamilyWithAChainedSuccessorAndNoKeyIsViolated()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = session.now().minusDays(40);
            UUID family = session.seedFamily(institution, session.seedAccount(institution), old,
                    old.plusDays(365), old);
            seedChain(session, institution, family, 3);

            long[] purged = purge(session, session.now().plusDays(1), 100);
            session.execute("set constraints all immediate");

            assertThat(purged).as("one family, three chained tokens").containsExactly(1, 3);
            assertThat(session.count("select count(*) from identity_refresh_token")).isZero();
        }
    }

    @Test
    void aFamilyWithMoreTokensThanTheBatchIsDeletedWhole() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime now = session.now();
            UUID account = session.seedAccount(institution);
            UUID first = session.seedFamily(institution, account, now.minusDays(40),
                    now.plusDays(300), now.minusDays(40));
            UUID other = session.seedFamily(institution, account, now.minusDays(41),
                    now.plusDays(300), now.minusDays(41));
            // The function takes families in database id order, and the ids are random, so the
            // long chain goes to whichever family PostgreSQL sorts first: the test stays deterministic.
            boolean firstSortsFirst = session.count(
                    "select count(*) from identity_refresh_token_family where id < ? and id = ?",
                    other, first) == 1;
            UUID big = firstSortsFirst ? first : other;
            UUID second = firstSortsFirst ? other : first;
            seedChain(session, institution, big, 5);
            session.seedOpenToken(institution, second);

            long[] purged = purge(session, now.plusDays(1), 1);

            assertThat(purged[0]).as("a batch of one family").isEqualTo(1);
            assertThat(purged[1]).as("every token of that family, more than the batch")
                    .isEqualTo(5);
            assertThat(familyExists(session, big)).isFalse();
            assertThat(familyExists(session, second)).as("the other family waits").isTrue();
        }
    }

    /**
     * Scenario I249: a failure in the middle leaves no family half deleted. The tokens are deleted
     * first; a trigger that refuses the deletion of the family, in a throw-away database, makes the
     * function fail after that step, and the tokens are all still there.
     */
    @Test
    void aFailureAfterTheTokensWereDeletedLeavesNoFamilyHalfDeleted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("11")) {
            try (PurgeSession owner = PurgeSession.open(database, "confia_owner")) {
                owner.execute("""
                        create function refuse_family_delete() returns trigger language plpgsql as $$
                        begin raise exception 'simulated failure in the middle'; end $$
                        """);
                owner.execute("create trigger refuse_family_delete before delete "
                        + "on identity_refresh_token_family for each row "
                        + "execute function refuse_family_delete()");
                owner.commit();
            }
            try (PurgeSession session = PurgeSession.open(database, ADMIN).asSystemOf(institution)) {
                OffsetDateTime old = session.now().minusDays(40);
                UUID family = session.seedFamily(institution, session.seedAccount(institution),
                        old, old.plusDays(365), old);
                seedChain(session, institution, family, 3);

                PurgeSession.Failure failure = session.failureOf(PURGE, session.now().plusDays(1), 100);

                assertThat(failure).isNotNull();
                assertThat(failure.message()).contains("simulated failure in the middle");
                assertThat(session.count("select count(*) from identity_refresh_token"))
                        .as("the tokens deleted before the failure are back").isEqualTo(3);
                assertThat(familyExists(session, family)).isTrue();
            }
        }
    }

    /** A chain of {@code length} tokens: each consumed one names the next, the last is open. */
    private static void seedChain(PurgeSession session, UUID institution, UUID family,
            int length) throws SQLException {
        UUID successor = session.seedOpenToken(institution, family);
        for (int i = 1; i < length; i++) {
            successor = session.seedConsumedToken(institution, family, successor);
        }
    }

    private static UUID revokedFamily(PurgeSession session, UUID institution, UUID account,
            OffsetDateTime revokedAt) throws SQLException {
        UUID family = session.seedFamily(institution, account, revokedAt,
                revokedAt.plus(365, ChronoUnit.DAYS), revokedAt);
        session.seedOpenToken(institution, family);
        return family;
    }

    private static UUID expiredFamily(PurgeSession session, UUID institution, UUID account,
            OffsetDateTime absoluteExpiresAt) throws SQLException {
        UUID family = session.seedFamily(institution, account, null, absoluteExpiresAt,
                absoluteExpiresAt);
        session.seedOpenToken(institution, family);
        return family;
    }

    private static boolean familyExists(PurgeSession session, UUID family) throws SQLException {
        return session.count("select count(*) from identity_refresh_token_family where id = ?",
                family) == 1;
    }

    private static long[] purge(PurgeSession session, OffsetDateTime cutoff, int limit)
            throws SQLException {
        try (var statement = session.connection().prepareStatement(PURGE)) {
            statement.setObject(1, cutoff);
            statement.setInt(2, limit);
            try (var result = statement.executeQuery()) {
                result.next();
                return new long[] {result.getLong(1), result.getLong(2)};
            }
        }
    }
}

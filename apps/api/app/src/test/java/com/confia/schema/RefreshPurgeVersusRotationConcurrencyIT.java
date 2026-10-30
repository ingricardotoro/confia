package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The refresh-token purge and a rotation reach the same family at the same time
 * (background-jobs-with-db-scheduler design.md decision 16, "Particularidades" of {@code
 * identity_purge_refresh_token_families}; design.md section 4, concurrency). Both take the lock of
 * the family first: the purge locks the families it will delete in id order, a rotation locks the one
 * family it rotates and then its tokens, so neither can wait for the other in a cycle.
 *
 * <p>No clock decides any order. The first transaction is held with its lock, the second is started
 * in a thread, and the test goes on when {@code pg_stat_activity} reports a backend waiting for a
 * lock (a condition with a bound, never a fixed pause). Every row is committed, so the base class
 * truncates the tables afterwards.
 */
class RefreshPurgeVersusRotationConcurrencyIT extends CommittingPostgresIntegrationTest {

    private static final String ADMIN = "confia_admin_app";
    private static final long LIMIT_MILLIS = 20_000;
    private static final String LOCK_FAMILY =
            "select id from identity_refresh_token_family where id = ? for update";
    private static final String PURGE =
            "select purged_families, purged_tokens from identity_purge_refresh_token_families(?, ?)";

    @Test
    void aPurgeWaitingForTheLockOfARotatedFamilyDeletesTheFamilyWithTheSuccessorTheRotationInserted()
            throws Exception {
        UUID institution = UUID.randomUUID();
        Seeded seeded = seedPurgeableFamilies(institution, 1);
        UUID family = seeded.families().get(0);

        try (PurgeSession rotation = PurgeSession.open(ADMIN).context(institution);
                ExecutorService pool = Executors.newSingleThreadExecutor()) {
            assertThat(rotation.count("select count(*) from (" + LOCK_FAMILY + ") locked", family))
                    .as("the rotation holds the lock of the family").isEqualTo(1);

            Future<long[]> purging = pool.submit(purgeAsTheWorker(institution));
            boolean purgeWaited = holdsWithin(() -> purging.isDone()
                    || someBackendWaitsFor("%identity_purge_refresh_token_families%"))
                    && !purging.isDone();

            rotate(rotation, institution, family, seeded.openTokens().get(0));
            rotation.commit();
            long[] purged = purging.get(60, TimeUnit.SECONDS);

            assertThat(purgeWaited).as("the purge waited for the family lock the rotation held")
                    .isTrue();
            assertThat(purged).as("no deadlock; one family; the original token and its successor")
                    .containsExactly(1, 2);
        }
        assertThat(rowsLeft()).as("nothing is left of the family").isZero();
    }

    @Test
    void aRotationWaitingForTheLockOfAFamilyThePurgeDeletesFindsNoFamily() throws Exception {
        UUID institution = UUID.randomUUID();
        Seeded seeded = seedPurgeableFamilies(institution, 1);
        UUID family = seeded.families().get(0);

        try (PurgeSession purge = PurgeSession.open(ADMIN).asSystemOf(institution);
                ExecutorService pool = Executors.newSingleThreadExecutor()) {
            long[] purged = purgeOn(purge);

            Future<Long> rotating = pool.submit(() -> {
                try (PurgeSession rotation = PurgeSession.open(ADMIN).context(institution)) {
                    long found = rotation.count("select count(*) from ("
                            + LOCK_FAMILY + ") locked", family);
                    rotation.commit();
                    return found;
                }
            });
            boolean rotationWaited = holdsWithin(() -> rotating.isDone()
                    || someBackendWaitsFor("%identity_refresh_token_family where id%"))
                    && !rotating.isDone();
            purge.commit();

            assertThat(rotationWaited)
                    .as("the rotation waited for the family lock the purge held").isTrue();
            assertThat(purged).containsExactly(1, 1);
            assertThat(rotating.get(60, TimeUnit.SECONDS))
                    .as("under the lock the rotation finds the family already deleted").isZero();
        }
        assertThat(rowsLeft()).isZero();
    }

    @Test
    void twoPurgesAtTheSameTimeLockInIdOrderWithoutDeadlockAndDeleteEveryFamilyOnce()
            throws Exception {
        UUID institution = UUID.randomUUID();
        seedPurgeableFamilies(institution, 30);

        try (PurgeSession first = PurgeSession.open(ADMIN).asSystemOf(institution);
                ExecutorService pool = Executors.newSingleThreadExecutor()) {
            long[] purgedByFirst = purgeOn(first);

            Future<long[]> second = pool.submit(purgeAsTheWorker(institution));
            boolean secondWaited = holdsWithin(() -> second.isDone()
                    || someBackendWaitsFor("%identity_purge_refresh_token_families%"))
                    && !second.isDone();
            first.commit();
            long[] purgedBySecond = second.get(60, TimeUnit.SECONDS);

            assertThat(secondWaited).as("the second purge waited for the first one's locks")
                    .isTrue();
            assertThat(purgedByFirst).as("the first deletes the thirty families").containsExactly(30, 30);
            assertThat(purgedBySecond).as("the second finds them gone and ends without error")
                    .containsExactly(0, 0);
        }
        assertThat(rowsLeft()).isZero();
    }

    private record Seeded(List<UUID> families, List<UUID> openTokens) {
    }

    /** Committed families revoked forty days ago, one open token each. */
    private Seeded seedPurgeableFamilies(UUID institution, int families) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        List<UUID> tokens = new ArrayList<>();
        try (PurgeSession seed = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = seed.now().minusDays(40);
            UUID account = seed.seedAccount(institution);
            for (int i = 0; i < families; i++) {
                UUID family = seed.seedFamily(institution, account, old, old.plusDays(365), old);
                tokens.add(seed.seedOpenToken(institution, family));
                ids.add(family);
            }
            seed.commit();
        }
        return new Seeded(ids, tokens);
    }

    /** What a rotation does under the family lock: touch the family, consume, insert a successor. */
    private static void rotate(PurgeSession rotation, UUID institution, UUID family,
            UUID openToken) throws SQLException {
        UUID successor = UUID.randomUUID();
        rotation.execute("update identity_refresh_token_family set last_used_at = now() "
                + "where id = ?", family);
        rotation.execute("""
                update identity_refresh_token
                   set consumed_at = now(), consumed_user_agent_fingerprint = ?, replaced_by = ?
                 where id = ?
                """, PurgeSession.ZERO_FINGERPRINT, successor, openToken);
        rotation.execute("""
                insert into identity_refresh_token
                    (institution_id, id, family_id, token_hash, issued_at, expires_at)
                values (?, ?, ?, ?, now(), now() + interval '8 hours')
                """, institution, successor, family, successor.toString().replace("-", "").repeat(2));
    }

    private static Callable<long[]> purgeAsTheWorker(UUID institution) {
        return () -> {
            try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
                long[] purged = purgeOn(session);
                session.commit();
                return purged;
            }
        };
    }

    private static long[] purgeOn(PurgeSession session) throws SQLException {
        try (PreparedStatement statement = session.connection().prepareStatement(PURGE)) {
            statement.setObject(1, session.now().plusDays(1));
            statement.setInt(2, 1000);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return new long[] {result.getLong(1), result.getLong(2)};
            }
        }
    }

    private long rowsLeft() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select (select count(*) from identity_refresh_token_family) "
                                + "+ (select count(*) from identity_refresh_token)")) {
            // superuser bypasses RLS, so this counts every institution's rows
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    /** True when a backend of this database is waiting for a lock and its statement matches. */
    private static boolean someBackendWaitsFor(String statementPattern) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select count(*) from pg_stat_activity
                         where datname = current_database() and wait_event_type = 'Lock'
                           and query like ?
                        """)) {
            statement.setString(1, statementPattern);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1) > 0;
            }
        }
    }

    /** Polls the condition until it holds or the bound is reached. */
    private static boolean holdsWithin(SqlCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LIMIT_MILLIS);
        while (System.nanoTime() < deadline) {
            if (condition.holds()) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    @FunctionalInterface
    private interface SqlCondition {

        boolean holds() throws SQLException;
    }
}

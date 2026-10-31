package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.kernel.InstitutionId;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * The login backoff purge and a login reach the same row at the same time
 * (background-jobs-with-db-scheduler design.md section 4, "Purga de reintentos frente a un inicio
 * de sesión que actualiza la fila"; identity scenario I259). The login runs through the real {@link
 * JooqLoginBackoffStore}: {@code claim} is an {@code INSERT ... ON CONFLICT DO UPDATE} and {@code
 * save} an {@code UPDATE}.
 *
 * <p>No clock decides any order. The first transaction is held with its lock, the second runs in a
 * thread that records its own backend process id first, and the test goes on when {@code
 * pg_stat_activity} reports that very backend waiting for a lock (a condition with a bound, never a
 * fixed pause). The pool is declared before the session that holds the lock, so the session rolls
 * back and releases the lock before the pool waits for the thread: a failure while the lock is held
 * fails the test instead of hanging it. Every row is committed, so the base class truncates the
 * tables afterwards.
 */
class BackoffPurgeVersusLoginIT extends CommittingPostgresIntegrationTest {

    private static final String ADMIN = "confia_admin_app";
    private static final long LIMIT_MILLIS = 20_000;
    private static final String PURGE = "select identity_purge_login_backoff(?, ?)";

    @Test
    void aLoginWaitingForThePurgeWaitsForTheDeleteAndRecreatesTheRowByInsert() throws Exception {
        UUID institution = UUID.randomUUID();
        String identifier = seedIdleRow(institution, 4);
        AtomicInteger loginPid = new AtomicInteger();

        try (ExecutorService pool = Executors.newSingleThreadExecutor();
                PurgeSession purge = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            assertThat(purgeOn(purge)).as("the purge deletes the idle row").isEqualTo(1);

            Future<BackoffState> login = pool.submit(() -> failedLogin(institution, identifier,
                    loginPid));
            boolean loginWaited = holdsWithin(() -> login.isDone() || waitsForALock(loginPid))
                    && !login.isDone();
            purge.commit();
            BackoffState prior = login.get(60, TimeUnit.SECONDS);

            assertThat(loginWaited).as("the login waited for the row the purge deleted").isTrue();
            assertThat(prior.consecutiveFailures())
                    .as("the claim found no row after the delete: it inserted a fresh one")
                    .isZero();
        }
        assertThat(failuresOf(institution, identifier))
                .as("the row is back, counting this one failure only").isEqualTo(1);
    }

    @Test
    void aPurgeWaitingForALoginThatRefreshedTheRowKeepsIt() throws Exception {
        UUID institution = UUID.randomUUID();
        String identifier = seedIdleRow(institution, 4);

        try (ExecutorService pool = Executors.newSingleThreadExecutor();
                PurgeSession login = PurgeSession.open(ADMIN).context(institution)) {
            JooqLoginBackoffStore store = storeOn(login.connection());
            BackoffState prior = store.claim(new InstitutionId(institution),
                    new IdentifierFingerprint(identifier), login.now().toInstant());
            store.save(new InstitutionId(institution), new IdentifierFingerprint(identifier),
                    new BackoffState(prior.consecutiveFailures() + 1, login.now().toInstant()));

            AtomicInteger purgePid = new AtomicInteger();
            Future<Long> purging = pool.submit(() -> {
                try (PurgeSession purge = PurgeSession.open(ADMIN).asSystemOf(institution)) {
                    purgePid.set((int) purge.count("select pg_backend_pid()"));
                    long purged = purgeOn(purge);
                    purge.commit();
                    return purged;
                }
            });
            boolean purgeWaited = holdsWithin(() -> purging.isDone() || waitsForALock(purgePid))
                    && !purging.isDone();
            login.commit();

            assertThat(purgeWaited).as("the purge waited for the row the login holds").isTrue();
            assertThat(purging.get(60, TimeUnit.SECONDS))
                    .as("under the lock the row is recent again, and the purge leaves it")
                    .isZero();
        }
        assertThat(failuresOf(institution, identifier))
                .as("the login's count survives the purge").isEqualTo(5);
    }

    /** A committed row of a random identifier, last attempted two days ago. */
    private static String seedIdleRow(UUID institution, int failures) throws SQLException {
        try (PurgeSession seed = PurgeSession.open(ADMIN).context(institution)) {
            String identifier = seed.seedLoginBackoff(institution, seed.now().minusDays(2),
                    failures);
            seed.commit();
            return identifier;
        }
    }

    /** What a failed login does with the backoff row: claim it, then save one failure more. */
    private static BackoffState failedLogin(UUID institution, String identifier,
            AtomicInteger pid) throws SQLException {
        try (PurgeSession login = PurgeSession.open(ADMIN).context(institution)) {
            pid.set((int) login.count("select pg_backend_pid()"));
            JooqLoginBackoffStore store = storeOn(login.connection());
            Instant now = login.now().toInstant();
            BackoffState prior = store.claim(new InstitutionId(institution),
                    new IdentifierFingerprint(identifier), now);
            store.save(new InstitutionId(institution), new IdentifierFingerprint(identifier),
                    new BackoffState(prior.consecutiveFailures() + 1, now));
            login.commit();
            return prior;
        }
    }

    private static long purgeOn(PurgeSession session) throws SQLException {
        return session.count(PURGE, session.now().plusDays(1), 1000);
    }

    private static JooqLoginBackoffStore storeOn(Connection connection) {
        return new JooqLoginBackoffStore(DSL.using(connection, SQLDialect.POSTGRES));
    }

    private static long failuresOf(UUID institution, String identifier) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select consecutive_failures from identity_login_backoff
                         where institution_id = ? and identifier_hash = ?
                        """)) {
            statement.setObject(1, institution);
            statement.setString(2, identifier);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : -1;
            }
        }
    }

    /** True when the backend whose process id was recorded is waiting for a lock. */
    private static boolean waitsForALock(AtomicInteger pid) throws SQLException {
        if (pid.get() == 0) {
            return false;
        }
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select count(*) from pg_stat_activity
                         where pid = ? and wait_event_type = 'Lock'
                        """)) {
            statement.setInt(1, pid.get());
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

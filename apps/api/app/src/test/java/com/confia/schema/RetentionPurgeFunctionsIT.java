package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import confia.generated.jooq.routines.IdentityPurgePasswordResetTokens;
import confia.generated.jooq.routines.IdentityPurgeRefreshTokenFamilies;
import com.confia.support.PostgresIntegrationTest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The two {@code SECURITY DEFINER} purge functions of {@code V11} (background-jobs-with-db-scheduler
 * design.md decision 16; specs/build-integrity/spec.md, scenarios BI70 to BI77, BI104 to BI107 and
 * identity scenarios I238 to I251 and I270 to I273), exercised as the real {@code confia_admin_app}
 * role. The behaviour every function shares is written once and run for each of them through
 * {@link Subject}; the rules that belong to refresh-token families alone are in {@link
 * RefreshTokenFamilyPurgeFunctionIT}.
 *
 * <p><b>The database clock rules.</b> The floor of each function is {@code now()} of PostgreSQL, so
 * every test reads {@code now()} once, in the same transaction that seeds the rows and calls the
 * function (inside one transaction {@code now()} does not move), and anchors every instant on it.
 * The cut-off passed as a parameter is computed from that instant, and the boundary is tested at
 * the exact instant, one second before and one second after. No test seeds with the Java clock. Each
 * test runs in a transaction that is rolled back, so nothing persists.
 */
class RetentionPurgeFunctionsIT extends PostgresIntegrationTest {

    private static final String ADMIN = "confia_admin_app";
    private static final Duration FAR_BEFORE_THE_FLOOR = Duration.ofDays(10);
    private static final Pattern FUNCTION_NAME = Pattern.compile("[a-z_]+");

    /** One purge function, with what a test needs to seed rows for it and to read them back. */
    enum Subject {
        PASSWORD_RESET("identity_purge_password_reset_tokens", Duration.ofHours(24)) {
            @Override
            void seedPurgeableAt(PurgeSession session, UUID institution, OffsetDateTime at)
                    throws SQLException {
                session.seedResetToken(institution, session.seedAccount(institution), at);
            }

            @Override
            void seedMany(PurgeSession session, UUID institution, int rows, OffsetDateTime at)
                    throws SQLException {
                UUID account = session.seedAccount(institution);
                session.execute("""
                        insert into identity_password_reset_token
                            (institution_id, id, account_id, token_hash, issued_at, expires_at,
                             superseded_at)
                        select ?, gen_random_uuid(), ?, lpad(to_hex(g), 64, '0'),
                               ?::timestamptz - interval '30 minutes', ?::timestamptz,
                               ?::timestamptz - interval '30 minutes'
                          from generate_series(1, ?) g
                        """, institution, account, at, at, at, rows);
            }

            @Override
            long remaining(PurgeSession session, UUID institution) throws SQLException {
                return session.count("select count(*) from identity_password_reset_token "
                        + "where institution_id = ?", institution);
            }

            @Override
            long purgeUsing(String function, PurgeSession session, OffsetDateTime cutoff,
                    int limit) throws SQLException {
                return session.count("select " + checked(function) + "(?, ?)", cutoff, limit);
            }

            @Override
            long purgeThroughTheGeneratedRoutine(PurgeSession session, OffsetDateTime cutoff,
                    int limit) {
                IdentityPurgePasswordResetTokens routine = new IdentityPurgePasswordResetTokens();
                routine.setPCutoff(cutoff);
                routine.setPLimit(limit);
                routine.execute(DSL.using(session.connection(), SQLDialect.POSTGRES)
                        .configuration());
                return routine.getReturnValue();
            }
        },
        REFRESH_FAMILIES("identity_purge_refresh_token_families", Duration.ofDays(30)) {
            @Override
            void seedPurgeableAt(PurgeSession session, UUID institution, OffsetDateTime at)
                    throws SQLException {
                UUID family = session.seedFamily(institution, session.seedAccount(institution),
                        at, at.plus(365, ChronoUnit.DAYS), at);
                session.seedOpenToken(institution, family);
            }

            @Override
            void seedMany(PurgeSession session, UUID institution, int rows, OffsetDateTime at)
                    throws SQLException {
                UUID account = session.seedAccount(institution);
                session.execute("""
                        insert into identity_refresh_token_family
                            (institution_id, id, account_id, second_factor_verified,
                             user_agent_fingerprint, created_at, last_used_at,
                             absolute_expires_at, revoked_at, revoked_reason)
                        select ?, gen_random_uuid(), ?, false, ?,
                               ?::timestamptz - interval '1 day', ?::timestamptz - interval '1 day',
                               ?::timestamptz + interval '365 days', ?::timestamptz,
                               'password_change'
                          from generate_series(1, ?) g
                        """, institution, account, PurgeSession.ZERO_FINGERPRINT, at, at, at, at,
                        rows);
            }

            @Override
            long remaining(PurgeSession session, UUID institution) throws SQLException {
                return session.count("select count(*) from identity_refresh_token_family "
                        + "where institution_id = ?", institution);
            }

            @Override
            long purgeUsing(String function, PurgeSession session, OffsetDateTime cutoff,
                    int limit) throws SQLException {
                return session.count("select purged_families from " + checked(function)
                        + "(?, ?)", cutoff, limit);
            }

            @Override
            long purgeThroughTheGeneratedRoutine(PurgeSession session, OffsetDateTime cutoff,
                    int limit) {
                IdentityPurgeRefreshTokenFamilies routine = new IdentityPurgeRefreshTokenFamilies();
                routine.setPCutoff(cutoff);
                routine.setPLimit(limit);
                routine.execute(DSL.using(session.connection(), SQLDialect.POSTGRES)
                        .configuration());
                return routine.getPurgedFamilies();
            }
        };

        private final String function;
        private final Duration floor;

        Subject(String function, Duration floor) {
            this.function = function;
            this.floor = floor;
        }

        /** Seeds one purgeable unit whose purge instant is exactly {@code at}. */
        abstract void seedPurgeableAt(PurgeSession session, UUID institution, OffsetDateTime at)
                throws SQLException;

        abstract void seedMany(PurgeSession session, UUID institution, int rows,
                OffsetDateTime at) throws SQLException;

        /** Rows left of the table the batch counts (tokens, or families). */
        abstract long remaining(PurgeSession session, UUID institution) throws SQLException;

        /** The count the function returns, calling {@code function} (a name from this test). */
        abstract long purgeUsing(String function, PurgeSession session, OffsetDateTime cutoff,
                int limit) throws SQLException;

        abstract long purgeThroughTheGeneratedRoutine(PurgeSession session,
                OffsetDateTime cutoff, int limit);

        long purge(PurgeSession session, OffsetDateTime cutoff, int limit) throws SQLException {
            return purgeUsing(function, session, cutoff, limit);
        }

        String call() {
            return function;
        }

        /** The instant the floor protects from: nothing newer than this may ever be deleted. */
        OffsetDateTime floorAt(PurgeSession session) {
            return session.now().minus(floor);
        }

        private static String checked(String function) {
            if (!FUNCTION_NAME.matcher(function).matches()) {
                throw new IllegalArgumentException("not a function name of this test: " + function);
            }
            return function;
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void aRowAtTheExactCutoffIsKeptAndOneSecondEarlierIsPurged(Subject subject)
            throws SQLException {
        try (PurgeSession session = PurgeSession.open(ADMIN)) {
            assertCutoffBoundary(subject, subject.call(), session);
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void aCutoffInTheFutureNeverDeletesARowYoungerThanTheFloor(Subject subject)
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime floor = subject.floorAt(session);
            subject.seedPurgeableAt(session, institution, floor.minusSeconds(1));
            subject.seedPurgeableAt(session, institution, floor);
            subject.seedPurgeableAt(session, institution, floor.plusSeconds(1));
            subject.seedPurgeableAt(session, institution, session.now());

            long purged = subject.purge(session, session.now().plusDays(1), 100);

            assertThat(purged).as("only the row older than the floor goes").isEqualTo(1);
            assertThat(subject.remaining(session, institution))
                    .as("the row at the exact floor and the two younger ones stay").isEqualTo(3);
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void theCallerCanOnlyMoveTheCutoffBackwards(Subject subject) throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime floor = subject.floorAt(session);
            subject.seedPurgeableAt(session, institution, floor.minus(2, ChronoUnit.DAYS));
            subject.seedPurgeableAt(session, institution, floor.minus(1, ChronoUnit.DAYS));

            long withAnEarlierCutoff = subject.purge(session, floor.minus(36, ChronoUnit.HOURS), 100);
            assertThat(withAnEarlierCutoff).as("an earlier cut-off is honoured").isEqualTo(1);

            long withALaterCutoff = subject.purge(session, session.now().plusDays(1), 100);
            assertThat(withALaterCutoff).as("a later one is capped at the floor").isEqualTo(1);
            assertThat(subject.remaining(session, institution)).isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void withoutAnInstitutionTheFunctionFailsAndDeletesNothing(Subject subject)
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = subject.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
            subject.seedPurgeableAt(session, institution, old);

            session.context(null);
            PurgeSession.Failure failure = session.failureOf("select " + subject.call() + "(?, ?)",
                    session.now().plusDays(1), 100);
            assertThat(failure).as("a purge with no institution must not succeed as zero rows")
                    .isNotNull();
            assertThat(failure.sqlState()).isEqualTo("P0001");
            assertThat(failure.message()).contains("confia_purge_context_missing");

            session.context(institution);
            assertThat(subject.remaining(session, institution)).as("nothing was deleted")
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void anyActorButSystemIsRejectedAndNothingIsDeleted(Subject subject) throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = subject.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
            subject.seedPurgeableAt(session, institution, old);

            for (String actor : new String[] {"staff", "", "portal"}) {
                session.actor(actor);
                PurgeSession.Failure failure = session.failureOf("select " + subject.call()
                        + "(?, ?)", session.now().plusDays(1), 100);
                assertThat(failure).as("actor '%s' must be rejected", actor).isNotNull();
                assertThat(failure.sqlState()).isEqualTo("P0001");
                assertThat(failure.message()).contains("confia_purge_actor_not_system");
            }

            session.actor("system");
            assertThat(subject.remaining(session, institution)).as("nothing was deleted")
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void anotherInstitutionIsLeftIntact(Subject subject) throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN)) {
            OffsetDateTime old = subject.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
            session.asSystemOf(institutionB);
            subject.seedPurgeableAt(session, institutionB, old);
            session.asSystemOf(institutionA);
            subject.seedPurgeableAt(session, institutionA, old);

            long purged = subject.purge(session, session.now().plusDays(1), 100);

            assertThat(purged).isEqualTo(1);
            assertThat(subject.remaining(session, institutionA)).isZero();
            session.context(institutionB);
            assertThat(subject.remaining(session, institutionB))
                    .as("B's equally old row is untouched").isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void theBatchRespectsTheLimitAndAlwaysTakesAtLeastOne(Subject subject) throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = subject.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
            for (int i = 0; i < 5; i++) {
                subject.seedPurgeableAt(session, institution, old);
            }

            assertThat(subject.purge(session, session.now().plusDays(1), 2)).isEqualTo(2);
            assertThat(subject.remaining(session, institution)).isEqualTo(3);
            assertThat(subject.purge(session, session.now().plusDays(1), 0))
                    .as("a limit of zero is raised to one").isEqualTo(1);
            assertThat(subject.purge(session, session.now().plusDays(1), -7))
                    .as("a negative limit is raised to one").isEqualTo(1);
            assertThat(subject.remaining(session, institution)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(Subject.class)
    void theBatchIsCappedAtTenThousandWhateverTheCallerAsks(Subject subject)
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = subject.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
            subject.seedMany(session, institution, 10_001, old);

            long purged = subject.purge(session, session.now().plusDays(1), Integer.MAX_VALUE);

            assertThat(purged).isEqualTo(10_000);
            assertThat(subject.remaining(session, institution)).isEqualTo(1);
        }
    }

    /** Probe P-12: the routine jOOQ generates from the migration works, parameters bound. */
    @ParameterizedTest
    @EnumSource(Subject.class)
    void theRoutineGeneratedByJooqCallsTheFunction(Subject subject) throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = subject.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
            subject.seedPurgeableAt(session, institution, old);
            subject.seedPurgeableAt(session, institution, session.now());

            long purged = subject.purgeThroughTheGeneratedRoutine(session,
                    session.now().plusDays(1), 100);

            assertThat(purged).isEqualTo(1);
            assertThat(subject.remaining(session, institution)).isEqualTo(1);
        }
    }

    /**
     * Scenario BI70: a caller whose {@code search_path} puts a look-alike table first cannot divert
     * the function. A temporary table is searched before any schema unless it is named, and a
     * temporary table is something the application role may create.
     */
    @Test
    void aHostileSearchPathOfTheCallerDoesNotDivertTheFunction() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (PurgeSession session = PurgeSession.open(ADMIN).asSystemOf(institution)) {
            OffsetDateTime old = Subject.PASSWORD_RESET.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
            Subject.PASSWORD_RESET.seedPurgeableAt(session, institution, old);
            session.execute("create temporary table identity_password_reset_token "
                    + "(like public.identity_password_reset_token) on commit drop");
            session.execute("""
                    insert into pg_temp.identity_password_reset_token
                        (institution_id, id, account_id, token_hash, issued_at, expires_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, institution, UUID.randomUUID(), UUID.randomUUID(), "f".repeat(64),
                    old.minusHours(1), old);
            session.execute("set local search_path = pg_temp, public");

            long purged = session.count("select public.identity_purge_password_reset_tokens(?, ?)",
                    session.now().plusDays(1), 100);

            assertThat(purged).as("the real table was purged").isEqualTo(1);
            assertThat(session.count("select count(*) from public.identity_password_reset_token"))
                    .isZero();
            assertThat(session.count("select count(*) from pg_temp.identity_password_reset_token"))
                    .as("the look-alike table was not touched").isEqualTo(1);
        }
    }

    /**
     * Scenario BI77, the negative control: the boundary check above is only worth something if it
     * fails for a function that ignores the cut-off. This variant keeps the floor and drops the
     * cut-off, and the same check must reject it.
     */
    @Test
    void theBoundaryCheckRejectsAVariantThatIgnoresTheCutoff() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("11")) {
            try (PurgeSession owner = PurgeSession.open(database, "confia_owner")) {
                owner.execute("""
                        create function identity_purge_password_reset_tokens_uncut(
                                p_cutoff timestamptz, p_limit integer)
                            returns bigint language plpgsql security definer
                            set search_path = pg_catalog, public
                        as $$
                        declare v_count bigint;
                        begin
                            delete from public.identity_password_reset_token
                             where institution_id = nullif(current_setting('app.institution_id', true), '')::uuid
                               and expires_at < now() - interval '24 hours';
                            get diagnostics v_count = row_count;
                            return v_count;
                        end
                        $$
                        """);
                owner.execute("grant execute on function "
                        + "identity_purge_password_reset_tokens_uncut(timestamptz, integer) "
                        + "to confia_admin_app");
                owner.commit();
            }
            try (PurgeSession session = PurgeSession.open(database, ADMIN)) {
                assertThatThrownBy(() -> assertCutoffBoundary(Subject.PASSWORD_RESET,
                        "identity_purge_password_reset_tokens_uncut", session))
                        .as("a function that ignores the cut-off must fail the boundary check")
                        .isInstanceOf(AssertionError.class);
            }
            try (PurgeSession session = PurgeSession.open(database, ADMIN)) {
                assertCutoffBoundary(Subject.PASSWORD_RESET, Subject.PASSWORD_RESET.call(),
                        session);
            }
        }
    }

    /**
     * Three rows around a cut-off that is far older than the floor: one second before it, at it, one
     * second after it. Only the first goes, so the comparison is strict and exact.
     */
    private static void assertCutoffBoundary(Subject subject, String function,
            PurgeSession session) throws SQLException {
        UUID institution = UUID.randomUUID();
        session.asSystemOf(institution);
        OffsetDateTime cutoff = subject.floorAt(session).minus(FAR_BEFORE_THE_FLOOR);
        subject.seedPurgeableAt(session, institution, cutoff.minusSeconds(1));
        subject.seedPurgeableAt(session, institution, cutoff);
        subject.seedPurgeableAt(session, institution, cutoff.plusSeconds(1));

        long purged = subject.purgeUsing(function, session, cutoff, 100);

        assertThat(purged).as("only the row one second before the cut-off is purged").isEqualTo(1);
        assertThat(subject.remaining(session, institution))
                .as("the row at the exact cut-off and the one after it are kept").isEqualTo(2);
    }
}

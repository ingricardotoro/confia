package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import com.confia.support.StaffRoleFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The record of the import batches (V23) and the external reference of the students against a real PostgreSQL
 * (docs/cortes/imports-students.md): the checks on the closed sets, the fingerprint and the counts, the shape of a
 * simulation and of an application, the composite key that keeps an application inside the institution of its
 * simulation, row-level security, the privileges (insert and read only), and the unique reference per institution.
 * Constraint rejections run as {@code confia_owner}, which holds every privilege, so what refuses the row is the
 * constraint under test and never a missing grant.
 */
class ImportsBatchSchemaIT extends CommittingPostgresIntegrationTest {

    private static final String TABLE = "imports_batch";
    private static final String SHA = "ab".repeat(32);

    private interface SqlAction {
        void run() throws SQLException;
    }

    private static SQLException rejected(Connection connection, SqlAction statement) throws SQLException {
        Savepoint before = connection.setSavepoint();
        try {
            statement.run();
        } catch (SQLException e) {
            connection.rollback(before);
            return e;
        }
        throw new AssertionError("the database accepted a statement it had to refuse");
    }

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    /** A simulation; the counts are 10 = 6 + 3 + 1. */
    private static UUID simulation(Connection c, UUID institution) throws SQLException {
        return batch(c, institution, "STUDENTS", "VALIDATED", SHA, 10, 6, 3, 0, 1, null, false);
    }

    private static UUID batch(Connection c, UUID institution, String kind, String status, String sha, int total,
            int valid, int rejected, int created, int skipped, UUID simulation, boolean applied) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, """
                insert into imports_batch (institution_id, id, kind, status, file_sha256, total_rows, valid_rows,
                    rejected_rows, created_rows, skipped_rows, simulation_id, created_by, applied_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, case when ? then clock_timestamp() end)
                """, institution, id, kind, status, sha, total, valid, rejected, created, skipped, simulation,
                UUID.randomUUID(), applied);
        return id;
    }

    private static void student(Connection c, UUID institution, String code, String externalRef) throws SQLException {
        exec(c, """
                insert into students_student (institution_id, id, student_code, first_name, last_name, birth_date,
                    sex, external_ref)
                values (?, ?, ?, 'Ana', 'Perez', date '2012-05-20', 'F', ?)
                """, institution, UUID.randomUUID(), code, externalRef);
    }

    // --- The batch ---

    @Test
    void theKindAndTheStatusHaveTheirClosedSetsAndTheFingerprintIsSha256InLowercaseHex() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            assertThat(simulation(owner, institution)).isNotNull();
            assertThat(rejected(owner, () -> batch(owner, institution, "GUARDIANS", "VALIDATED", SHA, 0, 0, 0, 0, 0,
                    null, false)).getMessage()).contains("imports_batch_kind_chk");
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "PENDING", SHA, 0, 0, 0, 0, 0,
                    null, false)).getMessage()).contains("imports_batch_status_chk");
            for (String sha : List.of("AB".repeat(32), "ab".repeat(31), "zz".repeat(32), "")) {
                assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", sha, 0, 0, 0, 0,
                        0, null, false)).getMessage()).as(sha).contains("imports_batch_sha_chk");
            }
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", "ab".repeat(33), 0, 0,
                    0, 0, 0, null, false)).getSQLState()).as("longer than the column").isEqualTo("22001");
            owner.rollback();
        }
    }

    @Test
    void theCountsAddUpAreNotNegativeAndNeverExceedTenThousandRows() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            assertThat(batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 0, 0, 0, 0, 0, null, false))
                    .as("an empty batch is consistent").isNotNull();
            assertThat(batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 10_000, 10_000, 0, 0, 0, null, false))
                    .isNotNull();
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 10, 6, 3, 0, 2,
                    null, false)).getMessage()).contains("imports_batch_counts_chk");
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 10, 11, -1, 0, 0,
                    null, false)).getMessage()).contains("imports_batch_counts_chk");
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 10_001, 10_001,
                    0, 0, 0, null, false)).getMessage()).contains("imports_batch_counts_chk");
            owner.rollback();
        }
    }

    @Test
    void aSimulationCreatesNothingAndIsNotAppliedAndAnApplicationCreatedWhatWasValid() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID simulation = simulation(owner, institution);
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 10, 6, 3, 6, 1,
                    null, false)).getMessage()).contains("imports_batch_status_shape_chk");
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 10, 6, 3, 0, 1,
                    null, true)).getMessage()).contains("imports_batch_status_shape_chk");
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "VALIDATED", SHA, 10, 6, 3, 0, 1,
                    simulation, false)).getMessage()).contains("imports_batch_status_shape_chk");
            assertThat(batch(owner, institution, "STUDENTS", "APPLIED", SHA, 10, 6, 3, 6, 1, simulation, true))
                    .isNotNull();
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "APPLIED", SHA, 10, 6, 3, 5, 1,
                    simulation, true)).getMessage()).contains("imports_batch_status_shape_chk");
            assertThat(rejected(owner, () -> batch(owner, institution, "STUDENTS", "APPLIED", SHA, 10, 6, 3, 6, 1,
                    simulation, false)).getMessage()).contains("imports_batch_status_shape_chk");
            owner.rollback();
        }
    }

    @Test
    void anApplicationCannotPointToTheSimulationOfAnotherInstitution() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID theirSimulation;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs)) {
            theirSimulation = simulation(owner, theirs);
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(mine)) {
            SQLException refusal = rejected(owner, () -> batch(owner, mine, "STUDENTS", "APPLIED", SHA, 10, 6, 3, 6, 1,
                    theirSimulation, true));
            assertThat(refusal.getMessage()).contains("imports_batch_simulation_fk");
            owner.rollback();
        }
    }

    @Test
    void theTableHoldsOnlyCountsAndTheFingerprintAndRowLevelSecurityIsForced() throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(UUID.randomUUID())) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rows = owner.createStatement().executeQuery(
                    "select column_name from information_schema.columns where table_name = '" + TABLE
                            + "' order by ordinal_position")) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
            assertThat(columns).containsExactly("institution_id", "id", "kind", "status", "file_sha256", "total_rows",
                    "valid_rows", "rejected_rows", "created_rows", "skipped_rows", "simulation_id", "created_by",
                    "created_at", "applied_at");
            try (ResultSet rows = owner.createStatement().executeQuery(
                    "select relrowsecurity, relforcerowsecurity from pg_class where relname = '" + TABLE + "'")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.getBoolean(2)).isTrue();
            }
            owner.rollback();
        }
    }

    @Test
    void theApplicationRoleInsertsAndReadsAndHasNoUpdateDeleteOrTruncatePrivilege() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);
            simulation(admin, institution);
            try (ResultSet rows = admin.createStatement().executeQuery("select count(*) from " + TABLE)) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
            for (String sql : List.of("update " + TABLE + " set status = 'APPLIED'", "delete from " + TABLE,
                    "truncate " + TABLE, "update " + TABLE + " set skipped_rows = 0")) {
                SQLException refused = rejected(admin, () -> admin.createStatement().execute(sql));
                assertThat(refused.getSQLState()).as(sql).isEqualTo("42501");
            }
            admin.rollback();
        }
    }

    @Test
    void eachInstitutionSeesOnlyItsOwnBatchesAndTheApplicationCannotWriteForAnother() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seed(theirs);
        seed(mine);

        assertThat(visibleInstitutions(mine)).containsOnly(mine).isNotEmpty();
        assertThat(visibleInstitutions(theirs)).containsOnly(theirs).isNotEmpty();
        assertThat(visibleInstitutions(null)).as("no context").isEmpty();
        assertThat(visibleInstitutions(UUID.randomUUID())).as("a stranger").isEmpty();
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);
            SQLException foreign = rejected(admin, () -> simulation(admin, theirs));
            assertThat(foreign.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            admin.rollback();
        }
    }

    @Test
    void theReadOnlyRoleReadsTheBatchesButWritesNothingAndThePortalSeesNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            try (ResultSet rows = readonly.createStatement().executeQuery("select * from " + TABLE)) {
                assertThat(rows.next()).isTrue();
            }
            SQLException insert = rejected(readonly, () -> simulation(readonly, institution));
            assertThat(insert.getSQLState()).isEqualTo("42501");
            readonly.rollback();
        }
        try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            portal.setAutoCommit(false);
            SQLException select = rejected(portal,
                    () -> portal.createStatement().executeQuery("select 1 from " + TABLE).close());
            assertThat(select.getSQLState()).isEqualTo("42501");
            portal.rollback();
        }
    }

    // --- The external reference of the student ---

    @Test
    void theExternalReferenceIsUniquePerInstitutionAndStudentsWithoutOneCoexist() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(mine)) {
            student(owner, mine, "E-000001", "LEGACY-1");
            student(owner, mine, "E-000002", null);
            student(owner, mine, "E-000003", null);
            student(owner, mine, "E-000004", "LEGACY-2");
            SQLException duplicate = rejected(owner, () -> student(owner, mine, "E-000005", "LEGACY-1"));
            assertThat(duplicate.getSQLState()).isEqualTo("23505");
            assertThat(duplicate.getMessage()).contains("students_student_external_ref_uq");
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs)) {
            student(owner, theirs, "E-000001", "LEGACY-1");
            owner.commit();
        }
    }

    @Test
    void theExternalReferenceIsNeverBlankNorPaddedNorLongerThanSixtyFourCharacters() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            student(owner, institution, "E-000001", "R".repeat(64));
            for (String reference : List.of("", " LEGACY", "LEGACY ")) {
                SQLException refusal = rejected(owner, () -> student(owner, institution, "E-000002", reference));
                assertThat(refusal.getMessage()).as("[" + reference + "]")
                        .contains("students_student_external_ref_chk");
            }
            SQLException tooLong = rejected(owner, () -> student(owner, institution, "E-000002", "R".repeat(65)));
            assertThat(tooLong.getSQLState()).isEqualTo("22001");
            owner.rollback();
        }
    }

    @Test
    void theReadOnlyRoleStillCannotReadTheExternalReference() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            student(owner, institution, "E-000001", "LEGACY-1");
            owner.commit();
        }
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            SQLException refusal = rejected(readonly, () -> readonly.createStatement()
                    .executeQuery("select external_ref from students_student").close());
            assertThat(refusal.getSQLState()).isEqualTo("42501");
            readonly.rollback();
        }
    }

    // --- Seeding and reading ---

    private static void seed(UUID institution) throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            simulation(owner, institution);
            owner.commit();
        }
    }

    private static List<UUID> visibleInstitutions(UUID context) throws SQLException {
        List<UUID> seen = new ArrayList<>();
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            if (context != null) {
                StaffRoleFixtures.fixInstitution(admin, context);
            }
            try (Statement statement = admin.createStatement();
                    ResultSet rows = statement.executeQuery("select institution_id from " + TABLE)) {
                while (rows.next()) {
                    seen.add(rows.getObject(1, UUID.class));
                }
            }
            admin.rollback();
        }
        return seen;
    }
}

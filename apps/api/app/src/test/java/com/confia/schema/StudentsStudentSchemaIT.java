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
 * The two tables of the student registry (V17) against a real PostgreSQL
 * (docs/cortes/students-registry.md): the constraints that enforce the rules of the cut, the row-level
 * security that keeps one institution from reading or writing the rows of another, and the privileges (no
 * one deletes, the read-only role never sees the encrypted document or its blind index, the portal sees
 * nothing). Constraint rejections run as {@code confia_owner}, which holds every privilege, so what
 * refuses the row is the constraint under test and never a missing grant.
 */
class StudentsStudentSchemaIT extends CommittingPostgresIntegrationTest {

    private static final List<String> TABLES = List.of("students_student", "students_student_code_counter");
    private static final String BLIND_A = "a".repeat(64);
    private static final String BLIND_B = "b".repeat(64);

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

    private static void student(Connection c, UUID institution, UUID id, String code, String sex, String status,
            String encrypted, String blindIndex, String last4) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                insert into students_student (institution_id, id, student_code, first_name, last_name,
                    birth_date, sex, status, national_id_encrypted, national_id_blind_index, national_id_last4)
                values (?, ?, ?, 'Ana', 'Perez', date '2012-05-20', ?, ?, ?, ?, ?)
                """)) {
            s.setObject(1, institution);
            s.setObject(2, id);
            s.setString(3, code);
            s.setString(4, sex);
            s.setString(5, status);
            s.setString(6, encrypted);
            s.setString(7, blindIndex);
            s.setString(8, last4);
            s.executeUpdate();
        }
    }

    private static void plain(Connection c, UUID institution, String code) throws SQLException {
        student(c, institution, UUID.randomUUID(), code, "F", "ACTIVE", null, null, null);
    }

    // --- Constraints ---

    @Test
    void theSexAndTheStatusHaveTheirClosedSetsAndTheNamesAreNotBlank() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            SQLException sex = rejected(owner, () -> student(owner, institution, UUID.randomUUID(), "A", "X",
                    "ACTIVE", null, null, null));
            SQLException status = rejected(owner, () -> student(owner, institution, UUID.randomUUID(), "A", "F",
                    "DELETED", null, null, null));
            SQLException blank = rejected(owner, () -> {
                try (PreparedStatement s = owner.prepareStatement("""
                        insert into students_student (institution_id, id, student_code, first_name, last_name,
                            birth_date, sex) values (?, ?, 'A', '   ', 'Perez', date '2012-05-20', 'F')
                        """)) {
                    s.setObject(1, institution);
                    s.setObject(2, UUID.randomUUID());
                    s.executeUpdate();
                }
            });

            assertThat(sex.getSQLState()).isEqualTo("23514");
            assertThat(sex.getMessage()).contains("students_student_sex_chk");
            assertThat(status.getSQLState()).isEqualTo("23514");
            assertThat(status.getMessage()).contains("students_student_status_chk");
            assertThat(blank.getSQLState()).isEqualTo("23514");
            assertThat(blank.getMessage()).contains("students_student_names_chk");
            for (String accepted : List.of("F", "M", "UNSPECIFIED")) {
                student(owner, institution, UUID.randomUUID(), "S-" + accepted, accepted, "ACTIVE", null, null, null);
            }
            owner.rollback();
        }
    }

    @Test
    void theThreeMembersOfTheDocumentArePresentTogetherOrAbsentTogether() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            for (String[] partial : new String[][] {{"v1:x", null, null}, {null, BLIND_A, null}, {null, null, "1234"},
                    {"v1:x", BLIND_A, null}, {"v1:x", null, "1234"}, {null, BLIND_A, "1234"}}) {
                SQLException refusal = rejected(owner, () -> student(owner, institution, UUID.randomUUID(), "P",
                        "F", "ACTIVE", partial[0], partial[1], partial[2]));
                assertThat(refusal.getSQLState()).isEqualTo("23514");
                assertThat(refusal.getMessage()).contains("students_student_national_id_chk");
            }
            student(owner, institution, UUID.randomUUID(), "N", "F", "ACTIVE", null, null, null);
            student(owner, institution, UUID.randomUUID(), "W", "F", "ACTIVE", "v1:x", BLIND_A, "1234");
            owner.rollback();
        }
    }

    @Test
    void theCodeIsUniquePerInstitutionOnly() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            plain(owner, institution, "E-000001");

            SQLException duplicate = rejected(owner, () -> plain(owner, institution, "E-000001"));

            assertThat(duplicate.getSQLState()).isEqualTo("23505");
            assertThat(duplicate.getMessage()).contains("students_student_code_uq");
            StaffRoleFixtures.fixInstitution(owner, other);
            plain(owner, other, "E-000001");
            owner.rollback();
        }
    }

    @Test
    void theBlindIndexIsUniquePerInstitutionWhilePresentAndAbsentOnesNeverCollide() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            student(owner, institution, UUID.randomUUID(), "A", "F", "ACTIVE", "v1:x", BLIND_A, "1234");
            plain(owner, institution, "B");
            plain(owner, institution, "C");

            SQLException duplicate = rejected(owner, () -> student(owner, institution, UUID.randomUUID(), "D", "F",
                    "ACTIVE", "v1:y", BLIND_A, "1234"));

            assertThat(duplicate.getSQLState()).isEqualTo("23505");
            assertThat(duplicate.getMessage()).contains("students_student_national_id_uq");
            student(owner, institution, UUID.randomUUID(), "E", "F", "ACTIVE", "v1:y", BLIND_B, "1234");
            StaffRoleFixtures.fixInstitution(owner, other);
            student(owner, other, UUID.randomUUID(), "A", "F", "ACTIVE", "v1:x", BLIND_A, "1234");
            owner.rollback();
        }
    }

    @Test
    void theCounterNeverGoesBelowZero() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            SQLException refusal = rejected(owner, () -> {
                try (PreparedStatement s = owner.prepareStatement(
                        "insert into students_student_code_counter (institution_id, last_value) values (?, -1)")) {
                    s.setObject(1, institution);
                    s.executeUpdate();
                }
            });

            assertThat(refusal.getSQLState()).isEqualTo("23514");
            assertThat(refusal.getMessage()).contains("students_student_code_counter_chk");
            owner.rollback();
        }
    }

    // --- Row-level security ---

    @Test
    void aUserOfAnotherInstitutionReadsNoRowAndWithoutContextReadsNone() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seed(mine);
        seed(theirs);

        for (String table : TABLES) {
            assertThat(visibleInstitutions(table, mine)).as("%s as mine", table).containsOnly(mine).isNotEmpty();
            assertThat(visibleInstitutions(table, theirs)).as("%s as theirs", table).containsOnly(theirs).isNotEmpty();
            assertThat(visibleInstitutions(table, null)).as("%s with no context", table).isEmpty();
            assertThat(visibleInstitutions(table, UUID.randomUUID())).as("%s as a stranger", table).isEmpty();
        }
    }

    @Test
    void theApplicationRoleCannotWriteARowOfAnotherInstitutionNorTouchOneThroughAnUpdate() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seed(theirs);
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);

            SQLException foreignInsert = rejected(admin, () -> plain(admin, theirs, "ZZZ"));
            SQLException foreignCounter = rejected(admin, () -> {
                try (PreparedStatement s = admin.prepareStatement(
                        "insert into students_student_code_counter (institution_id, last_value) values (?, 1)")) {
                    s.setObject(1, theirs);
                    s.executeUpdate();
                }
            });
            assertThat(foreignInsert.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            assertThat(foreignCounter.getSQLState()).isEqualTo("42501");
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update students_student set status = 'INACTIVE'")).isZero();
                assertThat(s.executeUpdate("update students_student_code_counter set last_value = 99")).isZero();
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs);
                ResultSet rows = owner.createStatement()
                        .executeQuery("select count(*) from students_student where status = 'ACTIVE'")) {
            rows.next();
            assertThat(rows.getLong(1)).as("the rows of the other institution stayed active").isEqualTo(1);
            owner.rollback();
        }
    }

    // --- Privileges ---

    @Test
    void nobodyDeletesTheApplicationReadsAndWritesAndThePortalSeesNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        for (String table : TABLES) {
            try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
                admin.setAutoCommit(false);
                StaffRoleFixtures.fixInstitution(admin, institution);
                SQLException delete = rejected(admin, () -> admin.createStatement().execute("delete from " + table));
                assertThat(delete.getSQLState()).as("DELETE on %s", table).isEqualTo("42501");
                SQLException truncate = rejected(admin, () -> admin.createStatement().execute("truncate " + table));
                assertThat(truncate.getSQLState()).as("TRUNCATE on %s", table).isEqualTo("42501");
                try (ResultSet rows = admin.createStatement().executeQuery("select count(*) from " + table)) {
                    rows.next();
                    assertThat(rows.getLong(1)).as("SELECT on %s", table).isEqualTo(1);
                }
                admin.rollback();
            }
            try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
                portal.setAutoCommit(false);
                SQLException select = rejected(portal,
                        () -> portal.createStatement().executeQuery("select 1 from " + table).close());
                assertThat(select.getSQLState()).as("SELECT by the portal role on %s", table).isEqualTo("42501");
                portal.rollback();
            }
        }
    }

    @Test
    void theReadOnlyRoleReadsTheStudentsButNeverTheEncryptedDocumentNorItsBlindIndexAndWritesNothing()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            try (ResultSet rows = readonly.createStatement().executeQuery(
                    "select student_code, first_name, last_name, birth_date, national_id_last4 from students_student")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("national_id_last4")).isEqualTo("1234");
            }
            for (String column : List.of("national_id_encrypted", "national_id_blind_index")) {
                SQLException refusal = rejected(readonly, () -> readonly.createStatement()
                        .executeQuery("select " + column + " from students_student").close());
                assertThat(refusal.getSQLState()).as("SELECT of %s", column).isEqualTo("42501");
            }
            SQLException star = rejected(readonly,
                    () -> readonly.createStatement().executeQuery("select * from students_student").close());
            assertThat(star.getSQLState()).as("select * touches the two columns").isEqualTo("42501");
            SQLException update = rejected(readonly,
                    () -> readonly.createStatement().execute("update students_student set status = 'INACTIVE'"));
            assertThat(update.getSQLState()).isEqualTo("42501");
            readonly.rollback();
        }
    }

    // --- Seeding and reading ---

    /** One student with a document and the counter row, committed under the institution given. */
    private static void seed(UUID institution) throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            student(owner, institution, UUID.randomUUID(), "E-000001", "F", "ACTIVE", "v1:x", BLIND_A, "1234");
            try (PreparedStatement s = owner.prepareStatement(
                    "insert into students_student_code_counter (institution_id, last_value) values (?, 1)")) {
                s.setObject(1, institution);
                s.executeUpdate();
            }
            owner.commit();
        }
    }

    /** Every institution the table shows to {@code confia_admin_app} under the given context. */
    private static List<UUID> visibleInstitutions(String table, UUID context) throws SQLException {
        List<UUID> seen = new ArrayList<>();
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            if (context != null) {
                StaffRoleFixtures.fixInstitution(admin, context);
            }
            try (Statement statement = admin.createStatement();
                    ResultSet rows = statement.executeQuery("select institution_id from " + table)) {
                while (rows.next()) {
                    seen.add(rows.getObject(1, UUID.class));
                }
            }
            admin.rollback();
        }
        return seen;
    }
}

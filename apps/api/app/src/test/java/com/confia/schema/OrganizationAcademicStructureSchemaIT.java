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
 * The four tables of the academic structure (V16) against a real PostgreSQL
 * (docs/cortes/organization-academic-structure.md): the constraints that enforce the rules of the cut,
 * the row-level security that keeps one institution from reading or writing the rows of another, and
 * the privileges (no one deletes, the portal sees nothing). Constraint rejections run as
 * {@code confia_owner}, which holds every privilege, so what refuses the row is the constraint under
 * test and never a missing grant; a rejected statement is rolled back to a savepoint so one test can
 * show the rejection and then the acceptance of the same row made valid.
 */
class OrganizationAcademicStructureSchemaIT extends CommittingPostgresIntegrationTest {

    private static final List<String> TABLES = List.of("organization_academic_year",
            "organization_modality", "organization_grade", "organization_section");

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

    private static void year(Connection c, UUID institution, UUID id, String name, String start,
            String end, String status) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                insert into organization_academic_year (institution_id, id, name, start_date, end_date, status)
                values (?, ?, ?, cast(? as date), cast(? as date), ?)
                """)) {
            s.setObject(1, institution);
            s.setObject(2, id);
            s.setString(3, name);
            s.setString(4, start);
            s.setString(5, end);
            s.setString(6, status);
            s.executeUpdate();
        }
    }

    private static void modality(Connection c, UUID institution, UUID id, String code) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "insert into organization_modality (institution_id, id, code, name) values (?, ?, ?, 'Nombre')")) {
            s.setObject(1, institution);
            s.setObject(2, id);
            s.setString(3, code);
            s.executeUpdate();
        }
    }

    private static void grade(Connection c, UUID institution, UUID id, UUID modality, String name,
            int ordinal) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                insert into organization_grade (institution_id, id, modality_id, name, ordinal)
                values (?, ?, ?, ?, ?)
                """)) {
            s.setObject(1, institution);
            s.setObject(2, id);
            s.setObject(3, modality);
            s.setString(4, name);
            s.setInt(5, ordinal);
            s.executeUpdate();
        }
    }

    private static void section(Connection c, UUID institution, UUID id, UUID year, UUID grade,
            String name, Integer capacity) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                insert into organization_section (institution_id, id, academic_year_id, grade_id, name, capacity)
                values (?, ?, ?, ?, ?, ?)
                """)) {
            s.setObject(1, institution);
            s.setObject(2, id);
            s.setObject(3, year);
            s.setObject(4, grade);
            s.setString(5, name);
            s.setObject(6, capacity);
            s.executeUpdate();
        }
    }

    // --- Constraints ---

    @Test
    void aYearEndsAfterItStartsAndHasOneOfTheThreeStatuses() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            SQLException equalDates = rejected(owner, () -> year(owner, institution, UUID.randomUUID(), "a",
                    "2026-02-01", "2026-02-01", "PLANNED"));
            SQLException reversed = rejected(owner, () -> year(owner, institution, UUID.randomUUID(), "a",
                    "2026-03-01", "2026-02-01", "PLANNED"));
            SQLException status = rejected(owner, () -> year(owner, institution, UUID.randomUUID(), "a",
                    "2026-02-01", "2026-03-01", "OPEN"));

            assertThat(equalDates.getSQLState()).isEqualTo("23514");
            assertThat(equalDates.getMessage()).contains("organization_academic_year_dates_chk");
            assertThat(reversed.getSQLState()).isEqualTo("23514");
            assertThat(status.getSQLState()).isEqualTo("23514");
            assertThat(status.getMessage()).contains("organization_academic_year_status_chk");
            year(owner, institution, UUID.randomUUID(), "a", "2026-02-01", "2026-02-02", "PLANNED");
            owner.rollback();
        }
    }

    @Test
    void anInstitutionHasAtMostOneActiveYearButManyPlannedAndClosedOnesAndAnotherMayHaveItsOwn()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            year(owner, institution, UUID.randomUUID(), "a", "2026-01-01", "2026-02-01", "ACTIVE");
            year(owner, institution, UUID.randomUUID(), "b", "2026-03-01", "2026-04-01", "PLANNED");
            year(owner, institution, UUID.randomUUID(), "c", "2026-05-01", "2026-06-01", "PLANNED");
            year(owner, institution, UUID.randomUUID(), "d", "2026-07-01", "2026-08-01", "CLOSED");
            year(owner, institution, UUID.randomUUID(), "e", "2026-09-01", "2026-10-01", "CLOSED");

            SQLException second = rejected(owner, () -> year(owner, institution, UUID.randomUUID(), "f",
                    "2026-11-01", "2026-12-01", "ACTIVE"));

            assertThat(second.getSQLState()).isEqualTo("23505");
            assertThat(second.getMessage()).contains("organization_academic_year_one_active_uq");
            StaffRoleFixtures.fixInstitution(owner, other);
            year(owner, other, UUID.randomUUID(), "a", "2026-01-01", "2026-02-01", "ACTIVE");
            owner.rollback();
        }
    }

    @Test
    void namesCodesAndOrdinalsAreUniqueWithinTheirScopeOnly() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID modality = UUID.randomUUID();
        UUID otherModality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        UUID otherGrade = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            year(owner, institution, year, "2026", "2026-02-01", "2026-11-30", "PLANNED");
            modality(owner, institution, modality, "BACH");
            modality(owner, institution, otherModality, "TEC");
            grade(owner, institution, grade, modality, "Primero", 1);
            grade(owner, institution, otherGrade, modality, "Segundo", 2);
            section(owner, institution, UUID.randomUUID(), year, grade, "A", 30);

            assertThat(rejected(owner, () -> year(owner, institution, UUID.randomUUID(), "2026", "2027-02-01",
                    "2027-11-30", "PLANNED")).getMessage()).contains("organization_academic_year_name_uq");
            assertThat(rejected(owner, () -> modality(owner, institution, UUID.randomUUID(), "BACH"))
                    .getMessage()).contains("organization_modality_code_uq");
            assertThat(rejected(owner, () -> grade(owner, institution, UUID.randomUUID(), modality,
                    "Primero", 9)).getMessage()).contains("organization_grade_name_uq");
            assertThat(rejected(owner, () -> grade(owner, institution, UUID.randomUUID(), modality,
                    "Tercero", 1)).getMessage()).contains("organization_grade_ordinal_uq");
            assertThat(rejected(owner, () -> section(owner, institution, UUID.randomUUID(), year, grade, "A",
                    10)).getMessage()).contains("organization_section_name_uq");

            grade(owner, institution, UUID.randomUUID(), otherModality, "Primero", 1);
            section(owner, institution, UUID.randomUUID(), year, otherGrade, "A", 10);
            owner.rollback();
        }
    }

    @Test
    void aCapacityMustBeAboveZeroWhenPresentAndAnOrdinalBelowZeroIsRefused() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            year(owner, institution, year, "2026", "2026-02-01", "2026-11-30", "PLANNED");
            modality(owner, institution, modality, "BACH");
            grade(owner, institution, grade, modality, "Primero", 1);

            assertThat(rejected(owner, () -> section(owner, institution, UUID.randomUUID(), year, grade, "A", 0))
                    .getMessage()).contains("organization_section_capacity_chk");
            assertThat(rejected(owner, () -> section(owner, institution, UUID.randomUUID(), year, grade, "A", -1))
                    .getMessage()).contains("organization_section_capacity_chk");
            assertThat(rejected(owner, () -> grade(owner, institution, UUID.randomUUID(), modality, "X", -1))
                    .getMessage()).contains("organization_grade_ordinal_chk");
            section(owner, institution, UUID.randomUUID(), year, grade, "A", 1);
            section(owner, institution, UUID.randomUUID(), year, grade, "B", null);
            owner.rollback();
        }
    }

    @Test
    void aGradeOrASectionCannotPointAtARowOfAnotherInstitution() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID theirModality = UUID.randomUUID();
        UUID theirYear = UUID.randomUUID();
        UUID theirGrade = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs)) {
            modality(owner, theirs, theirModality, "BACH");
            year(owner, theirs, theirYear, "2026", "2026-02-01", "2026-11-30", "PLANNED");
            grade(owner, theirs, theirGrade, theirModality, "Primero", 1);
            StaffRoleFixtures.fixInstitution(owner, mine);

            SQLException grade = rejected(owner,
                    () -> grade(owner, mine, UUID.randomUUID(), theirModality, "Primero", 1));
            SQLException section = rejected(owner,
                    () -> section(owner, mine, UUID.randomUUID(), theirYear, theirGrade, "A", 1));

            assertThat(grade.getSQLState()).isEqualTo("23503");
            assertThat(grade.getMessage()).contains("organization_grade_modality_fk");
            assertThat(section.getSQLState()).isEqualTo("23503");
            owner.rollback();
        }
    }

    // --- Row-level security ---

    @Test
    void aUserOfAnotherInstitutionReadsNoRowOfTheStructureAndWithoutContextReadsNone() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seedStructure(mine);
        seedStructure(theirs);

        for (String table : TABLES) {
            assertThat(visibleInstitutions(table, mine)).as("%s as mine", table).containsOnly(mine).isNotEmpty();
            assertThat(visibleInstitutions(table, theirs)).as("%s as theirs", table).containsOnly(theirs)
                    .isNotEmpty();
            assertThat(visibleInstitutions(table, null)).as("%s with no context", table).isEmpty();
            assertThat(visibleInstitutions(table, UUID.randomUUID())).as("%s as a stranger", table).isEmpty();
        }
    }

    @Test
    void theApplicationRoleCannotWriteARowOfAnotherInstitutionNorTouchOneThroughAnUpdate() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seedStructure(theirs);
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);

            SQLException foreignInsert = rejected(admin, () -> modality(admin, theirs, UUID.randomUUID(), "ZZZ"));
            assertThat(foreignInsert.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            for (String table : TABLES) {
                try (Statement s = admin.createStatement()) {
                    assertThat(s.executeUpdate("update " + table + " set is_active = false")).as(table).isZero();
                }
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs);
                ResultSet rows = owner.createStatement()
                        .executeQuery("select count(*) from organization_modality where is_active")) {
            rows.next();
            assertThat(rows.getLong(1)).as("the rows of the other institution stayed active").isEqualTo(1);
            owner.rollback();
        }
    }

    // --- Privileges ---

    @Test
    void nobodyDeletesTheApplicationReadsAndWritesTheReadOnlyRoleOnlyReadsAndThePortalSeesNothing()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        seedStructure(institution);
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
            try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
                readonly.setAutoCommit(false);
                StaffRoleFixtures.fixInstitution(readonly, institution);
                try (ResultSet rows = readonly.createStatement().executeQuery("select count(*) from " + table)) {
                    rows.next();
                    assertThat(rows.getLong(1)).isEqualTo(1);
                }
                SQLException update = rejected(readonly,
                        () -> readonly.createStatement().execute("update " + table + " set is_active = false"));
                assertThat(update.getSQLState()).as("UPDATE by the read-only role on %s", table).isEqualTo("42501");
                readonly.rollback();
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

    // --- Seeding and reading ---

    /** One row in each of the four tables, committed under the institution given. */
    private static void seedStructure(UUID institution) throws SQLException {
        UUID year = UUID.randomUUID();
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            year(owner, institution, year, "2026", "2026-02-01", "2026-11-30", "ACTIVE");
            modality(owner, institution, modality, "BACH");
            grade(owner, institution, grade, modality, "Primero", 1);
            section(owner, institution, UUID.randomUUID(), year, grade, "A", 30);
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

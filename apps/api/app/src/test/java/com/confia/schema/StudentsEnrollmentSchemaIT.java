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
 * The two tables of the enrollment (V18) against a real PostgreSQL (docs/cortes/enrollments.md): the
 * constraints that enforce the rules of the cut, INV-31 as a partial unique index, the row-level security
 * that keeps one institution from reading or writing the rows of another, and the privileges (nobody
 * deletes, nobody edits the history of transfers, the portal sees nothing). Constraint rejections run as
 * {@code confia_owner}, which holds every privilege, so what refuses the row is the constraint under test
 * and never a missing grant.
 */
class StudentsEnrollmentSchemaIT extends CommittingPostgresIntegrationTest {

    private static final List<String> TABLES = List.of("students_enrollment", "students_enrollment_transfer");

    private interface SqlAction {
        void run() throws SQLException;
    }

    /** The rows an enrollment hangs from, in one institution. */
    private record Base(UUID institution, UUID student, UUID year, UUID section, UUID otherSection, UUID otherYear) {
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

    /** A student, two years, one section in the first year and another in the second, in one institution. */
    private static Base base(Connection c, UUID institution) throws SQLException {
        UUID student = UUID.randomUUID();
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        UUID year = UUID.randomUUID();
        UUID otherYear = UUID.randomUUID();
        UUID section = UUID.randomUUID();
        UUID otherSection = UUID.randomUUID();
        exec(c, """
                insert into students_student (institution_id, id, student_code, first_name, last_name, birth_date, sex)
                values (?, ?, ?, 'Ana', 'Perez', date '2012-05-20', 'F')
                """, institution, student, "E-" + student.toString().substring(0, 8));
        exec(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'BAS', 'Basica')",
                institution, modality);
        exec(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) values (?, ?, ?, '7mo', 7)",
                institution, grade, modality);
        exec(c, """
                insert into organization_academic_year (institution_id, id, name, start_date, end_date, status)
                values (?, ?, '2026', date '2026-01-15', date '2026-11-30', 'ACTIVE')
                """, institution, year);
        exec(c, """
                insert into organization_academic_year (institution_id, id, name, start_date, end_date, status)
                values (?, ?, '2027', date '2027-01-15', date '2027-11-30', 'PLANNED')
                """, institution, otherYear);
        exec(c, "insert into organization_section (institution_id, id, academic_year_id, grade_id, name) values (?, ?, ?, ?, 'A')",
                institution, section, year, grade);
        exec(c, "insert into organization_section (institution_id, id, academic_year_id, grade_id, name) values (?, ?, ?, ?, 'B')",
                institution, otherSection, year, grade);
        return new Base(institution, student, year, section, otherSection, otherYear);
    }

    private static UUID enrollment(Connection c, Base base, UUID student, UUID year, String status, String enrolledOn,
            String withdrawnOn, String reason) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, """
                insert into students_enrollment (institution_id, id, student_id, section_id, academic_year_id,
                    enrolled_on, status, withdrawn_on, withdrawal_reason)
                values (?, ?, ?, ?, ?, cast(? as date), ?, cast(? as date), ?)
                """, base.institution(), id, student, base.section(), year, enrolledOn, status, withdrawnOn, reason);
        return id;
    }

    private static UUID active(Connection c, Base base) throws SQLException {
        return enrollment(c, base, base.student(), base.year(), "ACTIVE", "2026-02-01", null, null);
    }

    private static void transfer(Connection c, Base base, UUID enrollment, UUID from, UUID to, String reason)
            throws SQLException {
        exec(c, """
                insert into students_enrollment_transfer (institution_id, id, enrollment_id, from_section_id,
                    to_section_id, transferred_on, reason, actor_id)
                values (?, ?, ?, ?, ?, date '2026-03-01', ?, ?)
                """, base.institution(), UUID.randomUUID(), enrollment, from, to, reason, UUID.randomUUID());
    }

    // --- Constraints ---

    @Test
    void theStatusHasItsClosedSetAndTheExitDateIsPresentExactlyWhenTheEnrollmentIsNotActive() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException unknown = rejected(owner, () -> enrollment(owner, base, base.student(), base.year(),
                    "DELETED", "2026-02-01", "2026-03-01", "Motivo"));
            SQLException activeWithExit = rejected(owner, () -> enrollment(owner, base, base.student(), base.year(),
                    "ACTIVE", "2026-02-01", "2026-03-01", null));
            SQLException exitedWithoutDate = rejected(owner, () -> enrollment(owner, base, base.student(),
                    base.year(), "WITHDRAWN", "2026-02-01", null, "Motivo"));
            SQLException beforeEnrollment = rejected(owner, () -> enrollment(owner, base, base.student(), base.year(),
                    "WITHDRAWN", "2026-02-01", "2026-01-31", "Motivo"));

            // An unknown status also fails the reason check, which PostgreSQL evaluates first by name.
            assertThat(unknown.getMessage()).containsAnyOf("students_enrollment_status_chk",
                    "students_enrollment_reason_chk");
            assertThat(activeWithExit.getMessage()).contains("students_enrollment_exit_chk");
            assertThat(exitedWithoutDate.getMessage()).contains("students_enrollment_exit_chk");
            assertThat(beforeEnrollment.getMessage()).contains("students_enrollment_dates_chk");
            owner.rollback();
        }
    }

    @Test
    void theReasonIsRequiredToWithdrawOrLeaveForAnotherInstitutionOptionalToGraduateAndAbsentWhileActive()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            for (String status : List.of("WITHDRAWN", "TRANSFERRED")) {
                SQLException missing = rejected(owner, () -> enrollment(owner, base, base.student(), base.year(),
                        status, "2026-02-01", "2026-03-01", null));
                assertThat(missing.getMessage()).as(status).contains("students_enrollment_reason_chk");
            }
            SQLException activeWithReason = rejected(owner, () -> enrollment(owner, base, base.student(), base.year(),
                    "ACTIVE", "2026-02-01", null, "Motivo"));
            assertThat(activeWithReason.getMessage()).contains("students_enrollment_reason_chk");
            SQLException tooShort = rejected(owner, () -> enrollment(owner, base, base.student(), base.year(),
                    "WITHDRAWN", "2026-02-01", "2026-03-01", " ab "));
            SQLException tooLong = rejected(owner, () -> enrollment(owner, base, base.student(), base.year(),
                    "WITHDRAWN", "2026-02-01", "2026-03-01", "a".repeat(501)));
            assertThat(tooShort.getMessage()).contains("students_enrollment_reason_length_chk");
            assertThat(tooLong.getMessage()).containsAnyOf("students_enrollment_reason_length_chk", "value too long");

            enrollment(owner, base, base.student(), base.year(), "GRADUATED", "2026-02-01", "2026-03-01", null);
            enrollment(owner, base, base.student(), base.year(), "WITHDRAWN", "2026-02-01", "2026-03-01", "Motivo");
            owner.rollback();
        }
    }

    @Test
    void aStudentHasAtMostOneActiveEnrollmentPerYearButMayReturnAfterAWithdrawalAndInAnotherYear()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID first = active(owner, base);

            SQLException second = rejected(owner, () -> active(owner, base));
            assertThat(second.getSQLState()).isEqualTo("23505");
            assertThat(second.getMessage()).contains("students_enrollment_active_uq");

            exec(owner, "update students_enrollment set status = 'WITHDRAWN', withdrawn_on = date '2026-03-01', "
                    + "withdrawal_reason = 'Cambio de ciudad' where id = ?", first);
            UUID again = active(owner, base);
            assertThat(again).isNotEqualTo(first);

            enrollment(owner, base, base.student(), base.otherYear(), "ACTIVE", "2027-02-01", null, null);
            UUID otherStudent = UUID.randomUUID();
            exec(owner, """
                    insert into students_student (institution_id, id, student_code, first_name, last_name,
                        birth_date, sex)
                    values (?, ?, 'E-OTHER', 'Luis', 'Lopez', date '2012-05-20', 'M')
                    """, institution, otherStudent);
            enrollment(owner, base, otherStudent, base.year(), "ACTIVE", "2026-02-01", null, null);
            owner.rollback();
        }
    }

    @Test
    void anEnrollmentNeedsItsStudentItsSectionAndItsYearInTheSameInstitution() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        Base theirBase;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs)) {
            theirBase = base(owner, theirs);
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(mine)) {
            Base base = base(owner, mine);

            SQLException foreignStudent = rejected(owner, () -> enrollment(owner, base, theirBase.student(),
                    base.year(), "ACTIVE", "2026-02-01", null, null));
            assertThat(foreignStudent.getMessage()).contains("students_enrollment_student_fk");
            SQLException foreignSection = rejected(owner, () -> exec(owner, """
                    insert into students_enrollment (institution_id, id, student_id, section_id, academic_year_id,
                        enrolled_on) values (?, ?, ?, ?, ?, date '2026-02-01')
                    """, mine, UUID.randomUUID(), base.student(), theirBase.section(), base.year()));
            assertThat(foreignSection.getMessage()).contains("students_enrollment_section_fk");
            SQLException foreignYear = rejected(owner, () -> exec(owner, """
                    insert into students_enrollment (institution_id, id, student_id, section_id, academic_year_id,
                        enrolled_on) values (?, ?, ?, ?, ?, date '2026-02-01')
                    """, mine, UUID.randomUUID(), base.student(), base.section(), theirBase.year()));
            assertThat(foreignYear.getMessage()).contains("students_enrollment_year_fk");
            owner.rollback();
        }
    }

    @Test
    void aTransferNeedsAnEnrollmentTwoDifferentSectionsAndAReasonOfThreeToFiveHundredCharacters() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID enrollment = active(owner, base);

            transfer(owner, base, enrollment, base.section(), base.otherSection(), null);
            transfer(owner, base, enrollment, base.otherSection(), base.section(), "Cambio de turno");
            SQLException same = rejected(owner, () -> transfer(owner, base, enrollment, base.section(),
                    base.section(), null));
            SQLException orphan = rejected(owner, () -> transfer(owner, base, UUID.randomUUID(), base.section(),
                    base.otherSection(), null));
            SQLException short_ = rejected(owner, () -> transfer(owner, base, enrollment, base.section(),
                    base.otherSection(), "ab"));

            assertThat(same.getMessage()).contains("students_enrollment_transfer_sections_chk");
            assertThat(orphan.getMessage()).contains("students_enrollment_transfer_enrollment_fk");
            assertThat(short_.getMessage()).contains("students_enrollment_transfer_reason_chk");
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
        Base theirBase = seed(theirs);
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);

            SQLException foreignInsert = rejected(admin, () -> active(admin, theirBase));
            SQLException foreignTransfer = rejected(admin, () -> transfer(admin, theirBase, UUID.randomUUID(),
                    theirBase.section(), theirBase.otherSection(), null));
            assertThat(foreignInsert.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            assertThat(foreignTransfer.getSQLState()).isEqualTo("42501");
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update students_enrollment set updated_at = clock_timestamp()")).isZero();
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs);
                ResultSet rows = owner.createStatement()
                        .executeQuery("select count(*) from students_enrollment where status = 'ACTIVE'")) {
            rows.next();
            assertThat(rows.getLong(1)).as("the row of the other institution stayed active").isEqualTo(1);
            owner.rollback();
        }
    }

    // --- Privileges ---

    @Test
    void nobodyDeletesTheHistoryIsInsertOnlyTheApplicationReadsAndThePortalSeesNothing() throws SQLException {
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
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);
            SQLException update = rejected(admin, () -> admin.createStatement()
                    .execute("update students_enrollment_transfer set reason = 'edited'"));
            assertThat(update.getSQLState()).as("the history is never edited").isEqualTo("42501");
            admin.createStatement().execute("update students_enrollment set updated_at = clock_timestamp()");
            admin.rollback();
        }
    }

    @Test
    void theReadOnlyRoleReadsBothTablesAndWritesNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            for (String table : TABLES) {
                try (ResultSet rows = readonly.createStatement().executeQuery("select * from " + table)) {
                    assertThat(rows.next()).as(table).isTrue();
                }
            }
            SQLException update = rejected(readonly, () -> readonly.createStatement()
                    .execute("update students_enrollment set status = 'ACTIVE'"));
            SQLException insert = rejected(readonly, () -> readonly.createStatement().execute(
                    "delete from students_enrollment_transfer"));
            assertThat(update.getSQLState()).isEqualTo("42501");
            assertThat(insert.getSQLState()).isEqualTo("42501");
            readonly.rollback();
        }
    }

    // --- Seeding and reading ---

    /** One active enrollment with one transfer in its history, committed under the institution given. */
    private static Base seed(UUID institution) throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID enrollment = active(owner, base);
            transfer(owner, base, enrollment, base.section(), base.otherSection(), null);
            owner.commit();
            return base;
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

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
 * The tables of the charge plans (V31) against a real PostgreSQL (docs/cortes/charges-plans.md): the checks, the
 * references to the same institution, the life of a plan (draft to active to archived, never back, never deleted), the
 * items that change only while the plan is a draft, the row-level security and the privileges. Constraint rejections run
 * as {@code confia_owner}, which holds every privilege, so what refuses the row is the constraint or the trigger under test
 * and never a missing grant; the grants have their own tests as {@code confia_admin_app}.
 */
class ChargesPlanSchemaIT extends CommittingPostgresIntegrationTest {

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

    /** The parents of a plan of one institution: a year, a modality, a grade, a section, two concepts and a draft plan. */
    private record Base(UUID institution, UUID year, UUID modality, UUID grade, UUID section, UUID concept,
            UUID otherConcept, UUID plan) {
    }

    private static Base base(Connection c, UUID institution) throws SQLException {
        Base base = new Base(institution, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        update(c, "insert into organization_academic_year (institution_id, id, name, start_date, end_date) "
                + "values (?, ?, '2027', date '2027-01-01', date '2027-12-31')", institution, base.year());
        update(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'BACH', 'Bach')",
                institution, base.modality());
        update(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                + "values (?, ?, ?, 'Decimo', 10)", institution, base.grade(), base.modality());
        update(c, "insert into organization_section (institution_id, id, academic_year_id, grade_id, name) "
                + "values (?, ?, ?, ?, 'A')", institution, base.section(), base.year(), base.grade());
        concept(c, institution, base.concept(), "COLEG");
        concept(c, institution, base.otherConcept(), "MATRI");
        plan(c, base, base.plan(), "GRADE", base.grade(), "MONTHLY", 5, "DRAFT");
        return base;
    }

    private static void update(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private static void concept(Connection c, UUID institution, UUID id, String code) throws SQLException {
        update(c, "insert into catalog_payment_concept (institution_id, id, code, name, category, is_taxable, "
                + "is_recurring) values (?, ?, ?, 'Nombre', 'TUITION', false, true)", institution, id, code);
    }

    private static void plan(Connection c, Base base, UUID id, String scope, UUID ref, String frequency, int dueDay,
            String status) throws SQLException {
        update(c, "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, target_ref_id, "
                + "frequency, due_day_of_month, status) values (?, ?, ?, 'Plan', ?, ?, ?, ?, ?)", base.institution(),
                id, base.year(), scope, ref, frequency, dueDay, status);
    }

    private static UUID item(Connection c, Base base, UUID plan, UUID concept, int periods, String firstPeriod)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement s = c.prepareStatement("""
                insert into charges_plan_item (institution_id, id, plan_id, payment_concept_id, periods_count,
                    first_period_on)
                values (?, ?, ?, ?, ?, cast(? as date))
                """)) {
            s.setObject(1, base.institution());
            s.setObject(2, id);
            s.setObject(3, plan);
            s.setObject(4, concept);
            s.setInt(5, periods);
            s.setString(6, firstPeriod);
            s.executeUpdate();
        }
        return id;
    }

    private static void setStatus(Connection c, UUID plan, String status) throws SQLException {
        update(c, "update charges_plan set status = ? where id = ?", status, plan);
    }

    // --- Checks ---

    @Test
    void theScopeTheFrequencyAndTheDueDayAreChecked() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException scope = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "INSTITUTION", base.grade(), "MONTHLY", 5, "DRAFT"));
            SQLException lowerScope = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "grade", base.grade(), "MONTHLY", 5, "DRAFT"));
            SQLException frequency = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "GRADE", base.grade(), "WEEKLY", 5, "DRAFT"));
            SQLException zero = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "GRADE", base.grade(), "MONTHLY", 0, "DRAFT"));
            SQLException twentyNine = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "GRADE", base.grade(), "MONTHLY", 29, "DRAFT"));
            SQLException thirtyOne = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "GRADE", base.grade(), "MONTHLY", 31, "DRAFT"));

            assertThat(scope.getSQLState()).isEqualTo("23514");
            assertThat(scope.getMessage()).contains("charges_plan_scope_chk");
            assertThat(lowerScope.getMessage()).contains("charges_plan_scope_chk");
            assertThat(frequency.getSQLState()).isEqualTo("23514");
            assertThat(frequency.getMessage()).contains("charges_plan_frequency_chk");
            for (SQLException dueDay : List.of(zero, twentyNine, thirtyOne)) {
                assertThat(dueDay.getSQLState()).isEqualTo("23514");
                assertThat(dueDay.getMessage()).contains("charges_plan_due_day_chk");
            }
            plan(owner, base, UUID.randomUUID(), "MODALITY", base.modality(), "ANNUAL", 1, "DRAFT");
            plan(owner, base, UUID.randomUUID(), "SECTION", base.section(), "ONE_TIME", 28, "DRAFT");
            owner.rollback();
        }
    }

    @Test
    void theTargetMustExistInTheSameInstitutionAndBeOfTheKindTheScopeSaysAndTheYearToo() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base mine = base(owner, institution);
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            StaffRoleFixtures.fixInstitution(owner, institution);

            SQLException foreignModality = rejected(owner,
                    () -> plan(owner, mine, UUID.randomUUID(), "MODALITY", theirs.modality(), "MONTHLY", 5, "DRAFT"));
            SQLException foreignGrade = rejected(owner,
                    () -> plan(owner, mine, UUID.randomUUID(), "GRADE", theirs.grade(), "MONTHLY", 5, "DRAFT"));
            SQLException foreignSection = rejected(owner,
                    () -> plan(owner, mine, UUID.randomUUID(), "SECTION", theirs.section(), "MONTHLY", 5, "DRAFT"));
            SQLException missingSection = rejected(owner,
                    () -> plan(owner, mine, UUID.randomUUID(), "SECTION", UUID.randomUUID(), "MONTHLY", 5, "DRAFT"));
            SQLException gradeAsSection = rejected(owner,
                    () -> plan(owner, mine, UUID.randomUUID(), "SECTION", mine.grade(), "MONTHLY", 5, "DRAFT"));
            SQLException foreignYear = rejected(owner, () -> update(owner,
                    "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, "
                            + "target_ref_id, frequency, due_day_of_month) values (?, ?, ?, 'X', 'GRADE', ?, "
                            + "'MONTHLY', 5)", institution, UUID.randomUUID(), theirs.year(), mine.grade()));

            assertThat(foreignModality.getSQLState()).isEqualTo("23503");
            assertThat(foreignModality.getMessage()).contains("charges_plan_modality_fk");
            assertThat(foreignGrade.getSQLState()).isEqualTo("23503");
            assertThat(foreignGrade.getMessage()).contains("charges_plan_grade_fk");
            assertThat(foreignSection.getSQLState()).isEqualTo("23503");
            assertThat(foreignSection.getMessage()).contains("charges_plan_section_fk");
            assertThat(missingSection.getSQLState()).isEqualTo("23503");
            assertThat(gradeAsSection.getSQLState()).isEqualTo("23503");
            assertThat(foreignYear.getSQLState()).isEqualTo("23503");
            assertThat(foreignYear.getMessage()).contains("charges_plan_year_fk");
            owner.rollback();
        }
    }

    @Test
    void anItemChecksItsPeriodsItsFirstDayItsConceptAndItsPlanInTheSameInstitution() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base mine = base(owner, institution);
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            StaffRoleFixtures.fixInstitution(owner, institution);

            SQLException zero = rejected(owner, () -> item(owner, mine, mine.plan(), mine.concept(), 0, "2027-02-01"));
            SQLException thirtySeven = rejected(owner,
                    () -> item(owner, mine, mine.plan(), mine.concept(), 37, "2027-02-01"));
            SQLException midMonth = rejected(owner,
                    () -> item(owner, mine, mine.plan(), mine.concept(), 10, "2027-02-15"));
            SQLException tooEarly = rejected(owner,
                    () -> item(owner, mine, mine.plan(), mine.concept(), 10, "1999-12-01"));
            SQLException tooLate = rejected(owner,
                    () -> item(owner, mine, mine.plan(), mine.concept(), 10, "2101-01-01"));
            SQLException foreignConcept = rejected(owner,
                    () -> item(owner, mine, mine.plan(), theirs.concept(), 10, "2027-02-01"));
            SQLException foreignPlan = rejected(owner,
                    () -> item(owner, mine, theirs.plan(), mine.concept(), 10, "2027-02-01"));

            assertThat(zero.getSQLState()).isEqualTo("23514");
            assertThat(zero.getMessage()).contains("charges_plan_item_periods_chk");
            assertThat(thirtySeven.getMessage()).contains("charges_plan_item_periods_chk");
            assertThat(midMonth.getSQLState()).isEqualTo("23514");
            assertThat(midMonth.getMessage()).contains("charges_plan_item_first_period_chk");
            assertThat(tooEarly.getMessage()).contains("charges_plan_item_first_period_chk");
            assertThat(tooLate.getMessage()).contains("charges_plan_item_first_period_chk");
            assertThat(foreignConcept.getSQLState()).isEqualTo("23503");
            assertThat(foreignConcept.getMessage()).contains("charges_plan_item_concept_fk");
            assertThat(foreignPlan.getSQLState()).isEqualTo("23503");
            assertThat(foreignPlan.getMessage()).contains("charges_plan_item_plan_fk");
            item(owner, mine, mine.plan(), mine.concept(), 1, "2027-02-01");
            item(owner, mine, mine.plan(), mine.otherConcept(), 36, "2100-12-01");
            owner.rollback();
        }
    }

    @Test
    void aConceptAppearsOnceInAPlanButCanAppearInAnother() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID second = UUID.randomUUID();
            plan(owner, base, second, "GRADE", base.grade(), "MONTHLY", 5, "DRAFT");
            item(owner, base, base.plan(), base.concept(), 10, "2027-02-01");

            SQLException repeated = rejected(owner,
                    () -> item(owner, base, base.plan(), base.concept(), 1, "2027-03-01"));

            assertThat(repeated.getSQLState()).isEqualTo("23505");
            assertThat(repeated.getMessage()).contains("charges_plan_item_concept_uq");
            item(owner, base, second, base.concept(), 10, "2027-02-01");
            owner.rollback();
        }
    }

    // --- The life of a plan ---

    @Test
    void aPlanIsBornAsADraftNotEvenTheOwnerInsertsAnActiveOrArchivedOne() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException active = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "GRADE", base.grade(), "MONTHLY", 5, "ACTIVE"));
            SQLException archived = rejected(owner,
                    () -> plan(owner, base, UUID.randomUUID(), "GRADE", base.grade(), "MONTHLY", 5, "ARCHIVED"));

            assertThat(active.getSQLState()).isEqualTo("23001");
            assertThat(archived.getSQLState()).isEqualTo("23001");
            owner.rollback();
        }
    }

    @Test
    void aDraftIsActivatedOnlyWithItemsAndThenArchivedAndNothingGoesBack() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException withoutItems = rejected(owner, () -> setStatus(owner, base.plan(), "ACTIVE"));
            SQLException draftToArchived = rejected(owner, () -> setStatus(owner, base.plan(), "ARCHIVED"));
            item(owner, base, base.plan(), base.concept(), 10, "2027-02-01");
            setStatus(owner, base.plan(), "ACTIVE");
            SQLException backToDraft = rejected(owner, () -> setStatus(owner, base.plan(), "DRAFT"));
            setStatus(owner, base.plan(), "ARCHIVED");
            SQLException reactivated = rejected(owner, () -> setStatus(owner, base.plan(), "ACTIVE"));
            SQLException archivedToDraft = rejected(owner, () -> setStatus(owner, base.plan(), "DRAFT"));

            assertThat(withoutItems.getSQLState()).isEqualTo("23001");
            assertThat(withoutItems.getMessage()).contains("without items");
            assertThat(draftToArchived.getSQLState()).isEqualTo("23001");
            assertThat(backToDraft.getSQLState()).isEqualTo("23001");
            assertThat(reactivated.getSQLState()).isEqualTo("23001");
            assertThat(archivedToDraft.getSQLState()).isEqualTo("23001");
            owner.rollback();
        }
    }

    @Test
    void anActivePlanKeepsItsFrequencyAndDueDayAndAnArchivedPlanChangesNothingNotEvenForTheOwner()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            item(owner, base, base.plan(), base.concept(), 10, "2027-02-01");
            update(owner, "update charges_plan set frequency = 'BIMONTHLY', due_day_of_month = 10, name = 'Otro' "
                    + "where id = ?", base.plan());
            setStatus(owner, base.plan(), "ACTIVE");

            SQLException frequency = rejected(owner, () -> update(owner,
                    "update charges_plan set frequency = 'ANNUAL' where id = ?", base.plan()));
            SQLException dueDay = rejected(owner, () -> update(owner,
                    "update charges_plan set due_day_of_month = 6 where id = ?", base.plan()));
            SQLException archivedWithOtherDay = rejected(owner, () -> update(owner,
                    "update charges_plan set status = 'ARCHIVED', due_day_of_month = 6 where id = ?", base.plan()));
            update(owner, "update charges_plan set name = 'Renombrado', version = version + 1 where id = ?",
                    base.plan());
            setStatus(owner, base.plan(), "ARCHIVED");
            SQLException renamed = rejected(owner,
                    () -> update(owner, "update charges_plan set name = 'Tarde' where id = ?", base.plan()));
            SQLException touched = rejected(owner, () -> update(owner,
                    "update charges_plan set version = version + 1 where id = ?", base.plan()));

            assertThat(frequency.getSQLState()).isEqualTo("23001");
            assertThat(dueDay.getSQLState()).isEqualTo("23001");
            assertThat(archivedWithOtherDay.getSQLState()).isEqualTo("23001");
            assertThat(renamed.getSQLState()).isEqualTo("23001");
            assertThat(touched.getSQLState()).isEqualTo("23001");
            owner.rollback();
        }
    }

    @Test
    void theYearTheTargetAndTheCreationOfAPlanNeverChangeAndAPlanIsNeverDeletedNotEvenByTheOwner()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException scope = rejected(owner, () -> update(owner,
                    "update charges_plan set target_scope = 'SECTION', target_ref_id = ? where id = ?", base.section(),
                    base.plan()));
            SQLException reference = rejected(owner, () -> update(owner,
                    "update charges_plan set target_ref_id = ? where id = ?", UUID.randomUUID(), base.plan()));
            SQLException year = rejected(owner, () -> update(owner,
                    "update charges_plan set academic_year_id = ? where id = ?", UUID.randomUUID(), base.plan()));
            SQLException created = rejected(owner, () -> update(owner,
                    "update charges_plan set created_at = clock_timestamp() where id = ?", base.plan()));
            SQLException delete = rejected(owner,
                    () -> update(owner, "delete from charges_plan where id = ?", base.plan()));

            assertThat(scope.getSQLState()).isEqualTo("23001");
            assertThat(reference.getSQLState()).isEqualTo("23001");
            assertThat(year.getSQLState()).isIn("23001", "23503");
            assertThat(created.getSQLState()).isEqualTo("23001");
            assertThat(delete.getSQLState()).isEqualTo("23001");
            update(owner, "update charges_plan set name = 'Nuevo', version = version + 1 where id = ?", base.plan());
            owner.rollback();
        }
    }

    // --- The items of a plan that is not a draft ---

    @Test
    void theDatabaseRejectsInsertsUpdatesAndDeletesOfItemsOfAPlanThatIsNotADraftNotEvenForTheOwner()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID tuition = item(owner, base, base.plan(), base.concept(), 10, "2027-02-01");
            // Changing and deleting an item of a draft works.
            update(owner, "update charges_plan_item set periods_count = 9 where id = ?", tuition);
            UUID enrollment = item(owner, base, base.plan(), base.otherConcept(), 1, "2027-02-01");
            update(owner, "delete from charges_plan_item where id = ?", enrollment);
            setStatus(owner, base.plan(), "ACTIVE");

            SQLException insertActive = rejected(owner,
                    () -> item(owner, base, base.plan(), base.otherConcept(), 1, "2027-02-01"));
            SQLException updateActive = rejected(owner, () -> update(owner,
                    "update charges_plan_item set periods_count = 8 where id = ?", tuition));
            SQLException deleteActive = rejected(owner,
                    () -> update(owner, "delete from charges_plan_item where id = ?", tuition));
            setStatus(owner, base.plan(), "ARCHIVED");
            SQLException insertArchived = rejected(owner,
                    () -> item(owner, base, base.plan(), base.otherConcept(), 1, "2027-02-01"));
            SQLException deleteArchived = rejected(owner,
                    () -> update(owner, "delete from charges_plan_item where id = ?", tuition));

            for (SQLException refusal : List.of(insertActive, updateActive, deleteActive, insertArchived,
                    deleteArchived)) {
                assertThat(refusal.getSQLState()).isEqualTo("23001");
                assertThat(refusal.getMessage()).contains("only while it is a draft");
            }
            assertThat(count(owner, "select count(*) from charges_plan_item")).isEqualTo(1);
            owner.rollback();
        }
    }

    @Test
    void anItemNeverMovesToAnotherPlan() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID second = UUID.randomUUID();
            plan(owner, base, second, "GRADE", base.grade(), "MONTHLY", 5, "DRAFT");
            UUID tuition = item(owner, base, base.plan(), base.concept(), 10, "2027-02-01");

            SQLException move = rejected(owner,
                    () -> update(owner, "update charges_plan_item set plan_id = ? where id = ?", second, tuition));

            assertThat(move.getSQLState()).isEqualTo("23001");
            owner.rollback();
        }
    }

    // --- Privileges: what the application role may do ---

    @Test
    void theApplicationRoleMayChangeTheNameFrequencyDueDayStatusAndVersionOfAPlanAndNothingElseAndNeverDeletesOne()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        Base base;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            base = base(owner, institution);
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException scope = rejected(admin,
                    () -> update(admin, "update charges_plan set target_scope = 'SECTION' where id = ?", base.plan()));
            SQLException year = rejected(admin, () -> update(admin,
                    "update charges_plan set academic_year_id = ? where id = ?", UUID.randomUUID(), base.plan()));
            SQLException created = rejected(admin, () -> update(admin,
                    "update charges_plan set created_at = clock_timestamp() where id = ?", base.plan()));
            SQLException owner = rejected(admin, () -> update(admin,
                    "update charges_plan set institution_id = ? where id = ?", UUID.randomUUID(), base.plan()));
            SQLException delete = rejected(admin,
                    () -> admin.createStatement().execute("delete from charges_plan"));
            SQLException truncate = rejected(admin,
                    () -> admin.createStatement().execute("truncate charges_plan"));

            assertThat(scope.getSQLState()).isEqualTo("42501");
            assertThat(year.getSQLState()).isEqualTo("42501");
            assertThat(created.getSQLState()).isEqualTo("42501");
            assertThat(owner.getSQLState()).isEqualTo("42501");
            assertThat(delete.getSQLState()).as("DELETE").isEqualTo("42501");
            assertThat(truncate.getSQLState()).as("TRUNCATE").isEqualTo("42501");
            update(admin, "update charges_plan set name = 'Nuevo', frequency = 'ANNUAL', due_day_of_month = 7, "
                    + "version = 1, updated_at = clock_timestamp() where id = ?", base.plan());
            try (ResultSet rows = admin.createStatement().executeQuery(
                    "select academic_year_id, frequency from charges_plan")) {
                rows.next();
                assertThat(rows.getObject(1, UUID.class)).isEqualTo(base.year());
                assertThat(rows.getString(2)).isEqualTo("ANNUAL");
            }
            admin.rollback();
        }
    }

    @Test
    void theApplicationRoleInsertsAndDeletesItemsOfADraftAndNeverUpdatesOneNorDeletesOneOfAnActivePlan()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        Base base;
        UUID kept;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            base = base(owner, institution);
            kept = item(owner, base, base.plan(), base.concept(), 10, "2027-02-01");
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException updateItem = rejected(admin, () -> update(admin,
                    "update charges_plan_item set periods_count = 1 where id = ?", kept));
            SQLException truncate = rejected(admin,
                    () -> admin.createStatement().execute("truncate charges_plan_item"));
            UUID added = item(admin, base, base.plan(), base.otherConcept(), 1, "2027-02-01");
            update(admin, "delete from charges_plan_item where id = ?", added);
            update(admin, "update charges_plan set status = 'ACTIVE', version = 1 where id = ?", base.plan());
            SQLException deleteActive = rejected(admin,
                    () -> update(admin, "delete from charges_plan_item where id = ?", kept));
            SQLException insertActive = rejected(admin,
                    () -> item(admin, base, base.plan(), base.otherConcept(), 1, "2027-02-01"));

            assertThat(updateItem.getSQLState()).as("UPDATE of an item").isEqualTo("42501");
            assertThat(truncate.getSQLState()).isEqualTo("42501");
            assertThat(deleteActive.getSQLState()).as("the trigger refuses the delete").isEqualTo("23001");
            assertThat(insertActive.getSQLState()).isEqualTo("23001");
            assertThat(count(admin, "select count(*) from charges_plan_item")).isEqualTo(1);
            admin.rollback();
        }
    }

    // --- Row-level security ---

    @Test
    void aUserOfAnotherInstitutionReadsNoRowOfEitherTableAndWithoutContextReadsNone() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seed(mine);
        seed(theirs);

        for (String table : List.of("charges_plan", "charges_plan_item")) {
            assertThat(visibleInstitutions(table, mine)).as(table).containsOnly(mine).isNotEmpty();
            assertThat(visibleInstitutions(table, theirs)).as(table).containsOnly(theirs).isNotEmpty();
            assertThat(visibleInstitutions(table, null)).as("%s with no context", table).isEmpty();
            assertThat(visibleInstitutions(table, UUID.randomUUID())).as("%s as a stranger", table).isEmpty();
        }
    }

    @Test
    void theApplicationRoleCannotWriteARowOfAnotherInstitutionNorTouchOneThroughAnUpdateOrADelete()
            throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        Base theirBase = seed(theirs);
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);

            SQLException foreignPlan = rejected(admin, () -> update(admin,
                    "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, "
                            + "target_ref_id, frequency, due_day_of_month) values (?, ?, ?, 'X', 'GRADE', ?, "
                            + "'MONTHLY', 5)", theirs, UUID.randomUUID(), theirBase.year(), theirBase.grade()));
            SQLException foreignItem = rejected(admin,
                    () -> item(admin, theirBase, theirBase.plan(), theirBase.otherConcept(), 1, "2027-02-01"));
            assertThat(foreignPlan.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            assertThat(foreignItem.getSQLState()).isEqualTo("42501");
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update charges_plan set name = 'Robada'")).isZero();
                assertThat(s.executeUpdate("delete from charges_plan_item")).isZero();
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs)) {
            assertThat(count(owner, "select count(*) from charges_plan where name = 'Plan'")).isEqualTo(1);
            assertThat(count(owner, "select count(*) from charges_plan_item")).isEqualTo(1);
            owner.rollback();
        }
    }

    // --- Privileges: the other roles ---

    @Test
    void theReadOnlyRoleOnlyReadsAndThePortalSeesNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            for (String table : List.of("charges_plan", "charges_plan_item")) {
                assertThat(count(readonly, "select count(*) from " + table)).as(table).isEqualTo(1);
                SQLException update = rejected(readonly, () -> readonly.createStatement()
                        .execute("update " + table + " set " + (table.endsWith("item") ? "periods_count = 1"
                                : "name = 'x'")));
                SQLException delete = rejected(readonly,
                        () -> readonly.createStatement().execute("delete from " + table));
                assertThat(update.getSQLState()).as("UPDATE of %s by the read-only role", table).isEqualTo("42501");
                assertThat(delete.getSQLState()).as("DELETE of %s by the read-only role", table).isEqualTo("42501");
            }
            readonly.rollback();
        }
        try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            portal.setAutoCommit(false);
            for (String table : List.of("charges_plan", "charges_plan_item")) {
                SQLException select = rejected(portal,
                        () -> portal.createStatement().executeQuery("select 1 from " + table).close());
                assertThat(select.getSQLState()).as("SELECT of %s by the portal role", table).isEqualTo("42501");
            }
            portal.rollback();
        }
    }

    // --- Seeding and reading ---

    private static Base seed(UUID institution) throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            item(owner, base, base.plan(), base.concept(), 10, "2027-02-01");
            owner.commit();
            return base;
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
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

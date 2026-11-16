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
 * The tables of the price lists (V28) against a real PostgreSQL (docs/cortes/catalog-price-lists.md): INV-11 (the
 * exclusion constraint that no two validities of the same concept in the same list overlap), the immutability of a price
 * (INV-12, anticipated: only an open validity is closed, once; no delete), the checks, the row-level security and the
 * privileges. Constraint rejections run as {@code confia_owner}, which holds every privilege, so what refuses the row is
 * the constraint or the trigger under test and never a missing grant; the grants have their own tests as
 * {@code confia_admin_app}.
 */
class CatalogPriceListSchemaIT extends CommittingPostgresIntegrationTest {

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

    /** The parents of a list of one institution: a year, a modality, a grade and two concepts. */
    private record Base(UUID institution, UUID year, UUID modality, UUID grade, UUID concept, UUID otherConcept,
            UUID list) {
    }

    private static Base base(Connection c, UUID institution) throws SQLException {
        Base base = new Base(institution, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        update(c, "insert into organization_academic_year (institution_id, id, name, start_date, end_date) "
                + "values (?, ?, '2027', date '2027-01-01', date '2027-12-31')", institution, base.year());
        update(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'BACH', 'Bach')",
                institution, base.modality());
        update(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                + "values (?, ?, ?, 'Decimo', 10)", institution, base.grade(), base.modality());
        concept(c, institution, base.concept(), "COLEG");
        concept(c, institution, base.otherConcept(), "MATRI");
        update(c, "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope) "
                + "values (?, ?, ?, 'Lista 2027', 'INSTITUTION')", institution, base.list(), base.year());
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
                + "is_recurring) values (?, ?, ?, 'Nombre', 'TUITION', true, true)", institution, id, code);
    }

    private static void list(Connection c, Base base, UUID id, String scope, UUID ref, String status)
            throws SQLException {
        update(c, "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope, scope_ref_id, "
                + "status) values (?, ?, ?, 'Otra', ?, ?, ?)", base.institution(), id, base.year(), scope, ref,
                status);
    }

    private static UUID price(Connection c, Base base, UUID concept, String amount, String currency, String from,
            String to) throws SQLException {
        return priceIn(c, base, base.list(), concept, amount, currency, from, to);
    }

    private static UUID priceIn(Connection c, Base base, UUID list, UUID concept, String amount, String currency,
            String from, String to) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement s = c.prepareStatement("""
                insert into catalog_price_list_item (institution_id, id, price_list_id, payment_concept_id, amount,
                    currency, valid_from, valid_to, created_by)
                values (?, ?, ?, ?, cast(? as numeric), ?, cast(? as date), cast(? as date), ?)
                """)) {
            s.setObject(1, base.institution());
            s.setObject(2, id);
            s.setObject(3, list);
            s.setObject(4, concept);
            s.setString(5, amount);
            s.setString(6, currency);
            s.setString(7, from);
            s.setString(8, to);
            s.setObject(9, UUID.randomUUID());
            s.executeUpdate();
        }
        return id;
    }

    // --- Checks ---

    @Test
    void theScopeTheStatusAndTheReferenceOfTheScopeAreChecked() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException unknownScope = rejected(owner,
                    () -> list(owner, base, UUID.randomUUID(), "SECTION", base.grade(), "ACTIVE"));
            SQLException lowerStatus = rejected(owner,
                    () -> list(owner, base, UUID.randomUUID(), "INSTITUTION", null, "active"));
            SQLException institutionWithReference = rejected(owner,
                    () -> list(owner, base, UUID.randomUUID(), "INSTITUTION", base.modality(), "ACTIVE"));
            SQLException modalityWithoutReference = rejected(owner,
                    () -> list(owner, base, UUID.randomUUID(), "MODALITY", null, "ACTIVE"));
            SQLException gradeWithoutReference = rejected(owner,
                    () -> list(owner, base, UUID.randomUUID(), "GRADE", null, "ARCHIVED"));

            assertThat(unknownScope.getSQLState()).isEqualTo("23514");
            assertThat(unknownScope.getMessage()).contains("catalog_price_list_scope_chk");
            assertThat(lowerStatus.getSQLState()).isEqualTo("23514");
            assertThat(lowerStatus.getMessage()).contains("catalog_price_list_status_chk");
            assertThat(institutionWithReference.getMessage()).contains("catalog_price_list_scope_ref_chk");
            assertThat(modalityWithoutReference.getMessage()).contains("catalog_price_list_scope_ref_chk");
            assertThat(gradeWithoutReference.getMessage()).contains("catalog_price_list_scope_ref_chk");
            list(owner, base, UUID.randomUUID(), "MODALITY", base.modality(), "ACTIVE");
            list(owner, base, UUID.randomUUID(), "GRADE", base.grade(), "ARCHIVED");
            owner.rollback();
        }
    }

    @Test
    void theModalityOrTheGradeOfAListMustExistInTheSameInstitutionAndTheYearToo() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base mine = base(owner, institution);
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            StaffRoleFixtures.fixInstitution(owner, institution);

            SQLException foreignModality = rejected(owner,
                    () -> list(owner, mine, UUID.randomUUID(), "MODALITY", theirs.modality(), "ACTIVE"));
            SQLException foreignGrade = rejected(owner,
                    () -> list(owner, mine, UUID.randomUUID(), "GRADE", theirs.grade(), "ACTIVE"));
            SQLException missingGrade = rejected(owner,
                    () -> list(owner, mine, UUID.randomUUID(), "GRADE", UUID.randomUUID(), "ACTIVE"));
            SQLException modalityAsGrade = rejected(owner,
                    () -> list(owner, mine, UUID.randomUUID(), "GRADE", mine.modality(), "ACTIVE"));
            SQLException foreignYear = rejected(owner, () -> update(owner,
                    "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope) "
                            + "values (?, ?, ?, 'X', 'INSTITUTION')", institution, UUID.randomUUID(), theirs.year()));

            assertThat(foreignModality.getSQLState()).isEqualTo("23503");
            assertThat(foreignModality.getMessage()).contains("catalog_price_list_modality_fk");
            assertThat(foreignGrade.getSQLState()).isEqualTo("23503");
            assertThat(foreignGrade.getMessage()).contains("catalog_price_list_grade_fk");
            assertThat(missingGrade.getSQLState()).isEqualTo("23503");
            assertThat(modalityAsGrade.getSQLState()).isEqualTo("23503");
            assertThat(foreignYear.getSQLState()).isEqualTo("23503");
            assertThat(foreignYear.getMessage()).contains("catalog_price_list_year_fk");
            list(owner, mine, UUID.randomUUID(), "MODALITY", mine.modality(), "ACTIVE");
            list(owner, mine, UUID.randomUUID(), "GRADE", mine.grade(), "ACTIVE");
            owner.rollback();
        }
    }

    @Test
    void theAmountIsGreaterThanZeroTheCurrencyIsOneOfTheTwoAndTheEndIsNotBeforeTheStart() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException zero = rejected(owner,
                    () -> price(owner, base, base.concept(), "0", "HNL", "2027-01-01", null));
            SQLException negative = rejected(owner,
                    () -> price(owner, base, base.concept(), "-1500", "HNL", "2027-01-01", null));
            SQLException tooBig = rejected(owner,
                    () -> price(owner, base, base.concept(), "10000000000", "HNL", "2027-01-01", null));
            SQLException euro = rejected(owner,
                    () -> price(owner, base, base.concept(), "10", "EUR", "2027-01-01", null));
            SQLException lower = rejected(owner,
                    () -> price(owner, base, base.concept(), "10", "hnl", "2027-01-01", null));
            SQLException reversed = rejected(owner,
                    () -> price(owner, base, base.concept(), "10", "HNL", "2027-02-01", "2027-01-31"));

            assertThat(zero.getSQLState()).isEqualTo("23514");
            assertThat(zero.getMessage()).contains("catalog_price_list_item_amount_chk");
            assertThat(negative.getMessage()).contains("catalog_price_list_item_amount_chk");
            assertThat(tooBig.getSQLState()).as("NUMERIC(14,4) range").isEqualTo("22003");
            assertThat(euro.getMessage()).contains("catalog_price_list_item_currency_chk");
            assertThat(lower.getMessage()).contains("catalog_price_list_item_currency_chk");
            SQLException fractionOfACent = rejected(owner,
                    () -> price(owner, base, base.concept(), "10.005", "HNL", "2027-03-01", null));
            SQLException earlyYear = rejected(owner,
                    () -> price(owner, base, base.concept(), "10", "HNL", "0202-03-01", null));
            SQLException lateEnd = rejected(owner,
                    () -> price(owner, base, base.concept(), "10", "HNL", "2027-03-01", "2101-01-01"));
            assertThat(fractionOfACent.getSQLState()).isEqualTo("23514");
            assertThat(fractionOfACent.getMessage()).contains("catalog_price_list_item_minor_unit_chk");
            assertThat(earlyYear.getMessage()).contains("catalog_price_list_item_window_chk");
            assertThat(lateEnd.getMessage()).contains("catalog_price_list_item_window_chk");
            assertThat(reversed.getSQLState()).isEqualTo("23514");
            assertThat(reversed.getMessage()).contains("catalog_price_list_item_dates_chk");
            price(owner, base, base.concept(), "0.01", "USD", "2027-02-01", "2027-02-01");
            price(owner, base, base.otherConcept(), "9999999999.99", "HNL", "2027-01-01", null);
            owner.rollback();
        }
    }

    @Test
    void thePriceMustReferenceAListAndAConceptOfTheSameInstitution() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base mine = base(owner, institution);
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            StaffRoleFixtures.fixInstitution(owner, institution);

            SQLException foreignConcept = rejected(owner,
                    () -> price(owner, mine, theirs.concept(), "10", "HNL", "2027-01-01", null));
            SQLException foreignList = rejected(owner,
                    () -> priceIn(owner, mine, theirs.list(), mine.concept(), "10", "HNL", "2027-01-01", null));

            assertThat(foreignConcept.getSQLState()).isEqualTo("23503");
            assertThat(foreignConcept.getMessage()).contains("catalog_price_list_item_concept_fk");
            assertThat(foreignList.getSQLState()).isEqualTo("23503");
            assertThat(foreignList.getMessage()).contains("catalog_price_list_item_list_fk");
            owner.rollback();
        }
    }

    // --- INV-11: the exclusion constraint ---

    @Test
    void anExactAPartialAndAContainedOverlapOfTheSameConceptInTheSameListAreRefusedByTheDatabase()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            price(owner, base, base.concept(), "100", "HNL", "2027-01-01", "2027-06-30");

            SQLException exact = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2027-01-01", "2027-06-30"));
            SQLException partialRight = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2027-06-30", "2027-12-31"));
            SQLException partialLeft = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2026-06-01", "2027-01-01"));
            SQLException contained = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2027-02-01", "2027-02-28"));
            SQLException containing = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2026-01-01", "2028-01-01"));
            SQLException open = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2027-03-01", null));

            for (SQLException overlap : List.of(exact, partialRight, partialLeft, contained, containing, open)) {
                assertThat(overlap.getSQLState()).as(overlap.getMessage()).isEqualTo("23P01");
                assertThat(overlap.getMessage()).contains("catalog_price_list_item_validity_excl");
            }
            owner.rollback();
        }
    }

    @Test
    void anOpenValidityOverlapsEveryLaterOneAndAnotherOpenOneAndOnlyAnEarlierClosedOneFitsBefore()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            price(owner, base, base.concept(), "100", "HNL", "2027-03-01", null);

            SQLException later = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2030-01-01", "2030-12-31"));
            SQLException laterOpen = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2031-01-01", null));
            SQLException sameDayBefore = rejected(owner,
                    () -> price(owner, base, base.concept(), "110", "HNL", "2027-01-01", "2027-03-01"));

            assertThat(later.getSQLState()).isEqualTo("23P01");
            assertThat(laterOpen.getSQLState()).isEqualTo("23P01");
            assertThat(sameDayBefore.getSQLState()).isEqualTo("23P01");
            price(owner, base, base.concept(), "90", "HNL", "2027-01-01", "2027-02-28");
            owner.rollback();
        }
    }

    @Test
    void aContiguousValidityIsAllowedAndSoIsTheSameDatesForAnotherConceptAnotherListOrAnotherInstitution()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            price(owner, base, base.concept(), "100", "HNL", "2027-01-01", "2027-06-30");

            price(owner, base, base.concept(), "110", "HNL", "2027-07-01", "2027-12-31");
            price(owner, base, base.concept(), "120", "HNL", "2028-01-01", null);
            price(owner, base, base.otherConcept(), "50", "HNL", "2027-01-01", "2027-06-30");
            UUID secondList = UUID.randomUUID();
            list(owner, base, secondList, "INSTITUTION", null, "ACTIVE");
            priceIn(owner, base, secondList, base.concept(), "100", "HNL", "2027-01-01", "2027-06-30");
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            price(owner, theirs, theirs.concept(), "100", "HNL", "2027-01-01", "2027-06-30");

            owner.rollback();
        }
    }

    @Test
    void closingAnOpenValidityAndInsertingTheReplacementInOneTransactionIsAcceptedWhateverTheOrder()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID first = price(owner, base, base.concept(), "1500", "HNL", "2027-01-01", null);

            update(owner, "update catalog_price_list_item set valid_to = date '2027-06-30' where id = ?", first);
            price(owner, base, base.concept(), "1650", "HNL", "2027-07-01", null);

            assertThat(count(owner, "select count(*) from catalog_price_list_item where valid_to is null")).isEqualTo(1);
            assertThat(count(owner, "select count(*) from catalog_price_list_item")).isEqualTo(2);
            owner.rollback();
        }
    }

    // --- INV-12, anticipated: a price is immutable ---

    @Test
    void theOwnerCannotEditTheAmountTheCurrencyTheConceptTheStartOrTheCreationOfAPrice() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID id = price(owner, base, base.concept(), "1500", "HNL", "2027-01-01", null);

            for (String change : List.of("amount = 1", "currency = 'USD'", "payment_concept_id = '" + base.otherConcept() + "'",
                    "valid_from = date '2027-02-01'", "created_by = gen_random_uuid()",
                    "created_at = clock_timestamp()", "amount = 1, valid_to = date '2027-06-30'")) {
                SQLException edit = rejected(owner,
                        () -> update(owner, "update catalog_price_list_item set " + change + " where id = ?", id));
                assertThat(edit.getSQLState()).as(change).isEqualTo("23001");
                assertThat(edit.getMessage()).contains("a price is never edited");
            }
            assertThat(count(owner, "select count(*) from catalog_price_list_item where amount = 1500")).isEqualTo(1);
            owner.rollback();
        }
    }

    @Test
    void aValidityIsClosedOnceFromOpenToADateAndNeverReopenedNorMovedNorClearedAndNothingDeletesAPrice()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID open = price(owner, base, base.concept(), "1500", "HNL", "2027-01-01", null);
            UUID closed = price(owner, base, base.otherConcept(), "800", "HNL", "2027-01-01", "2027-03-31");

            SQLException moved = rejected(owner,
                    () -> update(owner, "update catalog_price_list_item set valid_to = date '2027-02-28' where id = ?",
                            closed));
            SQLException reopened = rejected(owner,
                    () -> update(owner, "update catalog_price_list_item set valid_to = null where id = ?", closed));
            SQLException beforeStart = rejected(owner,
                    () -> update(owner, "update catalog_price_list_item set valid_to = date '2026-12-31' where id = ?",
                            open));
            SQLException delete = rejected(owner,
                    () -> update(owner, "delete from catalog_price_list_item where id = ?", closed));

            assertThat(moved.getSQLState()).isEqualTo("23001");
            assertThat(moved.getMessage()).contains("closed once");
            assertThat(reopened.getSQLState()).isEqualTo("23001");
            assertThat(beforeStart.getSQLState()).isEqualTo("23514");
            assertThat(beforeStart.getMessage()).contains("catalog_price_list_item_dates_chk");
            assertThat(delete.getSQLState()).isEqualTo("23001");
            assertThat(delete.getMessage()).contains("never deleted");
            update(owner, "update catalog_price_list_item set valid_to = date '2027-06-30' where id = ?", open);
            SQLException secondClosing = rejected(owner,
                    () -> update(owner, "update catalog_price_list_item set valid_to = date '2027-06-29' where id = ?",
                            open));
            assertThat(secondClosing.getSQLState()).isEqualTo("23001");
            owner.rollback();
        }
    }

    @Test
    void theApplicationRoleCannotUpdateTheAmountNorDeleteAPriceAndMayOnlyCloseAnOpenValidity() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID open;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            open = price(owner, base, base.concept(), "1500", "HNL", "2027-01-01", null);
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException amount = rejected(admin,
                    () -> update(admin, "update catalog_price_list_item set amount = 1 where id = ?", open));
            SQLException currency = rejected(admin,
                    () -> update(admin, "update catalog_price_list_item set currency = 'USD' where id = ?", open));
            SQLException from = rejected(admin, () -> update(admin,
                    "update catalog_price_list_item set valid_from = date '2027-02-01' where id = ?", open));
            SQLException delete = rejected(admin, () -> admin.createStatement()
                    .execute("delete from catalog_price_list_item"));
            SQLException truncate = rejected(admin, () -> admin.createStatement()
                    .execute("truncate catalog_price_list_item"));

            assertThat(amount.getSQLState()).as("UPDATE of the amount").isEqualTo("42501");
            assertThat(currency.getSQLState()).isEqualTo("42501");
            assertThat(from.getSQLState()).isEqualTo("42501");
            assertThat(delete.getSQLState()).as("DELETE").isEqualTo("42501");
            assertThat(truncate.getSQLState()).as("TRUNCATE").isEqualTo("42501");
            update(admin, "update catalog_price_list_item set valid_to = date '2027-06-30' where id = ?", open);
            assertThat(count(admin, "select count(*) from catalog_price_list_item where valid_to is not null"))
                    .isEqualTo(1);
            admin.rollback();
        }
    }

    // --- The list: immutable year, scope and reference; never deleted ---

    @Test
    void theYearTheScopeAndTheReferenceOfAListNeverChangeNotEvenForTheOwnerAndItIsNeverDeleted()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID modalityList = UUID.randomUUID();
            list(owner, base, modalityList, "MODALITY", base.modality(), "ACTIVE");

            SQLException scope = rejected(owner, () -> update(owner,
                    "update catalog_price_list set scope = 'INSTITUTION', scope_ref_id = null where id = ?",
                    modalityList));
            SQLException reference = rejected(owner, () -> update(owner,
                    "update catalog_price_list set scope = 'GRADE', scope_ref_id = ? where id = ?", base.grade(),
                    modalityList));
            SQLException delete = rejected(owner,
                    () -> update(owner, "delete from catalog_price_list where id = ?", base.list()));

            assertThat(scope.getSQLState()).isEqualTo("23001");
            assertThat(reference.getSQLState()).isEqualTo("23001");
            assertThat(delete.getSQLState()).isEqualTo("23001");
            update(owner, "update catalog_price_list set name = 'Nuevo', status = 'ARCHIVED', version = version + 1 "
                    + "where id = ?", modalityList);
            owner.rollback();
        }
    }

    @Test
    void theApplicationRoleMayChangeTheNameTheStatusAndTheVersionOfAListAndNothingElse() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID list;
        UUID year;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            list = base.list();
            year = base.year();
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException scope = rejected(admin,
                    () -> update(admin, "update catalog_price_list set scope = 'GRADE' where id = ?", list));
            SQLException yearChange = rejected(admin, () -> update(admin,
                    "update catalog_price_list set academic_year_id = ? where id = ?", UUID.randomUUID(), list));
            SQLException delete = rejected(admin,
                    () -> admin.createStatement().execute("delete from catalog_price_list"));

            assertThat(scope.getSQLState()).isEqualTo("42501");
            assertThat(yearChange.getSQLState()).isEqualTo("42501");
            assertThat(delete.getSQLState()).isEqualTo("42501");
            update(admin, "update catalog_price_list set name = 'Nuevo', status = 'ARCHIVED', version = 1, "
                    + "updated_at = clock_timestamp() where id = ?", list);
            try (ResultSet rows = admin.createStatement().executeQuery(
                    "select academic_year_id, status from catalog_price_list")) {
                rows.next();
                assertThat(rows.getObject(1, UUID.class)).isEqualTo(year);
                assertThat(rows.getString(2)).isEqualTo("ARCHIVED");
            }
            admin.rollback();
        }
    }

    @Test
    void theApplicationRoleCannotChangeTheCodeOrTheInstitutionOfAConceptButEditsTheRest() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            concept(owner, institution, id, "COLEG");
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException code = rejected(admin,
                    () -> update(admin, "update catalog_payment_concept set code = 'OTRO' where id = ?", id));
            SQLException owner = rejected(admin, () -> update(admin,
                    "update catalog_payment_concept set institution_id = ? where id = ?", UUID.randomUUID(), id));
            SQLException created = rejected(admin, () -> update(admin,
                    "update catalog_payment_concept set created_at = clock_timestamp() where id = ?", id));

            assertThat(code.getSQLState()).isEqualTo("42501");
            assertThat(owner.getSQLState()).isEqualTo("42501");
            assertThat(created.getSQLState()).isEqualTo("42501");
            update(admin, "update catalog_payment_concept set name = 'Nuevo', category = 'FINE', "
                    + "is_taxable = false, is_recurring = false, is_active = false, version = 1, "
                    + "updated_at = clock_timestamp() where id = ?", id);
            try (ResultSet rows = admin.createStatement().executeQuery(
                    "select code from catalog_payment_concept where is_active = false")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("COLEG");
            }
            admin.rollback();
        }
    }

    // --- The version of the concept (finding B-1) ---

    @Test
    void thePaymentConceptHasAVersionThatStartsAtZeroAndIsNeverNegative() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID id = UUID.randomUUID();
            concept(owner, institution, id, "COLEG");

            assertThat(count(owner, "select version from catalog_payment_concept")).isZero();
            SQLException negative = rejected(owner,
                    () -> update(owner, "update catalog_payment_concept set version = -1 where id = ?", id));
            assertThat(negative.getSQLState()).isEqualTo("23514");
            assertThat(negative.getMessage()).contains("catalog_payment_concept_version_chk");
            update(owner, "update catalog_payment_concept set version = version + 1 where id = ?", id);
            assertThat(count(owner, "select version from catalog_payment_concept")).isEqualTo(1);
            owner.rollback();
        }
    }

    // --- Row-level security ---

    @Test
    void aUserOfAnotherInstitutionReadsNoRowOfEitherTableAndWithoutContextReadsNone() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seed(mine);
        seed(theirs);

        for (String table : List.of("catalog_price_list", "catalog_price_list_item")) {
            assertThat(visibleInstitutions(table, mine)).as(table).containsOnly(mine).isNotEmpty();
            assertThat(visibleInstitutions(table, theirs)).as(table).containsOnly(theirs).isNotEmpty();
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

            SQLException foreignList = rejected(admin, () -> update(admin,
                    "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope) "
                            + "values (?, ?, ?, 'X', 'INSTITUTION')", theirs, UUID.randomUUID(), theirBase.year()));
            SQLException foreignPrice = rejected(admin, () -> priceIn(admin, theirBase, theirBase.list(),
                    theirBase.concept(), "10", "HNL", "2030-01-01", null));
            assertThat(foreignList.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            assertThat(foreignPrice.getSQLState()).isEqualTo("42501");
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update catalog_price_list set status = 'ARCHIVED'")).isZero();
                assertThat(s.executeUpdate("update catalog_price_list_item set valid_to = date '2027-06-30'")).isZero();
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs)) {
            assertThat(count(owner, "select count(*) from catalog_price_list where status = 'ACTIVE'")).isEqualTo(1);
            assertThat(count(owner, "select count(*) from catalog_price_list_item where valid_to is null"))
                    .isEqualTo(1);
            owner.rollback();
        }
    }

    // --- Privileges ---

    @Test
    void theReadOnlyRoleOnlyReadsAndThePortalSeesNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            for (String table : List.of("catalog_price_list", "catalog_price_list_item")) {
                assertThat(count(readonly, "select count(*) from " + table)).as(table).isEqualTo(1);
                SQLException update = rejected(readonly, () -> readonly.createStatement()
                        .execute("update " + table + " set " + (table.endsWith("item") ? "valid_to" : "name")
                                + " = " + (table.endsWith("item") ? "date '2027-06-30'" : "'x'")));
                assertThat(update.getSQLState()).as("UPDATE of %s by the read-only role", table).isEqualTo("42501");
            }
            readonly.rollback();
        }
        try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            portal.setAutoCommit(false);
            for (String table : List.of("catalog_price_list", "catalog_price_list_item")) {
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
            price(owner, base, base.concept(), "1500", "HNL", "2027-01-01", null);
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

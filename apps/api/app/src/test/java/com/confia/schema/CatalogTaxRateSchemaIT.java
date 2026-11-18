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
 * The tables of the tax rates (V29) against a real PostgreSQL (docs/cortes/catalog-tax-rates.md): INV-11 (the exclusion
 * constraint that no two validities of the same rate overlap), the immutability of a period (the percentage is never
 * edited: only an open validity is closed, once; no delete), the checks, the tax rate of a payment concept, the row-level
 * security and the privileges. Constraint rejections run as {@code confia_owner}, which holds every privilege, so what
 * refuses the row is the constraint or the trigger under test and never a missing grant; the grants have their own tests
 * as {@code confia_admin_app}. The percentages are inputs of the cases, never values the system knows.
 */
class CatalogTaxRateSchemaIT extends CommittingPostgresIntegrationTest {

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

    /** The parents of a period of one institution: two rates. */
    private record Base(UUID institution, UUID rate, UUID otherRate) {
    }

    private static Base base(Connection c, UUID institution) throws SQLException {
        Base base = new Base(institution, UUID.randomUUID(), UUID.randomUUID());
        rate(c, institution, base.rate(), "ISV");
        rate(c, institution, base.otherRate(), "TUR");
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

    private static void rate(Connection c, UUID institution, UUID id, String code) throws SQLException {
        update(c, "insert into catalog_tax_rate (institution_id, id, code, name) values (?, ?, ?, 'Nombre')",
                institution, id, code);
    }

    private static void concept(Connection c, UUID institution, UUID id, String code, boolean taxable, UUID rate)
            throws SQLException {
        update(c, "insert into catalog_payment_concept (institution_id, id, code, name, category, is_taxable, "
                + "is_recurring, tax_rate_id) values (?, ?, ?, 'Nombre', 'TUITION', ?, true, ?)", institution, id, code,
                taxable, rate);
    }

    private static UUID period(Connection c, Base base, UUID rate, String percent, String from, String to)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement s = c.prepareStatement("""
                insert into catalog_tax_rate_period (institution_id, id, tax_rate_id, rate_percent, valid_from,
                    valid_to, created_by)
                values (?, ?, ?, cast(? as numeric), cast(? as date), cast(? as date), ?)
                """)) {
            s.setObject(1, base.institution());
            s.setObject(2, id);
            s.setObject(3, rate);
            s.setString(4, percent);
            s.setString(5, from);
            s.setString(6, to);
            s.setObject(7, UUID.randomUUID());
            s.executeUpdate();
        }
        return id;
    }

    private static UUID period(Connection c, Base base, String percent, String from, String to) throws SQLException {
        return period(c, base, base.rate(), percent, from, to);
    }

    // --- Checks ---

    @Test
    void theCodeIsAWhitelistTheVersionIsNeverNegativeAndTheCodeIsUniqueWhateverItsCapitalsInTheInstitution()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            for (String code : List.of("", " ISV", "=ISV", "-ISV", "I SV", "ISV\n", "ÍSV")) {
                SQLException bad = rejected(owner, () -> rate(owner, institution, UUID.randomUUID(), code));
                assertThat(bad.getSQLState()).as(code).isEqualTo("23514");
                assertThat(bad.getMessage()).contains("catalog_tax_rate_code_chk");
            }
            SQLException negative = rejected(owner,
                    () -> update(owner, "update catalog_tax_rate set version = -1 where id = ?", base.rate()));
            SQLException sameCode = rejected(owner, () -> rate(owner, institution, UUID.randomUUID(), "ISV"));
            SQLException otherCapitals = rejected(owner, () -> rate(owner, institution, UUID.randomUUID(), "isv"));

            assertThat(negative.getMessage()).contains("catalog_tax_rate_version_chk");
            assertThat(sameCode.getSQLState()).isEqualTo("23505");
            assertThat(sameCode.getMessage()).contains("catalog_tax_rate_code_uq");
            assertThat(otherCapitals.getSQLState()).isEqualTo("23505");
            assertThat(otherCapitals.getMessage()).contains("catalog_tax_rate_code_uq");
            rate(owner, institution, UUID.randomUUID(), "C".repeat(30));
            StaffRoleFixtures.fixInstitution(owner, other);
            rate(owner, other, UUID.randomUUID(), "ISV");
            owner.rollback();
        }
    }

    @Test
    void thePercentageIsGreaterThanZeroAtMostOneHundredWithFourDecimalsAndTheEndIsNotBeforeTheStart()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            for (String percent : List.of("0", "0.0000", "-1", "-0.0001", "100.0001", "101", "999.9999")) {
                SQLException bad = rejected(owner, () -> period(owner, base, percent, "2027-01-01", null));
                assertThat(bad.getSQLState()).as(percent).isEqualTo("23514");
                assertThat(bad.getMessage()).contains("catalog_tax_rate_period_percent_chk");
            }
            SQLException tooBig = rejected(owner, () -> period(owner, base, "1000", "2027-01-01", null));
            SQLException reversed = rejected(owner, () -> period(owner, base, "5", "2027-02-01", "2027-01-31"));
            SQLException earlyYear = rejected(owner, () -> period(owner, base, "5", "0202-03-01", null));
            SQLException lateEnd = rejected(owner, () -> period(owner, base, "5", "2027-03-01", "2101-01-01"));

            assertThat(tooBig.getSQLState()).as("NUMERIC(7,4) range").isEqualTo("22003");
            assertThat(reversed.getSQLState()).isEqualTo("23514");
            assertThat(reversed.getMessage()).contains("catalog_tax_rate_period_dates_chk");
            assertThat(earlyYear.getMessage()).contains("catalog_tax_rate_period_window_chk");
            assertThat(lateEnd.getMessage()).contains("catalog_tax_rate_period_window_chk");
            period(owner, base, "0.0001", "2027-02-01", "2027-02-01");
            period(owner, base, "100", "2027-03-01", "2027-03-31");
            period(owner, base, base.otherRate(), "12.3456", "2000-01-01", "2100-12-31");
            owner.rollback();
        }
    }

    @Test
    void aPeriodMustReferenceARateOfTheSameInstitution() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base mine = base(owner, institution);
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            StaffRoleFixtures.fixInstitution(owner, institution);

            SQLException foreign = rejected(owner, () -> period(owner, mine, theirs.rate(), "5", "2027-01-01", null));
            SQLException missing = rejected(owner,
                    () -> period(owner, mine, UUID.randomUUID(), "5", "2027-01-01", null));

            assertThat(foreign.getSQLState()).isEqualTo("23503");
            assertThat(foreign.getMessage()).contains("catalog_tax_rate_period_rate_fk");
            assertThat(missing.getSQLState()).isEqualTo("23503");
            owner.rollback();
        }
    }

    // --- INV-11: the exclusion constraint ---

    @Test
    void anExactAPartialAndAContainedOverlapOfTheSameRateAreRefusedByTheDatabase() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            period(owner, base, "10", "2027-01-01", "2027-06-30");

            SQLException exact = rejected(owner, () -> period(owner, base, "11", "2027-01-01", "2027-06-30"));
            SQLException partialRight = rejected(owner, () -> period(owner, base, "11", "2027-06-30", "2027-12-31"));
            SQLException partialLeft = rejected(owner, () -> period(owner, base, "11", "2026-06-01", "2027-01-01"));
            SQLException contained = rejected(owner, () -> period(owner, base, "11", "2027-02-01", "2027-02-28"));
            SQLException containing = rejected(owner, () -> period(owner, base, "11", "2026-01-01", "2028-01-01"));
            SQLException open = rejected(owner, () -> period(owner, base, "11", "2027-03-01", null));

            for (SQLException overlap : List.of(exact, partialRight, partialLeft, contained, containing, open)) {
                assertThat(overlap.getSQLState()).as(overlap.getMessage()).isEqualTo("23P01");
                assertThat(overlap.getMessage()).contains("catalog_tax_rate_period_validity_excl");
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
            period(owner, base, "10", "2027-03-01", null);

            SQLException later = rejected(owner, () -> period(owner, base, "11", "2030-01-01", "2030-12-31"));
            SQLException laterOpen = rejected(owner, () -> period(owner, base, "11", "2031-01-01", null));
            SQLException sameDayBefore = rejected(owner, () -> period(owner, base, "11", "2027-01-01", "2027-03-01"));

            assertThat(later.getSQLState()).isEqualTo("23P01");
            assertThat(laterOpen.getSQLState()).isEqualTo("23P01");
            assertThat(sameDayBefore.getSQLState()).isEqualTo("23P01");
            period(owner, base, "9", "2027-01-01", "2027-02-28");
            owner.rollback();
        }
    }

    @Test
    void aContiguousValidityIsAllowedAndSoIsTheSameDatesForAnotherRateOrAnotherInstitution() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            period(owner, base, "10", "2027-01-01", "2027-06-30");

            period(owner, base, "11", "2027-07-01", "2027-12-31");
            period(owner, base, "12", "2028-01-01", null);
            period(owner, base, base.otherRate(), "4", "2027-01-01", "2027-06-30");
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            period(owner, theirs, theirs.rate(), "10", "2027-01-01", "2027-06-30");

            owner.rollback();
        }
    }

    @Test
    void closingAnOpenValidityAndInsertingTheReplacementInOneTransactionIsAcceptedWhateverTheOrder()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID first = period(owner, base, "10", "2027-01-01", null);

            update(owner, "update catalog_tax_rate_period set valid_to = date '2027-06-30' where id = ?", first);
            period(owner, base, "12", "2027-07-01", null);

            assertThat(count(owner, "select count(*) from catalog_tax_rate_period where valid_to is null"))
                    .isEqualTo(1);
            assertThat(count(owner, "select count(*) from catalog_tax_rate_period")).isEqualTo(2);
            owner.rollback();
        }
    }

    // --- A period is immutable ---

    @Test
    void theOwnerCannotEditThePercentageTheRateTheStartOrTheCreationOfAPeriod() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID id = period(owner, base, "12.5", "2027-01-01", null);

            for (String change : List.of("rate_percent = 1", "tax_rate_id = '" + base.otherRate() + "'",
                    "valid_from = date '2027-02-01'", "created_by = gen_random_uuid()",
                    "created_at = clock_timestamp()", "rate_percent = 1, valid_to = date '2027-06-30'")) {
                SQLException edit = rejected(owner,
                        () -> update(owner, "update catalog_tax_rate_period set " + change + " where id = ?", id));
                assertThat(edit.getSQLState()).as(change).isEqualTo("23001");
                assertThat(edit.getMessage()).contains("never edited");
            }
            assertThat(count(owner, "select count(*) from catalog_tax_rate_period where rate_percent = 12.5"))
                    .isEqualTo(1);
            owner.rollback();
        }
    }

    @Test
    void aValidityIsClosedOnceFromOpenToADateAndNeverReopenedNorMovedNorClearedAndNothingDeletesAPeriod()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID open = period(owner, base, "12.5", "2027-01-01", null);
            UUID closed = period(owner, base, base.otherRate(), "8", "2027-01-01", "2027-03-31");

            SQLException moved = rejected(owner, () -> update(owner,
                    "update catalog_tax_rate_period set valid_to = date '2027-02-28' where id = ?", closed));
            SQLException reopened = rejected(owner,
                    () -> update(owner, "update catalog_tax_rate_period set valid_to = null where id = ?", closed));
            SQLException beforeStart = rejected(owner, () -> update(owner,
                    "update catalog_tax_rate_period set valid_to = date '2026-12-31' where id = ?", open));
            SQLException delete = rejected(owner,
                    () -> update(owner, "delete from catalog_tax_rate_period where id = ?", closed));

            assertThat(moved.getSQLState()).isEqualTo("23001");
            assertThat(moved.getMessage()).contains("closed once");
            assertThat(reopened.getSQLState()).isEqualTo("23001");
            assertThat(beforeStart.getSQLState()).isEqualTo("23514");
            assertThat(beforeStart.getMessage()).contains("catalog_tax_rate_period_dates_chk");
            assertThat(delete.getSQLState()).isEqualTo("23001");
            assertThat(delete.getMessage()).contains("never deleted");
            update(owner, "update catalog_tax_rate_period set valid_to = date '2027-06-30' where id = ?", open);
            SQLException secondClosing = rejected(owner, () -> update(owner,
                    "update catalog_tax_rate_period set valid_to = date '2027-06-29' where id = ?", open));
            assertThat(secondClosing.getSQLState()).isEqualTo("23001");
            owner.rollback();
        }
    }

    @Test
    void theApplicationRoleCannotUpdateThePercentageNorDeleteAPeriodAndMayOnlyCloseAnOpenValidity()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID open;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            open = period(owner, base, "12.5", "2027-01-01", null);
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException percent = rejected(admin,
                    () -> update(admin, "update catalog_tax_rate_period set rate_percent = 1 where id = ?", open));
            SQLException from = rejected(admin, () -> update(admin,
                    "update catalog_tax_rate_period set valid_from = date '2027-02-01' where id = ?", open));
            SQLException delete = rejected(admin,
                    () -> admin.createStatement().execute("delete from catalog_tax_rate_period"));
            SQLException truncate = rejected(admin,
                    () -> admin.createStatement().execute("truncate catalog_tax_rate_period"));

            assertThat(percent.getSQLState()).as("UPDATE of the percentage").isEqualTo("42501");
            assertThat(from.getSQLState()).isEqualTo("42501");
            assertThat(delete.getSQLState()).as("DELETE").isEqualTo("42501");
            assertThat(truncate.getSQLState()).as("TRUNCATE").isEqualTo("42501");
            update(admin, "update catalog_tax_rate_period set valid_to = date '2027-06-30' where id = ?", open);
            assertThat(count(admin, "select count(*) from catalog_tax_rate_period where valid_to is not null"))
                    .isEqualTo(1);
            admin.rollback();
        }
    }

    // --- The rate: immutable code; never deleted ---

    @Test
    void theCodeOfARateNeverChangesNotEvenForTheOwnerAndItIsNeverDeleted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException code = rejected(owner,
                    () -> update(owner, "update catalog_tax_rate set code = 'OTRO' where id = ?", base.rate()));
            SQLException created = rejected(owner, () -> update(owner,
                    "update catalog_tax_rate set created_at = clock_timestamp() where id = ?", base.rate()));
            SQLException delete = rejected(owner,
                    () -> update(owner, "delete from catalog_tax_rate where id = ?", base.rate()));

            assertThat(code.getSQLState()).isEqualTo("23001");
            assertThat(created.getSQLState()).isEqualTo("23001");
            assertThat(delete.getSQLState()).isEqualTo("23001");
            update(owner, "update catalog_tax_rate set name = 'Nuevo', is_active = false, version = version + 1 "
                    + "where id = ?", base.rate());
            owner.rollback();
        }
    }

    @Test
    void theApplicationRoleMayChangeTheNameTheFlagAndTheVersionOfARateAndNothingElse() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID rate;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            rate = base(owner, institution).rate();
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException code = rejected(admin,
                    () -> update(admin, "update catalog_tax_rate set code = 'OTRO' where id = ?", rate));
            SQLException owner = rejected(admin, () -> update(admin,
                    "update catalog_tax_rate set institution_id = ? where id = ?", UUID.randomUUID(), rate));
            SQLException created = rejected(admin, () -> update(admin,
                    "update catalog_tax_rate set created_at = clock_timestamp() where id = ?", rate));
            SQLException delete = rejected(admin, () -> admin.createStatement().execute("delete from catalog_tax_rate"));

            assertThat(code.getSQLState()).isEqualTo("42501");
            assertThat(owner.getSQLState()).isEqualTo("42501");
            assertThat(created.getSQLState()).isEqualTo("42501");
            assertThat(delete.getSQLState()).isEqualTo("42501");
            update(admin, "update catalog_tax_rate set name = 'Nuevo', is_active = false, version = 1, "
                    + "updated_at = clock_timestamp() where id = ?", rate);
            try (ResultSet rows = admin.createStatement().executeQuery(
                    "select code from catalog_tax_rate where is_active = false")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("ISV");
            }
            admin.rollback();
        }
    }

    // --- The tax rate of a payment concept ---

    @Test
    void aConceptMayHaveNoRateAndATaxableOneWithoutARateIsTolerated() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            concept(owner, institution, UUID.randomUUID(), "VIEJO", true, null);
            concept(owner, institution, UUID.randomUUID(), "BUS", false, null);
            concept(owner, institution, UUID.randomUUID(), "COLEG", true, base.rate());

            assertThat(count(owner, "select count(*) from catalog_payment_concept where tax_rate_id is null"))
                    .isEqualTo(2);
            owner.rollback();
        }
    }

    @Test
    void aConceptThatIsNotTaxableCannotHaveARateNotOnInsertNorOnUpdate() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID taxed = UUID.randomUUID();
            concept(owner, institution, taxed, "COLEG", true, base.rate());

            SQLException insert = rejected(owner,
                    () -> concept(owner, institution, UUID.randomUUID(), "BUS", false, base.rate()));
            SQLException update = rejected(owner, () -> update(owner,
                    "update catalog_payment_concept set is_taxable = false where id = ?", taxed));

            assertThat(insert.getSQLState()).isEqualTo("23514");
            assertThat(insert.getMessage()).contains("catalog_payment_concept_tax_rate_chk");
            assertThat(update.getSQLState()).isEqualTo("23514");
            update(owner, "update catalog_payment_concept set is_taxable = false, tax_rate_id = null where id = ?",
                    taxed);
            owner.rollback();
        }
    }

    @Test
    void theRateOfAConceptMustExistInTheSameInstitution() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base mine = base(owner, institution);
            StaffRoleFixtures.fixInstitution(owner, other);
            Base theirs = base(owner, other);
            StaffRoleFixtures.fixInstitution(owner, institution);
            UUID own = UUID.randomUUID();
            concept(owner, institution, own, "COLEG", true, mine.rate());

            SQLException foreign = rejected(owner,
                    () -> concept(owner, institution, UUID.randomUUID(), "BUS", true, theirs.rate()));
            SQLException missing = rejected(owner,
                    () -> concept(owner, institution, UUID.randomUUID(), "MAT", true, UUID.randomUUID()));
            SQLException moved = rejected(owner, () -> update(owner,
                    "update catalog_payment_concept set tax_rate_id = ? where id = ?", theirs.rate(), own));

            assertThat(foreign.getSQLState()).isEqualTo("23503");
            assertThat(foreign.getMessage()).contains("catalog_payment_concept_tax_rate_fk");
            assertThat(missing.getSQLState()).isEqualTo("23503");
            assertThat(moved.getSQLState()).isEqualTo("23503");
            owner.rollback();
        }
    }

    @Test
    void theApplicationRoleMayAssignTheRateOfAConceptButStillNotItsCodeOrInstitution() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID rate;
        UUID concept = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            rate = base(owner, institution).rate();
            concept(owner, institution, concept, "COLEG", true, null);
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);

            SQLException code = rejected(admin,
                    () -> update(admin, "update catalog_payment_concept set code = 'OTRO' where id = ?", concept));
            assertThat(code.getSQLState()).isEqualTo("42501");
            update(admin, "update catalog_payment_concept set tax_rate_id = ?, version = 1 where id = ?", rate,
                    concept);
            assertThat(count(admin, "select count(*) from catalog_payment_concept where tax_rate_id is not null"))
                    .isEqualTo(1);
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

        for (String table : List.of("catalog_tax_rate", "catalog_tax_rate_period")) {
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

            SQLException foreignRate = rejected(admin, () -> rate(admin, theirs, UUID.randomUUID(), "X"));
            SQLException foreignPeriod = rejected(admin,
                    () -> period(admin, theirBase, theirBase.rate(), "10", "2030-01-01", null));
            assertThat(foreignRate.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            assertThat(foreignPeriod.getSQLState()).isEqualTo("42501");
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update catalog_tax_rate set is_active = false")).isZero();
                assertThat(s.executeUpdate("update catalog_tax_rate_period set valid_to = date '2027-06-30'")).isZero();
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs)) {
            assertThat(count(owner, "select count(*) from catalog_tax_rate where is_active")).isEqualTo(2);
            assertThat(count(owner, "select count(*) from catalog_tax_rate_period where valid_to is null"))
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
            for (String table : List.of("catalog_tax_rate", "catalog_tax_rate_period")) {
                assertThat(count(readonly, "select count(*) from " + table)).as(table).isGreaterThanOrEqualTo(1);
                SQLException update = rejected(readonly, () -> readonly.createStatement()
                        .execute("update " + table + " set " + (table.endsWith("period") ? "valid_to" : "name")
                                + " = " + (table.endsWith("period") ? "date '2027-06-30'" : "'x'")));
                assertThat(update.getSQLState()).as("UPDATE of %s by the read-only role", table).isEqualTo("42501");
            }
            readonly.rollback();
        }
        try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            portal.setAutoCommit(false);
            for (String table : List.of("catalog_tax_rate", "catalog_tax_rate_period")) {
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
            period(owner, base, "12.5", "2027-01-01", null);
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

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
 * The table of the payment concepts (V27) against a real PostgreSQL (docs/cortes/catalog-payment-concepts.md): the
 * constraints that enforce the rules of the cut, the row-level security that keeps one institution from reading or
 * writing the rows of another, and the privileges (no one deletes, the portal sees nothing). Constraint rejections run
 * as {@code confia_owner}, which holds every privilege, so what refuses the row is the constraint under test and never
 * a missing grant.
 */
class CatalogPaymentConceptSchemaIT extends CommittingPostgresIntegrationTest {

    private static final String TABLE = "catalog_payment_concept";

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

    private static void concept(Connection c, UUID institution, UUID id, String code, String category)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                insert into catalog_payment_concept
                    (institution_id, id, code, name, category, is_taxable, is_recurring)
                values (?, ?, ?, 'Nombre', ?, true, false)
                """)) {
            s.setObject(1, institution);
            s.setObject(2, id);
            s.setString(3, code);
            s.setString(4, category);
            s.executeUpdate();
        }
    }

    // --- Constraints ---

    @Test
    void aCodeIsUniqueInTheInstitutionWhateverItsCapitalsAndAnotherInstitutionMayRepeatIt() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            concept(owner, institution, UUID.randomUUID(), "COLEG", "TUITION");

            SQLException same = rejected(owner,
                    () -> concept(owner, institution, UUID.randomUUID(), "COLEG", "TUITION"));
            SQLException capitals = rejected(owner,
                    () -> concept(owner, institution, UUID.randomUUID(), "coLeg", "OTHER"));

            assertThat(same.getSQLState()).isEqualTo("23505");
            assertThat(same.getMessage()).contains("catalog_payment_concept_code_uq");
            assertThat(capitals.getSQLState()).isEqualTo("23505");
            assertThat(capitals.getMessage()).contains("catalog_payment_concept_code_uq");
            concept(owner, institution, UUID.randomUUID(), "MATRI", "ENROLLMENT");
            StaffRoleFixtures.fixInstitution(owner, other);
            concept(owner, other, UUID.randomUUID(), "COLEG", "TUITION");
            owner.rollback();
        }
    }

    @Test
    void theCategoryIsOneOfTheSixAndAnythingElseIsRefused() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            for (String category : List.of("TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER")) {
                concept(owner, institution, UUID.randomUUID(), "C-" + category, category);
            }

            SQLException lower = rejected(owner,
                    () -> concept(owner, institution, UUID.randomUUID(), "X1", "tuition"));
            SQLException unknown = rejected(owner,
                    () -> concept(owner, institution, UUID.randomUUID(), "X2", "SCHOLARSHIP"));

            assertThat(lower.getSQLState()).isEqualTo("23514");
            assertThat(lower.getMessage()).contains("catalog_payment_concept_category_chk");
            assertThat(unknown.getSQLState()).isEqualTo("23514");
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

        assertThat(visibleInstitutions(mine)).containsOnly(mine).isNotEmpty();
        assertThat(visibleInstitutions(theirs)).containsOnly(theirs).isNotEmpty();
        assertThat(visibleInstitutions(null)).as("with no context").isEmpty();
        assertThat(visibleInstitutions(UUID.randomUUID())).as("as a stranger").isEmpty();
    }

    @Test
    void theApplicationRoleCannotWriteARowOfAnotherInstitutionNorTouchOneThroughAnUpdate() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seed(theirs);
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);

            SQLException foreignInsert = rejected(admin,
                    () -> concept(admin, theirs, UUID.randomUUID(), "ZZZ", "OTHER"));
            assertThat(foreignInsert.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update " + TABLE + " set is_active = false")).isZero();
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs);
                ResultSet rows = owner.createStatement()
                        .executeQuery("select count(*) from " + TABLE + " where is_active")) {
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
        seed(institution);
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);
            SQLException delete = rejected(admin, () -> admin.createStatement().execute("delete from " + TABLE));
            assertThat(delete.getSQLState()).as("DELETE").isEqualTo("42501");
            SQLException truncate = rejected(admin, () -> admin.createStatement().execute("truncate " + TABLE));
            assertThat(truncate.getSQLState()).as("TRUNCATE").isEqualTo("42501");
            try (ResultSet rows = admin.createStatement().executeQuery("select count(*) from " + TABLE)) {
                rows.next();
                assertThat(rows.getLong(1)).as("SELECT").isEqualTo(1);
            }
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update " + TABLE + " set is_active = false")).as("UPDATE").isEqualTo(1);
            }
            admin.rollback();
        }
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            try (ResultSet rows = readonly.createStatement().executeQuery("select count(*) from " + TABLE)) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
            SQLException update = rejected(readonly,
                    () -> readonly.createStatement().execute("update " + TABLE + " set is_active = false"));
            assertThat(update.getSQLState()).as("UPDATE by the read-only role").isEqualTo("42501");
            readonly.rollback();
        }
        try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            portal.setAutoCommit(false);
            SQLException select = rejected(portal,
                    () -> portal.createStatement().executeQuery("select 1 from " + TABLE).close());
            assertThat(select.getSQLState()).as("SELECT by the portal role").isEqualTo("42501");
            portal.rollback();
        }
    }

    // --- Seeding and reading ---

    private static void seed(UUID institution) throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            concept(owner, institution, UUID.randomUUID(), "COLEG", "TUITION");
            owner.commit();
        }
    }

    /** Every institution the table shows to {@code confia_admin_app} under the given context. */
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

package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffRole;
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
 * The shape of {@code identity_staff_account_role} (V14) against a real PostgreSQL
 * (rbac-permission-matrix-and-audit-integration design.md D4; scenarios I278 to I283 and BI121).
 * Every rejection is run as {@code confia_owner}, which holds every privilege, so what
 * refuses the row is the constraint under test and never a missing grant or the row policy. A
 * rejected statement is rolled back to a savepoint and the transaction keeps going, which lets one
 * test show the rejection and, right after it, the acceptance of the same row made valid.
 */
class IdentityStaffAccountRoleSchemaIT extends CommittingPostgresIntegrationTest {

    private static final String PK = "identity_staff_account_role_pk";
    private static final String CODE_CHECK = "identity_staff_account_role_code_chk";
    private static final String ACCOUNT_FK = "identity_staff_account_role_account_fk";

    private interface SqlAction {
        void run() throws SQLException;
    }

    /** Runs {@code statement}, expects the database to refuse it, and rolls back to before it. */
    private static SQLException rejected(Connection connection, SqlAction statement)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        try {
            statement.run();
        } catch (SQLException e) {
            connection.rollback(before);
            return e;
        }
        throw new AssertionError("the database accepted a statement it had to refuse");
    }

    @Test
    void aSecondAssignmentOfTheSameAccountIsRejectedWhateverTheRole() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            StaffRoleFixtures.insertAccount(owner, institution, account);
            StaffRoleFixtures.assign(owner, institution, account, "cashier");

            SQLException otherRole = rejected(owner,
                    () -> StaffRoleFixtures.assign(owner, institution, account, "auditor"));
            SQLException sameRole = rejected(owner,
                    () -> StaffRoleFixtures.assign(owner, institution, account, "cashier"));

            assertThat(otherRole.getSQLState()).isEqualTo("23505");
            assertThat(otherRole.getMessage()).contains(PK);
            assertThat(sameRole.getSQLState()).isEqualTo("23505");
            assertThat(sameRole.getMessage()).contains(PK);
            owner.rollback();
        }
    }

    @Test
    void onlyTheSixCodesOfTheCatalogAreAcceptedAndAnyOtherIsRejectedByTheClosedSetCheck()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            for (StaffRole role : StaffRole.values()) {
                UUID account = UUID.randomUUID();
                StaffRoleFixtures.insertAccount(owner, institution, account);
                StaffRoleFixtures.assign(owner, institution, account, role.code());
            }
            UUID account = UUID.randomUUID();
            StaffRoleFixtures.insertAccount(owner, institution, account);
            for (String code : List.of("ghost_role", "", "Cashier", "cashier ", "root")) {
                SQLException failure = rejected(owner,
                        () -> StaffRoleFixtures.assign(owner, institution, account, code));
                assertThat(failure.getSQLState()).as("code [%s]", code).isEqualTo("23514");
                assertThat(failure.getMessage()).contains(CODE_CHECK);
            }
            owner.rollback();
        }
    }

    @Test
    void anAssignmentCannotNameAnAccountThatDoesNotExistInItsOwnInstitution()
            throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(first)) {
            StaffRoleFixtures.insertAccount(owner, first, account);
            StaffRoleFixtures.fixInstitution(owner, second);

            SQLException failure = rejected(owner,
                    () -> StaffRoleFixtures.assign(owner, second, account, "cashier"));
            assertThat(failure.getSQLState()).isEqualTo("23503");
            assertThat(failure.getMessage()).contains(ACCOUNT_FK);

            StaffRoleFixtures.fixInstitution(owner, first);
            StaffRoleFixtures.assign(owner, first, account, "cashier");
            owner.rollback();
        }
    }

    @Test
    void anInstitutionReadsOnlyItsOwnAssignmentsAndWithoutContextItReadsNoneNotAnError()
            throws SQLException {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        StaffRoleFixtures.committed(first, UUID.randomUUID(), "cashier");
        StaffRoleFixtures.committed(second, UUID.randomUUID(), "auditor");

        assertThat(visibleInstitutions(second)).as("context of INS-2").containsExactly(second);
        assertThat(visibleInstitutions(first)).as("context of INS-1").containsExactly(first);
        assertThat(visibleInstitutions(null)).as("no context at all").isEmpty();
        assertThat(visibleInstitutions(new UUID(0, 0))).as("an institution with no rows").isEmpty();
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
                    ResultSet rows = statement.executeQuery(
                            "select institution_id from identity_staff_account_role")) {
                while (rows.next()) {
                    seen.add(rows.getObject(1, UUID.class));
                }
            }
            admin.rollback();
        }
        return seen;
    }

    @Test
    void rowSecurityIsForcedOnTheOwnerToo() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            StaffRoleFixtures.insertAccount(owner, institution, account);
            StaffRoleFixtures.assign(owner, institution, account, "cashier");

            SQLException foreign = rejected(owner,
                    () -> StaffRoleFixtures.assign(owner, other, UUID.randomUUID(), "cashier"));
            assertThat(foreign.getSQLState()).as("the policy refuses it before the foreign key")
                    .isEqualTo("42501");
            assertThat(foreign.getMessage()).contains("row-level security");
            assertThat(count(owner, "identity_staff_account_role", account)).as("own context").isEqualTo(1);
            StaffRoleFixtures.fixInstitution(owner, other);
            assertThat(count(owner, "identity_staff_account_role", account)).as("another context").isZero();
            owner.rollback();
        }
    }

    @Test
    void anAccountWithoutAnAssignmentIsAValidAccountWithNoRow() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, null);

        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);
            assertThat(count(admin, "identity_staff_account", account)).as("the account").isEqualTo(1);
            assertThat(count(admin, "identity_staff_account_role", account)).as("its role").isZero();
            admin.rollback();
        }
    }

    private static int count(Connection connection, String table, UUID account) throws SQLException {
        String column = table.endsWith("_role") ? "account_id" : "id";
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from " + table + " where " + column + " = ?")) {
            statement.setObject(1, account);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    @Test
    void theTableStoresTheAssignmentAndNothingElseAndNoGlobalRoleOrPermissionTableExists() {
        assertThat(dsl.fetch("""
                select attname from pg_attribute
                 where attrelid = 'identity_staff_account_role'::regclass
                   and attnum > 0 and not attisdropped order by attnum
                """).getValues("attname", String.class))
                .containsExactly("institution_id", "account_id", "role_code", "created_at");
        assertThat(dsl.fetch("""
                select a.attname from pg_index i
                  join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any (i.indkey)
                 where i.indrelid = 'identity_staff_account_role'::regclass and i.indisprimary
                 order by array_position(i.indkey, a.attnum)
                """).getValues("attname", String.class))
                .containsExactly("institution_id", "account_id");
        assertThat(dsl.fetch("""
                select relname from pg_class
                 where relnamespace = 'public'::regnamespace and relkind = 'r'
                   and (relname like '%role%' or relname like '%permission%')
                """).getValues("relname", String.class))
                .containsExactly("identity_staff_account_role");
    }

    /** The set the table accepts and the catalog in code cannot drift apart: same six codes, no more. */
    @Test
    void theClosedSetOfTheTableIsExactlyTheCatalogInCode() {
        String definition = dsl.fetchOne("select pg_get_constraintdef(oid) as d from pg_constraint "
                + "where conname = ?", CODE_CHECK).get("d", String.class);

        assertThat(StaffRole.values()).hasSize(6).allSatisfy(role ->
                assertThat(definition).contains("'" + role.code() + "'"));
        assertThat(definition.split("'", -1).length - 1).as("quotes: six literals, two each")
                .isEqualTo(12);
    }
}

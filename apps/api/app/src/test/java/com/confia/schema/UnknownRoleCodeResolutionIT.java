package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.AccountPermissions;
import com.confia.identity.domain.StaffRole;
import com.confia.identity.infrastructure.JooqStaffRoleAssignmentRepository;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.TransactionRunner;
import com.confia.support.StaffRoleFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Scenario AZ19 (rbac-permission-matrix-and-audit-integration design.md D5), in the {@code schema}
 * package because {@link StepwiseDatabase} is package-private: the real resolver over a database made
 * for the test, whose {@code V14} constraint is dropped so that a code outside the catalog can be stored.
 */
class UnknownRoleCodeResolutionIT {

    /**
     * AZ19: a stored code that is not in the catalog grants nothing. The constraint is dropped, as the
     * owner, on a database made for this test, so no other test ever sees a table without it; the same
     * database holds an account with a valid role, which makes the empty answer a decision of the
     * resolver and not of an empty table.
     */
    @Test
    void aStoredCodeOutsideTheCatalogResolvesToNoPermissionAndDoesNotThrow() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID ghost = UUID.randomUUID();
        UUID cashier = UUID.randomUUID();
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("14")) {
            try (Connection owner = database.connectionAs("confia_owner")) {
                owner.setAutoCommit(false);
                StaffRoleFixtures.fixInstitution(owner, institution);
                insertAccountBefore15(owner, institution, ghost);
                insertAccountBefore15(owner, institution, cashier);
                try (Statement statement = owner.createStatement()) {
                    statement.execute("alter table identity_staff_account_role drop constraint "
                            + "identity_staff_account_role_code_chk");
                }
                StaffRoleFixtures.assign(owner, institution, ghost, "ghost_role");
                StaffRoleFixtures.assign(owner, institution, cashier, "cashier");
                owner.commit();
            }
            DataSource admin = database.dataSourceAs("confia_admin_app");
            TransactionRunner runner = new TransactionRunner(new DataSourceTransactionManager(admin), admin);
            DSLContext context = DSL.using(new TransactionAwareDataSourceProxy(admin), SQLDialect.POSTGRES);
            AccountPermissions resolver =
                    new AccountPermissions(runner, new JooqStaffRoleAssignmentRepository(context));

            assertThat(resolver.resolve(actor(institution, ghost))).isEmpty();
            assertThat(resolver.resolve(actor(institution, cashier)))
                    .isEqualTo(StaffRole.CASHIER.permissions());
        }
    }

    /**
     * The database is pinned to version 14, where the staff account still requires the second
     * factor column, so the shared fixture (written for the current schema) cannot insert it.
     */
    private static void insertAccountBefore15(Connection connection, UUID institution, UUID account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, ?, true)
                """)) {
            statement.setObject(1, institution);
            statement.setObject(2, account);
            statement.setString(3, "staff-" + account + "@colegio.edu.hn");
            statement.setString(4, "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g");
            statement.executeUpdate();
        }
    }

    private static AuthenticatedActor actor(UUID institution, UUID account) {
        Instant now = Instant.parse("2026-11-04T12:00:00Z");
        return new AuthenticatedActor(account, institution, UUID.randomUUID(), UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD), now,
                now.plusSeconds(600));
    }

}

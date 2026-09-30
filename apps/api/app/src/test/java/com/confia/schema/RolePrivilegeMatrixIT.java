package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Role attribute and privilege-matrix gates over the real PostgreSQL roles and grants created in
 * PR A1 (roles) and PR A2 (grants) — design.md decision 10, points 5 and 6. Every assertion reads
 * {@code pg_roles} and {@code has_table_privilege} directly against the real database, never a
 * hand-maintained list.
 */
class RolePrivilegeMatrixIT extends PostgresIntegrationTest {

    private static final List<String> FIVE_ROLES = List.of("confia_owner", "confia_admin_app",
            "confia_portal_app", "confia_readonly", "confia_backup");

    /** Both tables PR B2a's V2 migration creates (design.md decisions 3 and 7). */
    private static final List<String> AUDIT_TABLES =
            List.of("shared_audit_log", "shared_audit_chain_head");

    /** Table PR C1's V4 migration creates (idempotency-key-infrastructure design.md decision 2). */
    private static final String IDEMPOTENCY_TABLE = "shared_idempotency_key";

    /**
     * The two tables PR C1's {@code V5} migration creates
     * (identity-module-and-password-authentication design.md decision 4): the staff account and
     * its login backoff state, both sharing the exact privilege shape of {@code
     * shared_idempotency_key} above — a non-financial table, so {@code UPDATE} for
     * {@code confia_admin_app} is the rule, never {@code DELETE}.
     */
    private static final List<String> IDENTITY_TABLES =
            List.of("identity_staff_account", "identity_login_backoff");

    /**
     * The four tables PR C1's {@code V6} migration creates
     * (column-encryption-and-mfa-totp design.md decisions 1 and 3): the data-encryption-key
     * envelope table (owned by {@code com.confia.shared.crypto}) and the three identity MFA
     * tables (TOTP credential, recovery code, TOTP verification backoff). All four share the
     * exact privilege shape of {@code identity_login_backoff} above — non-financial tables, so
     * {@code UPDATE} for {@code confia_admin_app} is the rule, never {@code DELETE}.
     */
    private static final List<String> CRYPTO_MFA_TABLES =
            List.of("shared_data_encryption_key", "identity_mfa_totp_credential",
                    "identity_mfa_recovery_code", "identity_mfa_totp_backoff");

    /**
     * The table PR C1's {@code V7} migration creates (password-recovery-token design.md decision
     * 1; specs/build-integrity/spec.md, requirement "Permisos de acceso a la tabla de tokens de
     * recuperación por rol de base de datos"): same privilege shape as the tables above —
     * {@code UPDATE} marks a token consumed or superseded, and no role deletes, because expired
     * rows are retained until change 9 decides the purge (owner, 2026-09-30).
     */
    private static final String PASSWORD_RESET_TOKEN_TABLE = "identity_password_reset_token";

    private static final List<String> ALL_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE");

    @Test
    void noneOfTheFiveRolesIsSuperuserOrHasBypassRls() {
        for (String role : FIVE_ROLES) {
            RoleAttributes attributes = roleAttributesOf(role);
            assertThat(attributes.rolsuper()).as("%s must not be SUPERUSER", role).isFalse();
            assertThat(attributes.rolbypassrls()).as("%s must not have BYPASSRLS", role)
                    .isFalse();
        }
    }

    @Test
    void confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnTheRootTable() {
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "SELECT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "INSERT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "organization_institution", "DELETE"))
                .isFalse();
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnTheRootTableButCanSelectFlywayHistory() {
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "SELECT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "INSERT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "UPDATE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "organization_institution", "DELETE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_portal_app", "flyway_schema_history", "SELECT"))
                .isTrue();
    }

    @Test
    void confiaReadonlyOnlyHasSelectOnTheRootTable() {
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "SELECT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "INSERT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "UPDATE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "organization_institution", "DELETE"))
                .isFalse();
    }

    /**
     * {@code confia_owner} conserves every privilege on both audit tables by definition — it is
     * never subject to {@code GRANT}/{@code REVOKE}. This is deliberately not a defect: the
     * append-only trigger, not this matrix, is what rejects the owner's {@code UPDATE}/{@code
     * DELETE}/{@code TRUNCATE}, proven by a separate test ({@code AuditLogAppendOnlyIT}, design.md
     * decision 8).
     */
    @Test
    void confiaOwnerRetainsAllPrivilegesOnBothAuditTablesByDefinition() {
        for (String table : AUDIT_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_owner", table, privilege))
                        .as("confia_owner must retain %s on %s by definition", privilege, table)
                        .isTrue();
            }
        }
    }

    @Test
    void confiaAdminAppCanSelectAndInsertOnSharedAuditLogButHasNoPrivilegeOnTheChainHead() {
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "SELECT")).isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "INSERT")).isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "UPDATE")).isFalse();
        assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_log", "DELETE")).isFalse();

        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_admin_app", "shared_audit_chain_head", privilege))
                    .as("confia_admin_app must have no privilege at all on "
                            + "shared_audit_chain_head; only the future SECURITY DEFINER trigger "
                            + "writes it (design.md decision 3)")
                    .isFalse();
        }
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnEitherAuditTable() {
        for (String table : AUDIT_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_portal_app", table, privilege))
                        .as("confia_portal_app must have no privilege on %s", table).isFalse();
            }
        }
    }

    @Test
    void confiaReadonlyOnlySelectsSharedAuditLogAndHasNoPrivilegeOnTheChainHead() {
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "SELECT")).isTrue();
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "INSERT")).isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "UPDATE")).isFalse();
        assertThat(hasTablePrivilege("confia_readonly", "shared_audit_log", "DELETE")).isFalse();

        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_readonly", "shared_audit_chain_head", privilege))
                    .isFalse();
        }
    }

    /** {@code confia_backup} reads through {@code pg_read_all_data}, which never bypasses RLS. */
    @Test
    void confiaBackupCanOnlySelectBothAuditTablesThroughPgReadAllData() {
        for (String table : AUDIT_TABLES) {
            assertThat(hasTablePrivilege("confia_backup", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_backup", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "DELETE")).isFalse();
        }
    }

    /** {@code pg_roles.rolsuper} and {@code rolbypassrls} for one role, read from the catalogue. */
    private RoleAttributes roleAttributesOf(String role) {
        var row = dsl.fetchOne("select rolsuper, rolbypassrls from pg_roles where rolname = ?",
                role);
        return new RoleAttributes(row.get("rolsuper", Boolean.class),
                row.get("rolbypassrls", Boolean.class));
    }

    /**
     * The scenario "{@code PUBLIC} no tiene ningún privilegio de partida"
     * (specs/audit-trail/spec.md, requirement "Permisos de acceso a {@code shared_audit_log} por
     * rol de base de datos"), which had no test until the SDD verification of this change found it
     * missing.
     *
     * <p>Asserting that {@code confia_portal_app} holds nothing does <b>not</b> cover this: that is
     * one named role with no {@code GRANT} of its own. {@code PUBLIC} is the pseudo-role every
     * other role inherits from, so a privilege granted to it would reach roles this matrix never
     * names — including ones created years from now. Both halves of the scenario are checked here:
     * the pseudo-role itself, which is what {@code REVOKE ALL ... FROM PUBLIC} acts on, and a
     * freshly created role that is none of the five, which is what the scenario's premise says.
     */
    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnEitherAuditTable() throws SQLException {
        for (String table : AUDIT_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("public", table, privilege))
                        .as("PUBLIC must hold no %s on %s: REVOKE ALL ... FROM PUBLIC is what "
                                + "keeps every present and future role from inheriting it",
                                privilege, table)
                        .isFalse();
            }
        }

        String scratchRole = "probe_role_outside_the_five";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String table : AUDIT_TABLES) {
                    for (String privilege : ALL_PRIVILEGES) {
                        assertThat(hasTablePrivilege(scratchRole, table, privilege))
                                .as("a role that is none of the five must inherit no %s on %s",
                                        privilege, table)
                                .isFalse();
                    }
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /**
     * The five roles against {@code shared_idempotency_key}
     * (idempotency-key-infrastructure design.md decision 3; specs/build-integrity/spec.md,
     * requirement "Permisos de acceso a {@code shared_idempotency_key} por rol de base de datos"):
     * {@code confia_admin_app} gets exactly {@code SELECT}, {@code INSERT} and {@code UPDATE}
     * (never {@code DELETE} — non-financial table, so {@code UPDATE} is the rule, not an
     * exception); {@code confia_portal_app} gets nothing at all; {@code confia_readonly} gets only
     * {@code SELECT}; {@code confia_backup} reads through {@code pg_read_all_data}; {@code
     * confia_owner} retains all four by definition, not subject to {@code GRANT}/{@code REVOKE}.
     */
    @Test
    void confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnTheIdempotencyKeyTable() {
        assertThat(hasTablePrivilege("confia_admin_app", IDEMPOTENCY_TABLE, "SELECT")).isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", IDEMPOTENCY_TABLE, "INSERT")).isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", IDEMPOTENCY_TABLE, "UPDATE")).isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", IDEMPOTENCY_TABLE, "DELETE")).isFalse();
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnTheIdempotencyKeyTable() {
        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_portal_app", IDEMPOTENCY_TABLE, privilege))
                    .as("confia_portal_app must have no privilege at all on %s "
                            + "(brecha con destino: F3/F4)", IDEMPOTENCY_TABLE)
                    .isFalse();
        }
    }

    @Test
    void confiaReadonlyOnlySelectsTheIdempotencyKeyTable() {
        assertThat(hasTablePrivilege("confia_readonly", IDEMPOTENCY_TABLE, "SELECT")).isTrue();
        assertThat(hasTablePrivilege("confia_readonly", IDEMPOTENCY_TABLE, "INSERT")).isFalse();
        assertThat(hasTablePrivilege("confia_readonly", IDEMPOTENCY_TABLE, "UPDATE")).isFalse();
        assertThat(hasTablePrivilege("confia_readonly", IDEMPOTENCY_TABLE, "DELETE")).isFalse();
    }

    /** {@code confia_backup} reads through {@code pg_read_all_data}, which never bypasses RLS. */
    @Test
    void confiaBackupCanOnlySelectTheIdempotencyKeyTableThroughPgReadAllData() {
        assertThat(hasTablePrivilege("confia_backup", IDEMPOTENCY_TABLE, "SELECT")).isTrue();
        assertThat(hasTablePrivilege("confia_backup", IDEMPOTENCY_TABLE, "INSERT")).isFalse();
        assertThat(hasTablePrivilege("confia_backup", IDEMPOTENCY_TABLE, "UPDATE")).isFalse();
        assertThat(hasTablePrivilege("confia_backup", IDEMPOTENCY_TABLE, "DELETE")).isFalse();
    }

    /** {@code confia_owner} is not subject to {@code GRANT}/{@code REVOKE}: retains all four. */
    @Test
    void confiaOwnerRetainsAllPrivilegesOnTheIdempotencyKeyTableByDefinition() {
        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_owner", IDEMPOTENCY_TABLE, privilege))
                    .as("confia_owner must retain %s on %s by definition", privilege,
                            IDEMPOTENCY_TABLE)
                    .isTrue();
        }
    }

    /**
     * {@code PUBLIC} and a role outside the five (design.md decision 3's {@code REVOKE ALL ...
     * FROM PUBLIC}; same discipline {@code publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnEitherAuditTable}
     * already applies to the audit tables — {@code confia_portal_app} holding nothing does not
     * cover {@code PUBLIC}, the pseudo-role every other role, present or future, inherits from).
     */
    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnTheIdempotencyKeyTable()
            throws SQLException {
        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("public", IDEMPOTENCY_TABLE, privilege))
                    .as("PUBLIC must hold no %s on %s", privilege, IDEMPOTENCY_TABLE).isFalse();
        }

        String scratchRole = "probe_role_outside_the_five_idempotency";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String privilege : ALL_PRIVILEGES) {
                    assertThat(hasTablePrivilege(scratchRole, IDEMPOTENCY_TABLE, privilege))
                            .as("a role that is none of the five must inherit no %s on %s",
                                    privilege, IDEMPOTENCY_TABLE)
                            .isFalse();
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /**
     * The five roles against both identity tables (identity-module-and-password-authentication
     * design.md decision 4; specs/build-integrity/spec.md, requirements "Tablas nuevas de
     * identidad..." and "Permisos de acceso a las tablas nuevas de identidad por rol de base de
     * datos"): {@code confia_admin_app} gets exactly {@code SELECT}, {@code INSERT} and
     * {@code UPDATE} (never {@code DELETE}); {@code confia_portal_app} gets nothing at all on
     * either table (docs/03-seguridad.md §6.1, "sin acceso alguno a ... ni shared_audit_log", and
     * decision 4's correspondencia documental extending that same treatment to
     * {@code identity_login_backoff}); {@code confia_readonly} gets only {@code SELECT}; {@code
     * confia_backup} reads through {@code pg_read_all_data}; {@code confia_owner} retains all four
     * by definition.
     */
    @Test
    void confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnBothIdentityTables() {
        for (String table : IDENTITY_TABLES) {
            assertThat(hasTablePrivilege("confia_admin_app", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "INSERT")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "UPDATE")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "DELETE"))
                    .as("confia_admin_app must never have DELETE on %s: clearing the backoff "
                            + "counter is an UPDATE to zero, never a row deletion", table)
                    .isFalse();
        }
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnEitherIdentityTable() {
        for (String table : IDENTITY_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_portal_app", table, privilege))
                        .as("confia_portal_app must have no privilege at all on %s", table)
                        .isFalse();
            }
        }
    }

    @Test
    void confiaReadonlyOnlySelectsBothIdentityTables() {
        for (String table : IDENTITY_TABLES) {
            assertThat(hasTablePrivilege("confia_readonly", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_readonly", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_readonly", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_readonly", table, "DELETE")).isFalse();
        }
    }

    /** {@code confia_backup} reads through {@code pg_read_all_data}, which never bypasses RLS. */
    @Test
    void confiaBackupCanOnlySelectBothIdentityTablesThroughPgReadAllData() {
        for (String table : IDENTITY_TABLES) {
            assertThat(hasTablePrivilege("confia_backup", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_backup", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "DELETE")).isFalse();
        }
    }

    /** {@code confia_owner} is not subject to {@code GRANT}/{@code REVOKE}: retains all four. */
    @Test
    void confiaOwnerRetainsAllPrivilegesOnBothIdentityTablesByDefinition() {
        for (String table : IDENTITY_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_owner", table, privilege))
                        .as("confia_owner must retain %s on %s by definition", privilege, table)
                        .isTrue();
            }
        }
    }

    /**
     * {@code PUBLIC} and a role outside the five (decision 4's {@code REVOKE ALL ... FROM PUBLIC});
     * same discipline the audit tables and the idempotency-key table already apply above.
     */
    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnEitherIdentityTable()
            throws SQLException {
        for (String table : IDENTITY_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("public", table, privilege))
                        .as("PUBLIC must hold no %s on %s", privilege, table).isFalse();
            }
        }

        String scratchRole = "probe_role_outside_the_five_identity";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String table : IDENTITY_TABLES) {
                    for (String privilege : ALL_PRIVILEGES) {
                        assertThat(hasTablePrivilege(scratchRole, table, privilege))
                                .as("a role that is none of the five must inherit no %s on %s",
                                        privilege, table)
                                .isFalse();
                    }
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /**
     * The five roles against all four crypto/MFA tables (column-encryption-and-mfa-totp design.md
     * decisions 1 and 3; specs/build-integrity/spec.md, requirements "Tablas nuevas de cifrado de
     * columna y MFA..." and "Permisos de acceso a las tablas nuevas de cifrado de columna y
     * MFA... por rol de base de datos"): {@code confia_admin_app} gets exactly {@code SELECT},
     * {@code INSERT} and {@code UPDATE} (never {@code DELETE}); {@code confia_portal_app} gets
     * nothing at all on any of the four; {@code confia_readonly} gets only {@code SELECT}; {@code
     * confia_backup} reads through {@code pg_read_all_data}; {@code confia_owner} retains all four
     * by definition.
     */
    @Test
    void confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnAllFourCryptoMfaTables() {
        for (String table : CRYPTO_MFA_TABLES) {
            assertThat(hasTablePrivilege("confia_admin_app", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "INSERT")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "UPDATE")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "DELETE"))
                    .as("confia_admin_app must never have DELETE on %s: retiring a DEK or "
                            + "invalidating a recovery code is an UPDATE, never a row deletion",
                            table)
                    .isFalse();
        }
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnAnyCryptoMfaTable() {
        for (String table : CRYPTO_MFA_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_portal_app", table, privilege))
                        .as("confia_portal_app must have no privilege at all on %s", table)
                        .isFalse();
            }
        }
    }

    @Test
    void confiaReadonlyOnlySelectsAllFourCryptoMfaTables() {
        for (String table : CRYPTO_MFA_TABLES) {
            assertThat(hasTablePrivilege("confia_readonly", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_readonly", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_readonly", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_readonly", table, "DELETE")).isFalse();
        }
    }

    /** {@code confia_backup} reads through {@code pg_read_all_data}, which never bypasses RLS. */
    @Test
    void confiaBackupCanOnlySelectAllFourCryptoMfaTablesThroughPgReadAllData() {
        for (String table : CRYPTO_MFA_TABLES) {
            assertThat(hasTablePrivilege("confia_backup", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_backup", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "DELETE")).isFalse();
        }
    }

    /** {@code confia_owner} is not subject to {@code GRANT}/{@code REVOKE}: retains all four. */
    @Test
    void confiaOwnerRetainsAllPrivilegesOnAllFourCryptoMfaTablesByDefinition() {
        for (String table : CRYPTO_MFA_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_owner", table, privilege))
                        .as("confia_owner must retain %s on %s by definition", privilege, table)
                        .isTrue();
            }
        }
    }

    /**
     * {@code PUBLIC} and a role outside the five (design.md decision 3's {@code REVOKE ALL ...
     * FROM PUBLIC}); same discipline the audit, idempotency-key and identity tables already apply
     * above.
     */
    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnAnyCryptoMfaTable() throws SQLException {
        for (String table : CRYPTO_MFA_TABLES) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("public", table, privilege))
                        .as("PUBLIC must hold no %s on %s", privilege, table).isFalse();
            }
        }

        String scratchRole = "probe_role_outside_the_five_crypto_mfa";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String table : CRYPTO_MFA_TABLES) {
                    for (String privilege : ALL_PRIVILEGES) {
                        assertThat(hasTablePrivilege(scratchRole, table, privilege))
                                .as("a role that is none of the five must inherit no %s on %s",
                                        privilege, table)
                                .isFalse();
                    }
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /**
     * The five roles against the password-reset token table, exactly and without one more or one
     * less (scenario "La matriz de privilegios cubre la tabla nueva para los cinco roles").
     * {@code IdentityRowSecurityIT} runs the real statements behind the same grants.
     */
    @Test
    void confiaAdminAppCanSelectInsertAndUpdateButNeverDeleteOnThePasswordResetTokenTable() {
        assertThat(hasTablePrivilege("confia_admin_app", PASSWORD_RESET_TOKEN_TABLE, "SELECT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", PASSWORD_RESET_TOKEN_TABLE, "INSERT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", PASSWORD_RESET_TOKEN_TABLE, "UPDATE"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_admin_app", PASSWORD_RESET_TOKEN_TABLE, "DELETE"))
                .as("confia_admin_app must never have DELETE on %s: consuming or superseding a "
                        + "token is an UPDATE, and expired rows are retained until change 9",
                        PASSWORD_RESET_TOKEN_TABLE)
                .isFalse();
    }

    @Test
    void confiaPortalAppHasNoPrivilegeOnThePasswordResetTokenTable() {
        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_portal_app", PASSWORD_RESET_TOKEN_TABLE,
                    privilege))
                    .as("confia_portal_app must have no privilege at all on %s: staff data",
                            PASSWORD_RESET_TOKEN_TABLE)
                    .isFalse();
        }
    }

    @Test
    void confiaReadonlyOnlySelectsThePasswordResetTokenTable() {
        assertThat(hasTablePrivilege("confia_readonly", PASSWORD_RESET_TOKEN_TABLE, "SELECT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_readonly", PASSWORD_RESET_TOKEN_TABLE, "INSERT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_readonly", PASSWORD_RESET_TOKEN_TABLE, "UPDATE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_readonly", PASSWORD_RESET_TOKEN_TABLE, "DELETE"))
                .isFalse();
    }

    /** {@code confia_backup} reads through {@code pg_read_all_data}, which never bypasses RLS. */
    @Test
    void confiaBackupCanOnlySelectThePasswordResetTokenTableThroughPgReadAllData() {
        assertThat(hasTablePrivilege("confia_backup", PASSWORD_RESET_TOKEN_TABLE, "SELECT"))
                .isTrue();
        assertThat(hasTablePrivilege("confia_backup", PASSWORD_RESET_TOKEN_TABLE, "INSERT"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_backup", PASSWORD_RESET_TOKEN_TABLE, "UPDATE"))
                .isFalse();
        assertThat(hasTablePrivilege("confia_backup", PASSWORD_RESET_TOKEN_TABLE, "DELETE"))
                .isFalse();
    }

    /** {@code confia_owner} is not subject to {@code GRANT}/{@code REVOKE}: retains all four. */
    @Test
    void confiaOwnerRetainsAllPrivilegesOnThePasswordResetTokenTableByDefinition() {
        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("confia_owner", PASSWORD_RESET_TOKEN_TABLE, privilege))
                    .as("confia_owner must retain %s on %s by definition", privilege,
                            PASSWORD_RESET_TOKEN_TABLE)
                    .isTrue();
        }
    }

    /** {@code PUBLIC} and a role outside the five: {@code V7} revokes before it grants. */
    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnThePasswordResetTokenTable()
            throws SQLException {
        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("public", PASSWORD_RESET_TOKEN_TABLE, privilege))
                    .as("PUBLIC must hold no %s on %s", privilege, PASSWORD_RESET_TOKEN_TABLE)
                    .isFalse();
        }

        String scratchRole = "probe_role_outside_the_five_password_reset";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String privilege : ALL_PRIVILEGES) {
                    assertThat(hasTablePrivilege(scratchRole, PASSWORD_RESET_TOKEN_TABLE,
                            privilege))
                            .as("a role that is none of the five must inherit no %s on %s",
                                    privilege, PASSWORD_RESET_TOKEN_TABLE)
                            .isFalse();
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /** {@code has_table_privilege(role, table, privilege)}, evaluated by PostgreSQL itself. */
    private boolean hasTablePrivilege(String role, String table, String privilege) {
        return dsl.fetchOne("select has_table_privilege(?, ?, ?) as has_priv", role, table,
                privilege).get("has_priv", Boolean.class);
    }

    private record RoleAttributes(boolean rolsuper, boolean rolbypassrls) {
    }
}

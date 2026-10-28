package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

    /**
     * The two tables {@code V8} creates (session-tokens-and-web-layer design.md decision 8;
     * specs/build-integrity/spec.md, requirement "Permisos de acceso a las tablas de familias y
     * tokens de refresco por rol de base de datos y por columna"), mapped to the only columns
     * {@code confia_admin_app} may {@code UPDATE}. They are the first tables with column-level
     * grants, so the table-level {@code UPDATE} must be absent and the column set exact.
     */
    private static final Map<String, Set<String>> REFRESH_TABLE_UPDATABLE_COLUMNS = Map.of(
            "identity_refresh_token_family",
            Set.of("last_used_at", "revoked_at", "revoked_reason"),
            "identity_refresh_token",
            Set.of("consumed_at", "consumed_user_agent_fingerprint", "replaced_by",
                    "successor_token_ciphertext"));

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

    /**
     * {@code confia_admin_app} on the refresh-token tables: {@code SELECT} and {@code INSERT} at
     * table level, {@code UPDATE} <b>only</b> on the columns that change state, and never {@code
     * DELETE}. {@code has_table_privilege(..., 'UPDATE')} is true only for a table-level grant, so
     * it is false here; {@code has_column_privilege} is read for every real column of the table
     * (from {@code pg_attribute}, not a hand-written list) so a column added later without a
     * deliberate grant stays immutable and a widened grant is caught.
     */
    @Test
    void confiaAdminAppUpdatesOnlyTheStateColumnsAndNeverDeletesOnTheRefreshTokenTables() {
        REFRESH_TABLE_UPDATABLE_COLUMNS.forEach((table, updatable) -> {
            assertThat(hasTablePrivilege("confia_admin_app", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "INSERT")).isTrue();
            assertThat(hasTablePrivilege("confia_admin_app", table, "UPDATE"))
                    .as("UPDATE on %s must be granted per column, never for the whole table",
                            table)
                    .isFalse();
            assertThat(hasTablePrivilege("confia_admin_app", table, "DELETE"))
                    .as("confia_admin_app must never have DELETE on %s: rows are retained until "
                            + "change 9 decides the purge", table)
                    .isFalse();

            List<String> columns = columnsOf(table);
            assertThat(columns).as("columns of %s read from the catalogue", table)
                    .containsAll(updatable);
            for (String column : columns) {
                assertThat(hasColumnPrivilege("confia_admin_app", table, column, "UPDATE"))
                        .as("UPDATE on %s.%s", table, column)
                        .isEqualTo(updatable.contains(column));
                assertThat(hasColumnPrivilege("confia_admin_app", table, column, "SELECT"))
                        .as("SELECT on %s.%s", table, column).isTrue();
            }
        });
    }

    @Test
    void confiaPortalAppHasNoPrivilegeAtAnyLevelOnTheRefreshTokenTables() {
        REFRESH_TABLE_UPDATABLE_COLUMNS.keySet().forEach(table -> {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_portal_app", table, privilege))
                        .as("confia_portal_app must have no %s on %s: staff data", privilege,
                                table)
                        .isFalse();
            }
            for (String column : columnsOf(table)) {
                for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "REFERENCES")) {
                    assertThat(hasColumnPrivilege("confia_portal_app", table, column, privilege))
                            .as("confia_portal_app must have no column-level %s on %s.%s",
                                    privilege, table, column)
                            .isFalse();
                }
            }
        });
    }

    @Test
    void confiaReadonlyOnlySelectsTheRefreshTokenTablesAtEveryLevel() {
        REFRESH_TABLE_UPDATABLE_COLUMNS.keySet().forEach(table -> {
            assertThat(hasTablePrivilege("confia_readonly", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_readonly", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_readonly", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_readonly", table, "DELETE")).isFalse();
            for (String column : columnsOf(table)) {
                assertThat(hasColumnPrivilege("confia_readonly", table, column, "UPDATE"))
                        .as("confia_readonly must not UPDATE %s.%s", table, column).isFalse();
            }
        });
    }

    /** {@code confia_backup} reads through {@code pg_read_all_data}, which never bypasses RLS. */
    @Test
    void confiaBackupCanOnlySelectTheRefreshTokenTablesThroughPgReadAllData() {
        REFRESH_TABLE_UPDATABLE_COLUMNS.keySet().forEach(table -> {
            assertThat(hasTablePrivilege("confia_backup", table, "SELECT")).isTrue();
            assertThat(hasTablePrivilege("confia_backup", table, "INSERT")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "UPDATE")).isFalse();
            assertThat(hasTablePrivilege("confia_backup", table, "DELETE")).isFalse();
        });
    }

    /** {@code confia_owner} is not subject to {@code GRANT}/{@code REVOKE}: retains all four. */
    @Test
    void confiaOwnerRetainsAllPrivilegesOnTheRefreshTokenTablesByDefinition() {
        REFRESH_TABLE_UPDATABLE_COLUMNS.keySet().forEach(table -> {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("confia_owner", table, privilege))
                        .as("confia_owner must retain %s on %s by definition", privilege, table)
                        .isTrue();
            }
        });
    }

    /** {@code PUBLIC} and a role outside the five: {@code V8} revokes before it grants. */
    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnTheRefreshTokenTables()
            throws SQLException {
        for (String table : REFRESH_TABLE_UPDATABLE_COLUMNS.keySet()) {
            for (String privilege : ALL_PRIVILEGES) {
                assertThat(hasTablePrivilege("public", table, privilege))
                        .as("PUBLIC must hold no %s on %s", privilege, table).isFalse();
            }
            for (String column : columnsOf(table)) {
                assertThat(hasColumnPrivilege("public", table, column, "UPDATE"))
                        .as("PUBLIC must hold no column-level UPDATE on %s.%s", table, column)
                        .isFalse();
            }
        }

        String scratchRole = "probe_role_outside_the_five_refresh_token";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String table : REFRESH_TABLE_UPDATABLE_COLUMNS.keySet()) {
                    for (String privilege : ALL_PRIVILEGES) {
                        assertThat(hasTablePrivilege(scratchRole, table, privilege))
                                .as("a role that is none of the five must inherit no %s on %s",
                                        privilege, table)
                                .isFalse();
                    }
                    for (String column : columnsOf(table)) {
                        assertThat(hasColumnPrivilege(scratchRole, table, column, "UPDATE"))
                                .as("no column-level UPDATE on %s.%s", table, column).isFalse();
                    }
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /**
     * The five roles against {@code scheduled_tasks} (background-jobs-with-db-scheduler design.md
     * decision 15; specs/build-integrity/spec.md, scenarios BI65 and BI67), exact, with no privilege
     * more and none less: {@code confia_admin_app} operates the table (the worker connects with that
     * role); {@code confia_portal_app} inserts and reads (probe P-6: {@code scheduleIfNotExists}
     * reads before it inserts) but never changes or deletes a task; {@code confia_readonly} holds
     * nothing (docs/03 section 6.1: a task row can carry business identifiers); {@code
     * confia_backup} reads through {@code pg_read_all_data}; {@code confia_owner} retains all four
     * by definition.
     */
    private static final Map<String, Set<String>> SCHEDULED_TASKS_MATRIX = Map.of(
            "confia_owner", Set.of("SELECT", "INSERT", "UPDATE", "DELETE"),
            "confia_admin_app", Set.of("SELECT", "INSERT", "UPDATE", "DELETE"),
            "confia_portal_app", Set.of("SELECT", "INSERT"),
            "confia_readonly", Set.of(),
            "confia_backup", Set.of("SELECT"));

    @Test
    void scheduledTasksPrivilegesAreExactlyTheMatrixForTheFiveRoles() throws SQLException {
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres")) {
            assertThat(scheduledTasksMatrixViolations(superuser))
                    .as("scheduled_tasks privileges that differ from the matrix").isEmpty();
        }
        assertThat(SCHEDULED_TASKS_MATRIX.keySet()).as("non-vacuous: all five roles are covered")
                .containsExactlyInAnyOrderElementsOf(FIVE_ROLES);
    }

    @Test
    void aPrivilegeOfMoreOnScheduledTasksBreaksTheMatrix() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("10")) {
            try (Connection superuser = database.connectionAs("postgres")) {
                assertThat(scheduledTasksMatrixViolations(superuser))
                        .as("the fixture starts from the exact matrix").isEmpty();
            }
            try (Connection owner = database.connectionAs("confia_owner");
                    Statement statement = owner.createStatement()) {
                statement.execute("GRANT DELETE ON scheduled_tasks TO confia_portal_app");
            }
            try (Connection superuser = database.connectionAs("postgres")) {
                assertThat(scheduledTasksMatrixViolations(superuser))
                        .singleElement().asString().contains("confia_portal_app", "DELETE");
            }
        }
    }

    @Test
    void publicAndAnyRoleOutsideTheFiveInheritNoPrivilegeOnScheduledTasks() throws SQLException {
        for (String privilege : ALL_PRIVILEGES) {
            assertThat(hasTablePrivilege("public", "scheduled_tasks", privilege))
                    .as("PUBLIC must hold no %s on scheduled_tasks", privilege).isFalse();
        }

        String scratchRole = "probe_role_outside_the_five_scheduler";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String privilege : ALL_PRIVILEGES) {
                    assertThat(hasTablePrivilege(scratchRole, "scheduled_tasks", privilege))
                            .as("a role that is none of the five must inherit no %s", privilege)
                            .isFalse();
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    /** One line per role whose held privileges on scheduled_tasks differ from the matrix. */
    private static List<String> scheduledTasksMatrixViolations(Connection connection)
            throws SQLException {
        List<String> violations = new ArrayList<>();
        for (var expected : SCHEDULED_TASKS_MATRIX.entrySet()) {
            Set<String> held = new TreeSet<>();
            for (String privilege : ALL_PRIVILEGES) {
                try (var statement = connection.prepareStatement(
                        "select has_table_privilege(?, 'scheduled_tasks', ?)")) {
                    statement.setString(1, expected.getKey());
                    statement.setString(2, privilege);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        if (result.getBoolean(1)) {
                            held.add(privilege);
                        }
                    }
                }
            }
            if (!held.equals(new TreeSet<>(expected.getValue()))) {
                violations.add("%s holds %s on scheduled_tasks but the matrix says %s"
                        .formatted(expected.getKey(), held,
                                new TreeSet<>(expected.getValue())));
            }
        }
        return violations;
    }
    /**
     * The two purge functions of {@code V11} (background-jobs-with-db-scheduler design.md decision
     * 16; specs/build-integrity/spec.md, scenarios BI68, BI69, BI72, BI83 and BI84), by the argument
     * list that identifies each: the only way to remove the rows they purge, because no role holds
     * {@code DELETE} on the tables.
     */
    private static final List<String> PURGE_FUNCTIONS =
            List.of("identity_purge_password_reset_tokens(timestamptz, integer)",
                    "identity_purge_refresh_token_families(timestamptz, integer)");

    private static final List<String> PURGED_TABLES = List.of("identity_password_reset_token",
            "identity_refresh_token_family", "identity_refresh_token");

    @Test
    void executeOnThePurgeFunctionsIsHeldByTheAdministrativeRoleAndTheOwnerOnly()
            throws SQLException {
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres")) {
            assertThat(purgeExecuteViolations(superuser))
                    .as("EXECUTE grants that differ from the matrix").isEmpty();
        }
    }

    @Test
    void anExecuteGrantOfMoreOnAPurgeFunctionBreaksTheMatrix() throws SQLException {
        try (StepwiseDatabase database = StepwiseDatabase.migratedTo("11")) {
            try (Connection superuser = database.connectionAs("postgres")) {
                assertThat(purgeExecuteViolations(superuser))
                        .as("the fixture starts from the exact matrix").isEmpty();
            }
            try (Connection owner = database.connectionAs("confia_owner");
                    Statement statement = owner.createStatement()) {
                statement.execute("GRANT EXECUTE ON FUNCTION "
                        + "identity_purge_refresh_token_families(timestamptz, integer) "
                        + "TO confia_readonly");
            }
            try (Connection superuser = database.connectionAs("postgres")) {
                assertThat(purgeExecuteViolations(superuser))
                        .singleElement().asString().contains("confia_readonly");
            }
        }
    }

    @Test
    void publicAndAnyRoleOutsideTheFiveCannotExecuteThePurgeFunctions() throws SQLException {
        for (String function : PURGE_FUNCTIONS) {
            assertThat(hasFunctionPrivilege("public", function))
                    .as("PUBLIC must not execute %s", function).isFalse();
            assertThat(dsl.fetchOne("""
                    select count(*) as entries from pg_proc p, aclexplode(p.proacl) a
                     where p.oid = ?::regprocedure and a.grantee = 0
                    """, function).get("entries", Long.class))
                    .as("the ACL of %s has no entry for PUBLIC", function).isZero();
        }

        String scratchRole = "probe_role_outside_the_five_purge";
        try (Connection superuser = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = superuser.createStatement()) {
            statement.execute("drop role if exists " + scratchRole);
            statement.execute("create role " + scratchRole + " nosuperuser nobypassrls");
            try {
                for (String function : PURGE_FUNCTIONS) {
                    assertThat(hasFunctionPrivilege(scratchRole, function))
                            .as("a role that is none of the five must not execute %s", function)
                            .isFalse();
                }
            } finally {
                statement.execute("drop role if exists " + scratchRole);
            }
        }
    }

    @Test
    void thePurgeFunctionsAreOwnedByTheOwnerRunWithItsRightsAndPinTheirSearchPath() {
        for (String function : PURGE_FUNCTIONS) {
            var row = dsl.fetchOne("""
                    select pg_get_userbyid(p.proowner) as owner, p.prosecdef as definer,
                           p.proconfig as settings
                      from pg_proc p where p.oid = ?::regprocedure
                    """, function);
            assertThat(row.get("owner", String.class)).as("owner of %s", function)
                    .isEqualTo("confia_owner");
            assertThat(row.get("definer", Boolean.class)).as("%s is SECURITY DEFINER", function)
                    .isTrue();
            assertThat(row.get("settings", String[].class)).as("settings of %s", function)
                    .containsExactly("search_path=pg_catalog, public");
        }
    }

    @Test
    void noRoleOtherThanTheOwnerCanDeleteFromTheTablesThePurgeFunctionsServe() {
        for (String table : PURGED_TABLES) {
            for (String role : List.of("confia_admin_app", "confia_portal_app",
                    "confia_readonly", "confia_backup", "public")) {
                assertThat(hasTablePrivilege(role, table, "DELETE"))
                        .as("%s must not delete from %s", role, table).isFalse();
            }
        }
    }

    /** One line per role whose EXECUTE on a purge function differs from the matrix. */
    private static List<String> purgeExecuteViolations(Connection connection)
            throws SQLException {
        List<String> violations = new ArrayList<>();
        for (String function : PURGE_FUNCTIONS) {
            for (String role : FIVE_ROLES) {
                boolean expected = role.equals("confia_admin_app") || role.equals("confia_owner");
                try (var statement = connection.prepareStatement(
                        "select has_function_privilege(?, ?, 'EXECUTE')")) {
                    statement.setString(1, role);
                    statement.setString(2, function);
                    try (var result = statement.executeQuery()) {
                        result.next();
                        if (result.getBoolean(1) != expected) {
                            violations.add("%s %s EXECUTE on %s but the matrix says %s"
                                    .formatted(role, result.getBoolean(1) ? "holds" : "lacks",
                                            function, expected ? "held" : "not held"));
                        }
                    }
                }
            }
        }
        return violations;
    }

    private boolean hasFunctionPrivilege(String role, String function) {
        return dsl.fetchOne("select has_function_privilege(?, ?, 'EXECUTE') as has_priv", role,
                function).get("has_priv", Boolean.class);
    }


    /** Real, non-dropped columns of a public table, read from {@code pg_attribute}. */
    private List<String> columnsOf(String table) {
        return dsl.fetch("""
                select attname from pg_attribute
                 where attrelid = (quote_ident(?))::regclass and attnum > 0 and not attisdropped
                 order by attnum
                """, table).getValues("attname", String.class);
    }

    /**
     * {@code has_column_privilege}: true for a table-level grant too, which is why the table-level
     * assertions above are made separately.
     */
    private boolean hasColumnPrivilege(String role, String table, String column,
            String privilege) {
        return dsl.fetchOne("select has_column_privilege(?, ?, ?, ?) as has_priv", role, table,
                column, privilege).get("has_priv", Boolean.class);
    }

    /** {@code has_table_privilege(role, table, privilege)}, evaluated by PostgreSQL itself. */
    private boolean hasTablePrivilege(String role, String table, String privilege) {
        return dsl.fetchOne("select has_table_privilege(?, ?, ?) as has_priv", role, table,
                privilege).get("has_priv", Boolean.class);
    }

    private record RoleAttributes(boolean rolsuper, boolean rolbypassrls) {
    }
}

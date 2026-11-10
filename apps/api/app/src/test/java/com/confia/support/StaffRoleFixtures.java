package com.confia.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Seeds staff accounts and their role assignment as {@code confia_owner}, the only database role that
 * can write {@code identity_staff_account_role}: {@code confia_admin_app} holds {@code SELECT} alone
 * (rbac-permission-matrix-and-audit-integration design.md D4), so nothing but a test can assign a
 * role until change 8b. {@code FORCE ROW LEVEL SECURITY} applies to the owner as well, so every
 * statement runs in a transaction that fixed {@code app.institution_id} first.
 *
 * <p>Two shapes: the methods that take a {@link Connection} write inside the caller's transaction (the
 * schema tests roll it back), and {@link #committed} opens its own and commits (the integration
 * tests, whose base class truncates the tables afterwards). The email is a placeholder and the
 * password hash is a well-formed Argon2id string that no password matches.
 */
public final class StaffRoleFixtures {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    private StaffRoleFixtures() {
    }

    /** A transaction of {@code confia_owner} (not committed yet) whose institution context is {@code institution}. */
    public static Connection ownerTransaction(UUID institution) throws SQLException {
        Connection connection = SharedPostgresContainer.connectionAs("confia_owner");
        connection.setAutoCommit(false);
        fixInstitution(connection, institution);
        return connection;
    }

    /** Sets {@code app.institution_id} for the rest of the transaction of {@code connection}. */
    public static void fixInstitution(Connection connection, UUID institution) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("select set_config('app.institution_id', ?, true)")) {
            statement.setString(1, institution.toString());
            statement.execute();
        }
    }

    /** Inserts a staff account of {@code institution} through the transaction of {@code connection}. */
    public static void insertAccount(Connection connection, UUID institution, UUID account)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash)
                values (?, ?, ?, ?)
                """)) {
            statement.setObject(1, institution);
            statement.setObject(2, account);
            statement.setString(3, "staff-" + account + "@colegio.edu.hn");
            statement.setString(4, PLACEHOLDER_PASSWORD_HASH);
            statement.executeUpdate();
        }
    }

    /** Inserts the role assignment of an account, with the code as given, valid or not. */
    public static void assign(Connection connection, UUID institution, UUID account, String roleCode)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into identity_staff_account_role (institution_id, account_id, role_code)
                values (?, ?, ?)
                """)) {
            statement.setObject(1, institution);
            statement.setObject(2, account);
            statement.setString(3, roleCode);
            statement.executeUpdate();
        }
    }

    /** One committed account of {@code institution}, with the role {@code roleCode} or with none when it is null. */
    public static void committed(UUID institution, UUID account, String roleCode)
            throws SQLException {
        try (Connection connection = ownerTransaction(institution)) {
            insertAccount(connection, institution, account);
            if (roleCode != null) {
                assign(connection, institution, account, roleCode);
            }
            connection.commit();
        }
    }
}

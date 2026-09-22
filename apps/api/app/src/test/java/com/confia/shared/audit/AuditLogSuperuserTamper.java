package com.confia.shared.audit;

import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

/**
 * {@code SUPERUSER} manipulation of a confirmed {@code shared_audit_log} row, shared by {@link
 * AuditChainVerifierIT} and {@link AuditChainKnownLimitIT} (design.md decision 10; sonda S3, task
 * 5.1). Both methods open their own, short-lived connection as {@code "postgres"} — the container's
 * real, unmodified superuser (design.md decision 10: "La conexión de superusuario ya existe; no
 * hay que crear nada") — and always restore {@code session_replication_role} to {@code 'origin'}
 * before closing, even when the statement in between throws, so a failing assertion never leaves
 * the append-only trigger disabled for the rest of the JVM.
 *
 * <p>{@code column} is never attacker-reachable: it is always one of the three literal values
 * {@link #TAMPERABLE_COLUMNS} names, supplied by this test source itself
 * ({@code @ValueSource(strings = {"actor_label", "user_agent", "trace_id"})} in {@link
 * AuditChainVerifierIT}), the same trusted-source reasoning {@code
 * CommittingPostgresIntegrationTest#tablesWithoutABeforeTruncateTrigger} already documents for an
 * identifier that cannot be bound as a query parameter in any SQL dialect (CLAUDE.md rule 12
 * targets untrusted, attacker-reachable input). The allow-list below still rejects anything else
 * loudly, rather than trusting the caller silently.
 */
final class AuditLogSuperuserTamper {

    private static final Set<String> TAMPERABLE_COLUMNS =
            Set.of("reason", "actor_label", "user_agent", "trace_id");

    private AuditLogSuperuserTamper() {
    }

    /**
     * design.md decision 10's "alterada sin recalcular" scenario: disables the {@code BEFORE
     * UPDATE} trigger for this session only, changes exactly {@code column}, and restores the
     * trigger — {@code prev_hash} and {@code row_hash} are left exactly as the chaining trigger
     * originally computed them, so the row becomes internally inconsistent with its own recorded
     * hash.
     */
    static void tamperFieldWithoutRecalculating(UUID institutionId, long id, String column,
            String value) throws SQLException {
        requireTamperableColumn(column);
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres")) {
            setReplicationRole(connection, "replica");
            try (PreparedStatement update = connection.prepareStatement(
                    "update shared_audit_log set " + column
                            + " = ? where institution_id = ? and id = ?")) {
                update.setString(1, value);
                update.setObject(2, institutionId);
                update.setLong(3, id);
                update.executeUpdate();
            } finally {
                setReplicationRole(connection, "origin");
            }
        }
    }

    /**
     * design.md decision 10's "límite conocido" scenario: same disabled-trigger manipulation as
     * {@link #tamperFieldWithoutRecalculating}, but afterward recalculates {@code row_hash} — and,
     * since a row's hash feeds the next row's {@code prev_hash}, cascades that recalculation
     * forward through every following row up to the last, using {@code shared_audit_row_hash(...)},
     * the exact same PL/pgSQL function the chaining trigger itself calls (design.md decision 10:
     * "un actor con acceso administrativo tiene esa función igual que la tiene la prueba"). The
     * result is a chain that is, by design, internally consistent again — the declared, accepted
     * limit of this control until cambio 11's external anchor exists.
     */
    static void tamperAndRecalculateWholeChainFrom(UUID institutionId, long tamperedId,
            String column, String value) throws SQLException {
        requireTamperableColumn(column);
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres")) {
            setReplicationRole(connection, "replica");
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "update shared_audit_log set " + column
                                + " = ? where institution_id = ? and id = ?")) {
                    update.setString(1, value);
                    update.setObject(2, institutionId);
                    update.setLong(3, tamperedId);
                    update.executeUpdate();
                }
                recalculateChainFrom(connection, institutionId, tamperedId);
            } finally {
                setReplicationRole(connection, "origin");
            }
        }
    }

    /**
     * Cascades {@code shared_audit_row_hash(...)} forward from {@code fromId}'s own, untouched
     * {@code prev_hash} through every subsequent row, feeding each row's freshly recalculated
     * {@code row_hash} into the next row's {@code prev_hash} — exactly what a real chain recompute
     * does. Stops the moment {@code UPDATE ... RETURNING} matches no more rows (the last row of the
     * institution).
     */
    private static void recalculateChainFrom(Connection connection, UUID institutionId, long fromId)
            throws SQLException {
        byte[] prevHash = fetchPrevHash(connection, institutionId, fromId);
        long id = fromId;
        while (true) {
            byte[] recalculatedRowHash = recalculateOneRow(connection, institutionId, id, prevHash);
            if (recalculatedRowHash == null) {
                return;
            }
            prevHash = recalculatedRowHash;
            id++;
        }
    }

    private static byte[] fetchPrevHash(Connection connection, UUID institutionId, long id)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "select prev_hash from shared_audit_log where institution_id = ? and id = ?")) {
            select.setObject(1, institutionId);
            select.setLong(2, id);
            try (ResultSet resultSet = select.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException(
                            "no row id=" + id + " for institution " + institutionId);
                }
                return resultSet.getBytes("prev_hash");
            }
        }
    }

    /** {@code null} once {@code id} is past the institution's last row. */
    private static byte[] recalculateOneRow(Connection connection, UUID institutionId, long id,
            byte[] prevHash) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                update shared_audit_log
                   set prev_hash = ?,
                       row_hash = shared_audit_row_hash(?, id, institution_id, occurred_at,
                           actor_id, actor_kind, actor_label, source_ip, user_agent, request_id,
                           trace_id, action, entity_type, entity_id, outcome, before_value,
                           after_value, reason, approver_id)
                 where institution_id = ? and id = ?
                returning row_hash
                """)) {
            update.setBytes(1, prevHash);
            update.setBytes(2, prevHash);
            update.setObject(3, institutionId);
            update.setLong(4, id);
            try (ResultSet resultSet = update.executeQuery()) {
                return resultSet.next() ? resultSet.getBytes("row_hash") : null;
            }
        }
    }

    private static void setReplicationRole(Connection connection, String role) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = '" + role + "'");
        }
    }

    private static void requireTamperableColumn(String column) {
        if (!TAMPERABLE_COLUMNS.contains(column)) {
            throw new IllegalArgumentException(
                    "column '" + column + "' is not in the fixed, trusted allow-list " + TAMPERABLE_COLUMNS);
        }
    }
}

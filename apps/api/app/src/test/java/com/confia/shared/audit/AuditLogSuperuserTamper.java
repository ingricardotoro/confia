package com.confia.shared.audit;

import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
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

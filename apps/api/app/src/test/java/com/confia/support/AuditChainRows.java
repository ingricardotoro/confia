package com.confia.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Rows of {@code shared_audit_log} for the tests of the chain verification, written and altered with the
 * superuser of the container (the same real manipulation {@code AuditLogSuperuserTamper} makes). A row is
 * inserted at the {@code occurred_at} the test chooses, so a test can place rows on either side of the
 * start of a window; the chaining trigger still computes {@code prev_hash} and {@code row_hash} from the
 * values the row has, so the chain is genuine until a test alters a row.
 */
public final class AuditChainRows {

    private AuditChainRows() {
    }

    /** Inserts one row of {@code institution} that happened at {@code occurredAt} and returns its {@code id}. */
    public static long insert(UUID institution, Instant occurredAt) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres")) {
            // The chaining trigger writes the head of the chain of that institution, whose policy reads this setting.
            try (PreparedStatement context = connection.prepareStatement("select set_config('app.institution_id', ?, false)")) {
                context.setString(1, institution.toString());
                context.execute();
            }
            return insertRow(connection, institution, occurredAt);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long insertRow(Connection connection, UUID institution, Instant occurredAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                        insert into shared_audit_log
                            (institution_id, occurred_at, actor_kind, actor_label, request_id, action,
                             entity_type, entity_id, outcome)
                        values (?, ?, 'system', 'chain-fixture', gen_random_uuid(), 'test.action', 'test_entity',
                                'entity-1', 'success')
                        returning id
                        """)) {
            statement.setObject(1, institution);
            statement.setObject(2, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    /** Changes the {@code reason} of one row without recalculating any hash, so the chain no longer verifies. */
    public static void alter(UUID institution, long id) {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres")) {
            setReplicationRole(connection, "replica");
            try (PreparedStatement update = connection.prepareStatement(
                    "update shared_audit_log set reason = 'altered by the test' where institution_id = ? and id = ?")) {
                update.setObject(1, institution);
                update.setLong(2, id);
                update.executeUpdate();
            } finally {
                setReplicationRole(connection, "origin");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setReplicationRole(Connection connection, String role) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("set session_replication_role = " + role);
        }
    }
}

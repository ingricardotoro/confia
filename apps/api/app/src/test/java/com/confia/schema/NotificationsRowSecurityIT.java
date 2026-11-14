package com.confia.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Row-level security and the {@code CHECK} constraints of {@code notifications_email_delivery}
 * ({@code V16}; transactional-email-adapter specs/build-integrity/spec.md, scenarios BI154 and BI155).
 * As in {@code IdentityRowSecurityIT}, a catalogue assertion proves the policy exists; only a real
 * two-institution read and a real rejected statement prove it works. The hash is deliberately the same
 * in both institutions, so only the policy, never the data, keeps their rows apart.
 */
class NotificationsRowSecurityIT extends CommittingPostgresIntegrationTest {

    private static final String SQLSTATE_CHECK_VIOLATION = "23514";
    private static final String SQLSTATE_ROW_POLICY_VIOLATION = "42501";

    /** Not an address: 64 lowercase hex characters, all the shape constraint asks for. */
    private static final String SOME_HASH = "ab".repeat(32);

    private static final String INSERT = """
            insert into notifications_email_delivery
                (institution_id, id, request_id, attempted_at, channel, purpose, template_key,
                 template_version, recipient_hash, status, provider_message_id, failure_class)
            values (?, gen_random_uuid(), gen_random_uuid(), now(), ?, 'transactional',
                    'password-reset-link', 1, ?, ?, ?, ?)
            """;

    @Test
    void oneInstitutionCannotReadAnotherInstitutionsDeliveries() {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        seedSentRow(institutionA);
        seedSentRow(institutionB);

        assertThat(countVisible(institutionB, institutionA))
                .as("institution B must see none of institution A's rows, although the row "
                        + "physically exists: the policy, not the predicate, keeps them apart")
                .isZero();
        assertThat(countVisible(institutionB, institutionB))
                .as("institution B must still see its own row: a policy that hid everything "
                        + "would satisfy the assertion above for the wrong reason")
                .isEqualTo(1L);
    }

    @Test
    void anAbsentInstitutionContextReturnsZeroRowsNotAPermissionError() {
        seedSentRow(UUID.randomUUID());

        long count = transactionRunner().execute(
                new SecurityContext("", "system", "", UUID.randomUUID().toString()),
                () -> dsl.fetchOne("select count(*) as c from notifications_email_delivery")
                        .get("c", Number.class).longValue());

        assertThat(count).as("with no app.institution_id the policy denies by returning no rows")
                .isZero();
    }

    @Test
    void anInsertOfAnotherInstitutionsRowIsRejectedByThePolicy() throws SQLException {
        UUID contextInstitution = UUID.randomUUID();
        UUID otherInstitution = UUID.randomUUID();

        try (Connection connection = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            connection.setAutoCommit(false);
            setInstitutionContext(connection, contextInstitution);

            assertThatThrownBy(() -> insert(connection, otherInstitution, "email", SOME_HASH,
                    "failed", null, "unavailable"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security")
                    .extracting(thrown -> ((SQLException) thrown).getSQLState())
                    .isEqualTo(SQLSTATE_ROW_POLICY_VIOLATION);
            connection.rollback();

            setInstitutionContext(connection, contextInstitution);
            assertThat(insert(connection, contextInstitution, "email", SOME_HASH, "failed", null,
                    "unavailable")).as("control: the same row of its own institution is accepted")
                    .isEqualTo(1);
            connection.rollback();
        }
    }

    @Test
    void theConstraintsRejectIncoherentRowsEachByItsOwnName() throws SQLException {
        UUID institution = UUID.randomUUID();

        assertRejectedBy("notifications_email_delivery_outcome_chk", institution, "email",
                SOME_HASH, "sent", null, "unavailable");
        assertRejectedBy("notifications_email_delivery_outcome_chk", institution, "email",
                SOME_HASH, "failed", null, null);
        assertRejectedBy("notifications_email_delivery_outcome_chk", institution, "email",
                SOME_HASH, "failed", "provider-id-1", "unavailable");
        assertRejectedBy("notifications_email_delivery_channel_chk", institution, "sms",
                SOME_HASH, "sent", null, null);
        assertRejectedBy("notifications_email_delivery_recipient_hash_chk", institution, "email",
                "a".repeat(63), "sent", null, null);
        assertRejectedBy("notifications_email_delivery_recipient_hash_chk", institution, "email",
                "A".repeat(64), "sent", null, null);
        assertRejectedBy("notifications_email_delivery_failure_class_chk", institution, "email",
                SOME_HASH, "failed", null, "smtp-error");
        // A "pending" row also breaks the outcome constraint, which PostgreSQL reports first (its name
        // sorts before the status one), so only the rejection is asserted, not the constraint's name.
        assertRejectedBy("notifications_email_delivery", institution, "email", SOME_HASH,
                "pending", null, null);
    }

    @Test
    void theCoherentRowsOfBothStatusesAreAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            connection.setAutoCommit(false);
            setInstitutionContext(connection, institution);
            assertThat(insert(connection, institution, "email", SOME_HASH, "sent", "id-1", null))
                    .isEqualTo(1);
            assertThat(insert(connection, institution, "email", SOME_HASH, "sent", null, null))
                    .as("the provider identifier is optional on a sent row").isEqualTo(1);
            assertThat(insert(connection, institution, "email", SOME_HASH, "failed", null,
                    "timeout")).isEqualTo(1);
            connection.rollback();
        }
    }

    private static void assertRejectedBy(String constraint, UUID institution, String channel,
            String hash, String status, String providerId, String failureClass)
            throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            connection.setAutoCommit(false);
            setInstitutionContext(connection, institution);
            assertThatThrownBy(() -> insert(connection, institution, channel, hash, status,
                    providerId, failureClass))
                    .as("a row with status %s, channel %s, class %s", status, channel,
                            failureClass)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining(constraint)
                    .extracting(thrown -> ((SQLException) thrown).getSQLState())
                    .isEqualTo(SQLSTATE_CHECK_VIOLATION);
            connection.rollback();
        }
    }

    private static int insert(Connection connection, UUID institution, String channel,
            String hash, String status, String providerId, String failureClass)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, institution);
            statement.setString(2, channel);
            statement.setString(3, hash);
            statement.setString(4, status);
            statement.setString(5, providerId);
            statement.setString(6, failureClass);
            return statement.executeUpdate();
        }
    }

    private static void setInstitutionContext(Connection connection, UUID institution)
            throws SQLException {
        try (PreparedStatement statement = connection
                .prepareStatement("select set_config('app.institution_id', ?, true)")) {
            statement.setString(1, institution.toString());
            statement.execute();
        }
    }

    private void seedSentRow(UUID institution) {
        transactionRunner().execute(contextOf(institution), () -> {
            dsl.execute(INSERT, institution, "email", SOME_HASH, "sent", null, null);
            return null;
        });
    }

    private long countVisible(UUID contextInstitution, UUID queryInstitution) {
        return transactionRunner().execute(contextOf(contextInstitution),
                () -> dsl.fetchOne("select count(*) as c from notifications_email_delivery "
                        + "where institution_id = ?", queryInstitution)
                        .get("c", Number.class).longValue());
    }

    private static SecurityContext contextOf(UUID institution) {
        return new SecurityContext("", "system", institution.toString(),
                UUID.randomUUID().toString());
    }
}

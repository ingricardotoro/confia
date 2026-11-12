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
 * The event log of the consents of the guardians (V20) against a real PostgreSQL (docs/cortes/guardians-consent.md): the
 * checks on the closed sets and on the policy version (present exactly when the consent is granted, with its shape), the
 * composite key that keeps an event inside the institution of its guardian, the row-level security, and the privileges:
 * the application role can only insert and read (no UPDATE, DELETE or TRUNCATE privilege), and a trigger refuses every
 * update and delete even to the owner of the schema. Constraint rejections run as {@code confia_owner}, which holds
 * every privilege, so what refuses the row is the constraint under test and never a missing grant.
 */
class GuardiansGuardianConsentEventSchemaIT extends CommittingPostgresIntegrationTest {

    private static final String TABLE = "guardians_guardian_consent_event";

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

    private static void exec(Connection c, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                s.setObject(i + 1, parameters[i]);
            }
            s.executeUpdate();
        }
    }

    private static UUID guardian(Connection c, UUID institution, String code) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, "insert into guardians_guardian (institution_id, id, guardian_code, first_name, last_name) "
                + "values (?, ?, ?, 'Ana', 'Lopez')", institution, id, code);
        return id;
    }

    /** Inserts an event; a null version is sent as a null varchar. */
    private static UUID event(Connection c, UUID institution, UUID guardian, String channel, String purpose,
            String action, String evidence, String version) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, """
                insert into guardians_guardian_consent_event (institution_id, id, guardian_id, channel, purpose,
                    action, evidence_source, policy_version, recorded_by)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as varchar), ?)
                """, institution, id, guardian, channel, purpose, action, evidence, version, UUID.randomUUID());
        return id;
    }

    private static UUID grant(Connection c, UUID institution, UUID guardian) throws SQLException {
        return event(c, institution, guardian, "EMAIL", "INSTITUTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01");
    }

    // --- Constraints ---

    @Test
    void theChannelPurposeActionAndEvidenceHaveTheirClosedSetsAndTheTransactionalPurposeIsNotAConsent()
            throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID guardian = guardian(owner, institution, "P-000001");

            SQLException channel = rejected(owner, () -> event(owner, institution, guardian, "FAX", "MARKETING",
                    "GRANT", "SIGNED_PAPER_FORM", "2026-01"));
            SQLException transactional = rejected(owner, () -> event(owner, institution, guardian, "EMAIL",
                    "TRANSACTIONAL", "GRANT", "SIGNED_PAPER_FORM", "2026-01"));
            SQLException purpose = rejected(owner, () -> event(owner, institution, guardian, "EMAIL", "SPAM", "GRANT",
                    "SIGNED_PAPER_FORM", "2026-01"));
            SQLException action = rejected(owner, () -> event(owner, institution, guardian, "EMAIL", "MARKETING",
                    "REVOKE", "SIGNED_PAPER_FORM", null));
            SQLException evidence = rejected(owner, () -> event(owner, institution, guardian, "EMAIL", "MARKETING",
                    "GRANT", "ORAL", "2026-01"));

            assertThat(channel.getMessage()).contains("guardians_guardian_consent_event_channel_chk");
            assertThat(transactional.getMessage()).contains("guardians_guardian_consent_event_purpose_chk");
            assertThat(purpose.getMessage()).contains("guardians_guardian_consent_event_purpose_chk");
            assertThat(action.getMessage()).contains("guardians_guardian_consent_event_action_chk");
            assertThat(evidence.getMessage()).contains("guardians_guardian_consent_event_evidence_chk");
            for (String name : List.of("EMAIL", "SMS", "WHATSAPP")) {
                event(owner, institution, guardian, name, "MARKETING", "GRANT", "ADMIN_REGISTERED", "v1");
            }
            for (String name : List.of("INSTITUTIONAL", "MARKETING")) {
                event(owner, institution, guardian, "SMS", name, "WITHDRAW", "SIGNED_PAPER_FORM", null);
            }
            // The portal records its own evidence later: the database already accepts it.
            event(owner, institution, guardian, "EMAIL", "INSTITUTIONAL", "WITHDRAW", "PORTAL_FORM", null);
            owner.rollback();
        }
    }

    @Test
    void thePolicyVersionIsPresentExactlyWhenTheConsentIsGrantedAndHasTheDocumentedShape() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID guardian = guardian(owner, institution, "P-000001");

            SQLException grantWithout = rejected(owner, () -> event(owner, institution, guardian, "EMAIL", "MARKETING",
                    "GRANT", "SIGNED_PAPER_FORM", null));
            SQLException withdrawWith = rejected(owner, () -> event(owner, institution, guardian, "EMAIL", "MARKETING",
                    "WITHDRAW", "SIGNED_PAPER_FORM", "2026-01"));
            assertThat(grantWithout.getMessage()).contains("guardians_guardian_consent_event_version_chk");
            assertThat(withdrawWith.getMessage()).contains("guardians_guardian_consent_event_version_chk");
            for (String bad : List.of("", "2026 01", "2026/01", "v1;", "versión", "a".repeat(33))) {
                SQLException shape = rejected(owner, () -> event(owner, institution, guardian, "EMAIL", "MARKETING",
                        "GRANT", "SIGNED_PAPER_FORM", bad));
                assertThat(shape.getMessage()).as("version '%s'", bad).containsAnyOf(
                        "guardians_guardian_consent_event_version_shape_chk",
                        "value too long for type character varying(32)");
            }
            SQLException trailingNewline = rejected(owner, () -> event(owner, institution, guardian, "EMAIL",
                    "MARKETING", "GRANT", "SIGNED_PAPER_FORM", "v1\n"));
            assertThat(trailingNewline.getMessage()).contains("guardians_guardian_consent_event_version_shape_chk");
            for (String good : List.of("2026-01", "v1", "1.0.0", "policy_2026.rev-3", "a".repeat(32))) {
                event(owner, institution, guardian, "EMAIL", "MARKETING", "GRANT", "SIGNED_PAPER_FORM", good);
            }
            owner.rollback();
        }
    }

    @Test
    void anEventNeverCrossesInstitutionsBecauseItsGuardianKeyIsComposite() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID foreignGuardian;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(other)) {
            foreignGuardian = guardian(owner, other, "P-000001");
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            guardian(owner, institution, "P-000001");

            SQLException foreign = rejected(owner, () -> grant(owner, institution, foreignGuardian));
            SQLException unknown = rejected(owner, () -> grant(owner, institution, UUID.randomUUID()));

            assertThat(foreign.getMessage()).contains("guardians_guardian_consent_event_guardian_fk");
            assertThat(unknown.getMessage()).contains("guardians_guardian_consent_event_guardian_fk");
            owner.rollback();
        }
    }

    @Test
    void theOrderIsTheSequenceAndTheTimeIsTheServersWhenNobodyGivesIt() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID guardian = guardian(owner, institution, "P-000001");
            UUID first = grant(owner, institution, guardian);
            UUID second = event(owner, institution, guardian, "EMAIL", "INSTITUTIONAL", "WITHDRAW",
                    "ADMIN_REGISTERED", null);
            UUID third = grant(owner, institution, guardian);

            List<UUID> bySequence = new ArrayList<>();
            try (ResultSet rows = owner.createStatement().executeQuery(
                    "select id, abs(extract(epoch from clock_timestamp() - occurred_at)) < 60 "
                            + "from guardians_guardian_consent_event order by seq")) {
                while (rows.next()) {
                    bySequence.add(rows.getObject(1, UUID.class));
                    assertThat(rows.getBoolean(2)).as("occurred_at defaults to the server clock").isTrue();
                }
            }
            assertThat(bySequence).containsExactly(first, second, third);
            SQLException plainSeq = rejected(owner, () -> exec(owner,
                    "insert into guardians_guardian_consent_event (institution_id, id, seq, guardian_id, channel, "
                            + "purpose, action, evidence_source, policy_version, recorded_by) "
                            + "values (?, ?, 1, ?, 'EMAIL', 'MARKETING', 'GRANT', 'ADMIN_REGISTERED', 'v1', ?)",
                    institution, UUID.randomUUID(), guardian, UUID.randomUUID()));
            // The order is the database's: a writer cannot choose it (GENERATED ALWAYS).
            assertThat(plainSeq.getMessage()).contains("seq");
            owner.rollback();
        }
    }

    // --- Append-only ---

    @Test
    void noRoleEditsOrDeletesAnEventNotEvenTheOwnerOfTheSchema() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID guardian = guardian(owner, institution, "P-000001");
            UUID id = grant(owner, institution, guardian);

            SQLException update = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_consent_event set action = 'WITHDRAW', policy_version = null "
                            + "where id = ?", id));
            SQLException touch = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_consent_event set occurred_at = occurred_at where id = ?", id));
            SQLException delete = rejected(owner, () -> exec(owner,
                    "delete from guardians_guardian_consent_event where id = ?", id));

            for (SQLException e : List.of(update, touch, delete)) {
                assertThat(e.getSQLState()).isEqualTo("23000");
                assertThat(e.getMessage()).contains("immutable");
            }
            try (ResultSet rows = owner.createStatement().executeQuery(
                    "select count(*), min(action) from guardians_guardian_consent_event")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
                assertThat(rows.getString(2)).isEqualTo("GRANT");
            }
            owner.rollback();
        }
    }

    @Test
    void theApplicationRoleInsertsAndReadsAndHasNoUpdateDeleteOrTruncatePrivilege() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID guardian;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            guardian = guardian(owner, institution, "P-000001");
            owner.commit();
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);
            grant(admin, institution, guardian);
            try (ResultSet rows = admin.createStatement().executeQuery("select count(*) from " + TABLE)) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(1);
            }
            for (String sql : List.of("update " + TABLE + " set action = 'WITHDRAW'", "delete from " + TABLE,
                    "truncate " + TABLE, "update " + TABLE + " set policy_version = 'x'")) {
                SQLException refused = rejected(admin, () -> admin.createStatement().execute(sql));
                assertThat(refused.getSQLState()).as(sql).isEqualTo("42501");
            }
            admin.rollback();
        }
    }

    // --- Row-level security and the other roles ---

    @Test
    void eachInstitutionSeesOnlyItsOwnEventsAndTheApplicationCannotWriteForAnother() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        UUID theirGuardian = seed(theirs);
        seed(mine);

        assertThat(visibleInstitutions(mine)).containsOnly(mine).isNotEmpty();
        assertThat(visibleInstitutions(theirs)).containsOnly(theirs).isNotEmpty();
        assertThat(visibleInstitutions(null)).as("no context").isEmpty();
        assertThat(visibleInstitutions(UUID.randomUUID())).as("a stranger").isEmpty();
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);
            SQLException foreign = rejected(admin, () -> grant(admin, theirs, theirGuardian));
            assertThat(foreign.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            admin.rollback();
        }
    }

    @Test
    void theReadOnlyRoleReadsTheEventsButWritesNothingAndThePortalSeesNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID guardian = seed(institution);
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            try (ResultSet rows = readonly.createStatement().executeQuery("select * from " + TABLE)) {
                assertThat(rows.next()).isTrue();
            }
            SQLException insert = rejected(readonly, () -> grant(readonly, institution, guardian));
            assertThat(insert.getSQLState()).isEqualTo("42501");
            readonly.rollback();
        }
        try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
            portal.setAutoCommit(false);
            SQLException select = rejected(portal,
                    () -> portal.createStatement().executeQuery("select 1 from " + TABLE).close());
            assertThat(select.getSQLState()).isEqualTo("42501");
            portal.rollback();
        }
    }

    @Test
    void theTableHasNoContactDataColumnAndRowLevelSecurityIsForced() throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(UUID.randomUUID())) {
            List<String> columns = new ArrayList<>();
            try (ResultSet rows = owner.createStatement().executeQuery(
                    "select column_name from information_schema.columns where table_name = '" + TABLE
                            + "' order by ordinal_position")) {
                while (rows.next()) {
                    columns.add(rows.getString(1));
                }
            }
            assertThat(columns).containsExactly("institution_id", "id", "seq", "guardian_id", "channel", "purpose",
                    "action", "evidence_source", "policy_version", "occurred_at", "recorded_by");
            try (ResultSet rows = owner.createStatement().executeQuery(
                    "select relrowsecurity, relforcerowsecurity from pg_class where relname = '" + TABLE + "'")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isTrue();
                assertThat(rows.getBoolean(2)).isTrue();
            }
            owner.rollback();
        }
    }

    // --- Seeding and reading ---

    /** A guardian with one event, committed under the institution given; returns the guardian. */
    private static UUID seed(UUID institution) throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID guardian = guardian(owner, institution, "P-000001");
            grant(owner, institution, guardian);
            owner.commit();
            return guardian;
        }
    }

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

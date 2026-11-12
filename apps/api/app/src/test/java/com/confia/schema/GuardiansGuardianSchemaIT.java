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
 * The three tables of the guardians (V19) against a real PostgreSQL (docs/cortes/guardians-registry.md): the
 * constraints that enforce what is local to the rows (one current link per guardian and student, one primary payer,
 * one billing recipient, the percentage between 0.01 and 100, the document and its blind index together, the
 * composite keys that keep a link inside one institution), the row-level security that keeps one institution from
 * reading or writing the rows of another, and the privileges: nobody deletes, a link can only be closed (the one
 * column the application may update, and only once), the read-only role never sees the document, the RTN nor the
 * contact data, and the portal sees nothing. Constraint rejections run as {@code confia_owner}, which holds every
 * privilege, so what refuses the row is the constraint under test and never a missing grant.
 */
class GuardiansGuardianSchemaIT extends CommittingPostgresIntegrationTest {

    private static final List<String> TABLES = List.of("guardians_guardian", "guardians_guardian_code_counter",
            "guardians_guardian_student");
    private static final String DIGEST = "v1:" + "a".repeat(64);

    private interface SqlAction {
        void run() throws SQLException;
    }

    /** The rows a link hangs from, in one institution. */
    private record Base(UUID institution, UUID student, UUID guardianA, UUID guardianB) {
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

    /** A commit the deferred constraint refuses: the transaction is over, so there is no savepoint to go back to. */
    private static SQLException failedCommit(Connection connection) {
        try {
            connection.commit();
        } catch (SQLException e) {
            return e;
        }
        throw new AssertionError("the database accepted a commit it had to refuse");
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

    private static UUID student(Connection c, UUID institution) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, """
                insert into students_student (institution_id, id, student_code, first_name, last_name, birth_date, sex)
                values (?, ?, ?, 'Luis', 'Perez', date '2012-05-20', 'F')
                """, institution, id, "E-" + id.toString().substring(0, 8));
        return id;
    }

    private static Base base(Connection c, UUID institution) throws SQLException {
        return new Base(institution, student(c, institution), guardian(c, institution, "P-000001"),
                guardian(c, institution, "P-000002"));
    }

    /** Inserts the link open (a link is always born open) and, when {@code validTo} is given, closes it at once. */
    private static UUID link(Connection c, Base base, UUID guardian, String percent, boolean primary, boolean billing,
            String validTo) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, """
                insert into guardians_guardian_student (institution_id, id, guardian_id, student_id, relationship,
                    financial_responsibility_percent, is_primary_payer, is_billing_recipient, valid_from, created_by)
                values (?, ?, ?, ?, 'MOTHER', cast(? as numeric), ?, ?, date '2026-03-01', ?)
                """, base.institution(), id, guardian, base.student(), percent, primary, billing, UUID.randomUUID());
        if (validTo != null) {
            exec(c, "update guardians_guardian_student set valid_to = cast(? as date) where id = ?", validTo, id);
        }
        return id;
    }

    private static final String TODAY = "(now() at time zone 'America/Tegucigalpa')::date";

    // --- Constraints of the guardian ---

    @Test
    void theDocumentAndItsBlindIndexAreTogetherWellFormedAndUniquePerInstitutionWhilePresent() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            UUID first = guardian(owner, institution, "P-000001");
            exec(owner, "update guardians_guardian set national_id_encrypted = 'v1:x', national_id_blind_index = ? "
                    + "where id = ?", DIGEST, first);

            SQLException onlyDocument = rejected(owner, () -> exec(owner,
                    "update guardians_guardian set national_id_blind_index = null where id = ?", first));
            SQLException onlyIndex = rejected(owner, () -> exec(owner,
                    "update guardians_guardian set national_id_encrypted = null where id = ?", first));
            SQLException badIndex = rejected(owner, () -> exec(owner,
                    "update guardians_guardian set national_id_blind_index = 'v1:ABC' where id = ?", first));
            SQLException duplicate = rejected(owner, () -> exec(owner, """
                    insert into guardians_guardian (institution_id, id, guardian_code, first_name, last_name,
                        national_id_encrypted, national_id_blind_index)
                    values (?, ?, 'P-000002', 'Eva', 'Mejia', 'v1:y', ?)
                    """, institution, UUID.randomUUID(), DIGEST));

            assertThat(onlyDocument.getMessage()).contains("guardians_guardian_national_id_chk");
            assertThat(onlyIndex.getMessage()).contains("guardians_guardian_national_id_chk");
            assertThat(badIndex.getMessage()).contains("guardians_guardian_blind_index_chk");
            assertThat(duplicate.getMessage()).contains("guardians_guardian_national_id_uq");
            // Two guardians without document do not collide, and the RTN has no uniqueness at all.
            guardian(owner, institution, "P-000003");
            guardian(owner, institution, "P-000004");
            exec(owner, "update guardians_guardian set rtn_encrypted = 'v1:r'");
            owner.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(other)) {
            UUID same = guardian(owner, other, "P-000001");
            exec(owner, "update guardians_guardian set national_id_encrypted = 'v1:x', national_id_blind_index = ? "
                    + "where id = ?", DIGEST, same);
            owner.rollback();
        }
    }

    @Test
    void theCodeIsUniquePerInstitutionAndTheNamesChannelAndStatusAreChecked() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            guardian(owner, institution, "P-000001");

            SQLException code = rejected(owner, () -> guardian(owner, institution, "P-000001"));
            SQLException blankName = rejected(owner, () -> exec(owner, "insert into guardians_guardian "
                    + "(institution_id, id, guardian_code, first_name, last_name) values (?, ?, 'P-9', '  ', 'X')",
                    institution, UUID.randomUUID()));
            SQLException channel = rejected(owner, () -> exec(owner, "update guardians_guardian "
                    + "set preferred_channel = 'FAX'"));
            SQLException status = rejected(owner, () -> exec(owner, "update guardians_guardian "
                    + "set status = 'DELETED'"));

            assertThat(code.getMessage()).contains("guardians_guardian_code_uq");
            assertThat(blankName.getMessage()).contains("guardians_guardian_names_chk");
            assertThat(channel.getMessage()).contains("guardians_guardian_channel_chk");
            assertThat(status.getMessage()).contains("guardians_guardian_status_chk");
            for (String channelName : List.of("EMAIL", "SMS", "WHATSAPP", "NONE")) {
                exec(owner, "update guardians_guardian set preferred_channel = ?", channelName);
            }
            owner.rollback();
        }
    }

    // --- Constraints of the links ---

    @Test
    void thePercentageIsBetweenOneHundredthAndOneHundredWithTwoDecimals() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException zero = rejected(owner, () -> link(owner, base, base.guardianA(), "0", false, false, null));
            SQLException tiny = rejected(owner, () -> link(owner, base, base.guardianA(), "0.004", false, false, null));
            SQLException over = rejected(owner, () -> link(owner, base, base.guardianA(), "100.01", false, false, null));
            SQLException negative = rejected(owner, () -> link(owner, base, base.guardianA(), "-5", false, false, null));

            for (SQLException e : List.of(zero, tiny, over, negative)) {
                assertThat(e.getMessage()).contains("guardians_guardian_student_percent_chk");
            }
            link(owner, base, base.guardianA(), "0.01", false, false, "2026-03-02");
            link(owner, base, base.guardianA(), "100", false, false, "2026-03-02");
            // The column keeps exactly two decimals: a third is rounded by the type, never stored.
            link(owner, base, base.guardianB(), "33.33", false, false, null);
            try (ResultSet rows = owner.createStatement().executeQuery(
                    "select financial_responsibility_percent::text from guardians_guardian_student "
                            + "where valid_to is null")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("33.33");
            }
            owner.rollback();
        }
    }

    @Test
    void theRelationshipHasItsClosedSetAndAClosedLinkCannotEndBeforeItStarted() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException relationship = rejected(owner, () -> exec(owner, """
                    insert into guardians_guardian_student (institution_id, id, guardian_id, student_id, relationship,
                        financial_responsibility_percent, valid_from, created_by)
                    values (?, ?, ?, ?, 'UNCLE', 100, date '2026-03-01', ?)
                    """, institution, UUID.randomUUID(), base.guardianA(), base.student(), UUID.randomUUID()));
            SQLException dates = rejected(owner, () -> link(owner, base, base.guardianA(), "100", false, false,
                    "2026-02-28"));

            assertThat(relationship.getMessage()).contains("guardians_guardian_student_relationship_chk");
            assertThat(dates.getMessage()).contains("guardians_guardian_student_dates_chk");
            // Opened and closed on the same day is a valid, one-day history.
            link(owner, base, base.guardianA(), "100", false, false, "2026-03-01");
            owner.rollback();
        }
    }

    @Test
    void thereIsOneCurrentLinkPerGuardianAndStudentButClosedOnesPileUpAsHistory() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            link(owner, base, base.guardianA(), "60", false, false, "2026-03-02");
            link(owner, base, base.guardianA(), "50", false, false, "2026-03-03");
            link(owner, base, base.guardianA(), "40", false, false, null);

            SQLException second = rejected(owner, () -> link(owner, base, base.guardianA(), "30", false, false, null));

            assertThat(second.getMessage()).contains("guardians_guardian_student_current_uq");
            // The other guardian of the same student is unaffected.
            link(owner, base, base.guardianB(), "60", false, false, null);
            owner.rollback();
        }
    }

    @Test
    void atMostOnePrimaryPayerAndOneBillingRecipientAmongTheCurrentLinksOfAStudent() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            link(owner, base, base.guardianA(), "60", true, true, null);

            SQLException primary = rejected(owner, () -> link(owner, base, base.guardianB(), "40", true, false, null));
            SQLException billing = rejected(owner, () -> link(owner, base, base.guardianB(), "40", false, true, null));

            assertThat(primary.getMessage()).contains("guardians_guardian_student_primary_uq");
            assertThat(billing.getMessage()).contains("guardians_guardian_student_billing_uq");
            link(owner, base, base.guardianB(), "40", false, false, null);
            // A closed link no longer holds the flag: a former primary payer does not block a new one.
            UUID otherStudent = student(owner, institution);
            Base second = new Base(institution, otherStudent, base.guardianA(), base.guardianB());
            link(owner, second, second.guardianA(), "50", true, true, "2026-03-05");
            link(owner, second, second.guardianB(), "50", true, true, null);
            owner.rollback();
        }
    }

    @Test
    void aLinkNeverCrossesInstitutionsBecauseItsKeysAreComposite() throws SQLException {
        UUID institution = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID foreignGuardian;
        UUID foreignStudent;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(other)) {
            foreignGuardian = guardian(owner, other, "P-000001");
            foreignStudent = student(owner, other);
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException guardian = rejected(owner, () -> link(owner, base, foreignGuardian, "100", false, false, null));
            SQLException student = rejected(owner, () -> exec(owner, """
                    insert into guardians_guardian_student (institution_id, id, guardian_id, student_id, relationship,
                        financial_responsibility_percent, valid_from, created_by)
                    values (?, ?, ?, ?, 'MOTHER', 100, date '2026-03-01', ?)
                    """, institution, UUID.randomUUID(), base.guardianA(), foreignStudent, UUID.randomUUID()));

            assertThat(guardian.getMessage()).contains("guardians_guardian_student_guardian_fk");
            assertThat(student.getMessage()).contains("guardians_guardian_student_student_fk");
            owner.rollback();
        }
    }

    // --- A closed link is history ---

    @Test
    void aLinkCanBeClosedOnceAndNothingElseOfItEverChangesNotEvenForTheOwner() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            UUID id = link(owner, base, base.guardianA(), "60", false, false, null);

            SQLException percent = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_student set financial_responsibility_percent = 70 where id = ?", id));
            SQLException flag = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_student set is_primary_payer = true where id = ?", id));
            SQLException student = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_student set student_id = ? where id = ?", UUID.randomUUID(), id));
            assertThat(percent.getSQLState()).isEqualTo("23000");
            assertThat(flag.getSQLState()).isEqualTo("23000");
            assertThat(student.getSQLState()).isIn("23000", "23503");

            exec(owner, "update guardians_guardian_student set valid_to = date '2026-03-10' where id = ?", id);

            SQLException reopen = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_student set valid_to = null where id = ?", id));
            SQLException again = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_student set valid_to = date '2026-03-11' where id = ?", id));
            assertThat(reopen.getSQLState()).as("a closed link is never reopened").isEqualTo("23000");
            assertThat(again.getSQLState()).as("a closed link is closed once").isEqualTo("23000");
            owner.rollback();
        }
    }

    // --- INV-32 in the database, B1 ---

    @Test
    void theCurrentLinksOfAStudentMustAddUpToExactlyOneHundredWhenTheTransactionCommits() throws SQLException {
        for (String[] split : new String[][] {{"60", "39"}, {"100", "40"}, {"99.99", "0.02"}}) {
            UUID institution = UUID.randomUUID();
            Connection owner = StaffRoleFixtures.ownerTransaction(institution);
            try {
                Base base = base(owner, institution);
                link(owner, base, base.guardianA(), split[0], false, false, null);
                // Inside the transaction the set may be anything: only the commit checks it.
                link(owner, base, base.guardianB(), split[1], false, false, null);

                SQLException commit = failedCommit(owner);

                assertThat(commit.getSQLState()).as(split[0] + " + " + split[1]).isEqualTo("23514");
                assertThat(commit.getMessage()).contains("exactly 100 percent");
            } finally {
                owner.close();
            }
        }
    }

    @Test
    void aSetThatAddsUpToOneHundredCommitsAndReplacingItInOneTransactionIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        Base base;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            base = base(owner, institution);
            link(owner, base, base.guardianA(), "60.00", true, true, null);
            link(owner, base, base.guardianB(), "40.00", false, false, null);
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            // Close every link (zero current links in between) and open a complete new set, in one transaction.
            exec(owner, "update guardians_guardian_student set valid_to = " + TODAY + " where student_id = ? "
                    + "and valid_to is null", base.student());
            link(owner, base, base.guardianA(), "33.33", false, false, null);
            link(owner, base, base.guardianB(), "66.67", false, false, null);
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            // Closing only one of the two leaves 66.67 current: refused at commit.
            exec(owner, "update guardians_guardian_student set valid_to = " + TODAY + " where student_id = ? "
                    + "and guardian_id = ? and valid_to is null", base.student(), base.guardianA());
            SQLException commit = failedCommit(owner);
            assertThat(commit.getSQLState()).isEqualTo("23514");
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution);
                ResultSet rows = owner.createStatement().executeQuery(
                        "select count(*) filter (where valid_to is null), count(*) from guardians_guardian_student "
                                + "where student_id = '" + base.student() + "'")) {
            rows.next();
            assertThat(rows.getInt(1)).isEqualTo(2);
            assertThat(rows.getInt(2)).isEqualTo(4);
        }
    }

    @Test
    void aStudentWithoutCurrentLinksCommitsAndClosingEveryLinkIsAccepted() throws SQLException {
        UUID institution = UUID.randomUUID();
        Base base;
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            base = base(owner, institution);
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            link(owner, base, base.guardianA(), "100", false, false, null);
            owner.commit();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            exec(owner, "update guardians_guardian_student set valid_to = " + TODAY + " where student_id = ?",
                    base.student());
            owner.commit();
        }
    }

    @Test
    void aLinkIsInsertedOpenAndIsClosedOnlyOnADayThatIsNotInTheFutureInHonduras() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);

            SQLException closedAtBirth = rejected(owner, () -> exec(owner, """
                    insert into guardians_guardian_student (institution_id, id, guardian_id, student_id, relationship,
                        financial_responsibility_percent, valid_from, valid_to, created_by)
                    values (?, ?, ?, ?, 'MOTHER', 100, date '2026-03-01', date '2026-03-02', ?)
                    """, institution, UUID.randomUUID(), base.guardianA(), base.student(), UUID.randomUUID()));
            assertThat(closedAtBirth.getSQLState()).isEqualTo("23000");
            assertThat(closedAtBirth.getMessage()).contains("inserted open");

            UUID tomorrowLink = link(owner, base, base.guardianA(), "100", false, false, null);
            SQLException future = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_student set valid_to = " + TODAY + " + 1 where id = ?", tomorrowLink));
            SQLException beforeStart = rejected(owner, () -> exec(owner,
                    "update guardians_guardian_student set valid_to = date '2026-02-28' where id = ?", tomorrowLink));
            assertThat(future.getSQLState()).isEqualTo("23000");
            assertThat(future.getMessage()).contains("future day");
            assertThat(beforeStart.getMessage()).contains("guardians_guardian_student_dates_chk");

            exec(owner, "update guardians_guardian_student set valid_to = " + TODAY + " where id = ?", tomorrowLink);
            owner.rollback();
        }
    }

    // --- Row-level security ---

    @Test
    void eachInstitutionSeesOnlyItsOwnRowsOfEveryTable() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        seed(mine);
        seed(theirs);

        for (String table : TABLES) {
            assertThat(visibleInstitutions(table, mine)).as("%s as mine", table).containsOnly(mine).isNotEmpty();
            assertThat(visibleInstitutions(table, theirs)).as("%s as theirs", table).containsOnly(theirs).isNotEmpty();
            assertThat(visibleInstitutions(table, null)).as("%s with no context", table).isEmpty();
            assertThat(visibleInstitutions(table, UUID.randomUUID())).as("%s as a stranger", table).isEmpty();
        }
    }

    @Test
    void theApplicationRoleCannotWriteARowOfAnotherInstitutionNorTouchOneThroughAnUpdate() throws SQLException {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        Base theirBase = seed(theirs);
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, mine);

            SQLException foreignGuardian = rejected(admin, () -> guardian(admin, theirs, "P-000009"));
            SQLException foreignLink = rejected(admin, () -> link(admin, theirBase, theirBase.guardianB(), "10", false,
                    false, null));
            assertThat(foreignGuardian.getSQLState()).as("row-level security refuses the write").isEqualTo("42501");
            assertThat(foreignLink.getSQLState()).isEqualTo("42501");
            try (Statement s = admin.createStatement()) {
                assertThat(s.executeUpdate("update guardians_guardian set updated_at = clock_timestamp()")).isZero();
                assertThat(s.executeUpdate("update guardians_guardian_student set valid_to = date '2026-03-10'"))
                        .isZero();
            }
            admin.rollback();
        }
        try (Connection owner = StaffRoleFixtures.ownerTransaction(theirs);
                ResultSet rows = owner.createStatement()
                        .executeQuery("select count(*) from guardians_guardian_student where valid_to is null")) {
            rows.next();
            assertThat(rows.getLong(1)).as("the link of the other institution stayed current").isEqualTo(1);
            owner.rollback();
        }
    }

    // --- Privileges ---

    @Test
    void nobodyDeletesTheApplicationOnlyClosesLinksAndThePortalSeesNothing() throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        for (String table : TABLES) {
            try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
                admin.setAutoCommit(false);
                StaffRoleFixtures.fixInstitution(admin, institution);
                SQLException delete = rejected(admin, () -> admin.createStatement().execute("delete from " + table));
                assertThat(delete.getSQLState()).as("DELETE on %s", table).isEqualTo("42501");
                SQLException truncate = rejected(admin, () -> admin.createStatement().execute("truncate " + table));
                assertThat(truncate.getSQLState()).as("TRUNCATE on %s", table).isEqualTo("42501");
                try (ResultSet rows = admin.createStatement().executeQuery("select count(*) from " + table)) {
                    rows.next();
                    assertThat(rows.getLong(1)).as("SELECT on %s", table).isGreaterThanOrEqualTo(1);
                }
                admin.rollback();
            }
            try (Connection portal = SharedPostgresContainer.connectionAs("confia_portal_app")) {
                portal.setAutoCommit(false);
                SQLException select = rejected(portal,
                        () -> portal.createStatement().executeQuery("select 1 from " + table).close());
                assertThat(select.getSQLState()).as("SELECT by the portal role on %s", table).isEqualTo("42501");
                portal.rollback();
            }
        }
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(admin, institution);
            for (String column : List.of("financial_responsibility_percent = 1", "relationship = 'FATHER'",
                    "is_primary_payer = true", "guardian_id = guardian_id", "created_by = created_by")) {
                SQLException update = rejected(admin, () -> admin.createStatement()
                        .execute("update guardians_guardian_student set " + column));
                assertThat(update.getSQLState()).as("UPDATE of %s", column).isEqualTo("42501");
            }
            admin.createStatement().execute("update guardians_guardian_student set valid_to = date '2026-03-10' "
                    + "where valid_to is null");
            admin.createStatement().execute("update guardians_guardian set updated_at = clock_timestamp()");
            admin.createStatement().execute("update guardians_guardian_code_counter set last_value = last_value + 1");
            admin.rollback();
        }
    }

    @Test
    void theReadOnlyRoleReadsTheLinksAndTheNonSensitiveColumnsOfTheGuardianAndNothingElse() throws SQLException {
        UUID institution = UUID.randomUUID();
        seed(institution);
        try (Connection readonly = SharedPostgresContainer.connectionAs("confia_readonly")) {
            readonly.setAutoCommit(false);
            StaffRoleFixtures.fixInstitution(readonly, institution);
            try (ResultSet rows = readonly.createStatement().executeQuery("select * from guardians_guardian_student")) {
                assertThat(rows.next()).isTrue();
            }
            try (ResultSet rows = readonly.createStatement().executeQuery(
                    "select id, guardian_code, first_name, last_name, preferred_channel, status "
                            + "from guardians_guardian")) {
                assertThat(rows.next()).isTrue();
            }
            for (String column : List.of("national_id_encrypted", "national_id_blind_index", "rtn_encrypted", "email",
                    "phone", "address")) {
                SQLException secret = rejected(readonly, () -> readonly.createStatement()
                        .executeQuery("select " + column + " from guardians_guardian").close());
                assertThat(secret.getSQLState()).as("SELECT of %s by the read-only role", column).isEqualTo("42501");
            }
            SQLException star = rejected(readonly, () -> readonly.createStatement()
                    .executeQuery("select * from guardians_guardian").close());
            SQLException write = rejected(readonly, () -> readonly.createStatement()
                    .execute("update guardians_guardian_student set valid_to = date '2026-03-10'"));
            assertThat(star.getSQLState()).isEqualTo("42501");
            assertThat(write.getSQLState()).isEqualTo("42501");
            readonly.rollback();
        }
    }

    // --- Seeding and reading ---

    /** Two guardians, a student and one current link, committed under the institution given. */
    private static Base seed(UUID institution) throws SQLException {
        try (Connection owner = StaffRoleFixtures.ownerTransaction(institution)) {
            Base base = base(owner, institution);
            link(owner, base, base.guardianA(), "100", true, true, null);
            exec(owner, "insert into guardians_guardian_code_counter (institution_id, last_value) values (?, 2)",
                    institution);
            owner.commit();
            return base;
        }
    }

    /** Every institution the table shows to {@code confia_admin_app} under the given context. */
    private static List<UUID> visibleInstitutions(String table, UUID context) throws SQLException {
        List<UUID> seen = new ArrayList<>();
        try (Connection admin = SharedPostgresContainer.connectionAs("confia_admin_app")) {
            admin.setAutoCommit(false);
            if (context != null) {
                StaffRoleFixtures.fixInstitution(admin, context);
            }
            try (Statement statement = admin.createStatement();
                    ResultSet rows = statement.executeQuery("select institution_id from " + table)) {
                while (rows.next()) {
                    seen.add(rows.getObject(1, UUID.class));
                }
            }
            admin.rollback();
        }
        return seen;
    }
}

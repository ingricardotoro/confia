package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditChainVerification;
import com.confia.shared.audit.AuditChainVerifier;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.audit.CanonicalAuditRowSerializer;
import com.confia.shared.audit.DefaultAuditChainVerifier;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.support.StaffRoleFixtures;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Exit criterion 2 of phase 0, proved end to end on the real administrative process
 * (rbac-permission-matrix-and-audit-integration, task 7; design.md D19; scenarios AZ65 to AZ68, AZ70 and
 * AZ22): started through the production launcher without any test policy or double, against a real
 * PostgreSQL with the least-privilege role of the application. Every account signs in with its password
 * and a code the test computes from the secret the enrollment endpoint answered, so the token each one
 * holds is the one a person gets, with the methods {@code pwd} and {@code otp} and a live session.
 *
 * <p>Every test has a process and an institution of its own (a login belongs to one institution), so
 * the counters of the cap, the trail and the accounts are its own and the class runs in random order.
 * The process has no counter on the use case and no manual clock (D19): "zero invocations" is shown by
 * what a denial answers (a problem without a {@code roles} member) and by the row the decider wrote;
 * the counted version and the controls that need a manual clock or a broken writer (AZ69) are the
 * harness of {@code AuthorizationEdgeIT}, which imports the same configuration as the process.
 */
@TestMethodOrder(MethodOrderer.Random.class)
class ExitCriterion2EndToEndIT extends LoginEndpointSupport {

    private static final String ROLES = "/api/v1/identity/roles";
    private static final String ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
    private static final String VERIFICATIONS = "/api/v1/auth/mfa/verifications";
    private static final String DENIED = "authorization.access.denied";
    private static final String SUMMARY = "authorization.access.denied_summary";

    private final InstitutionId institution = new InstitutionId(UUID.randomUUID());
    private LoginProcess process;

    /** A staff member who signed in: the account and the access token of the session the login opened. */
    private record Member(StaffAccountId account, String token) {
    }

    @BeforeEach
    void startTheProcess() {
        process = LoginProcess.startWithoutExemptions(PEPPER_BYTES, institution, TEST_PASSWORD,
                Map.of());
    }

    @AfterEach
    void stopTheProcess() {
        process.close();
    }

    @Override
    InstitutionId institution() {
        return institution;
    }

    @Override
    SecondFactorExemptions exemptions() {
        return process.exemptions();
    }

    // --- AZ65: without the permission, 403, no invocation and exactly one row ---

    @Test
    void aCashierWhoSignedInWithPasswordAndTotpReceives403AndOneDeniedRowWithTheTraceId()
            throws SQLException {
        Member cashier = signedInAs(StaffRole.CASHIER);

        HttpResponse<String> response = get(cashier.token());

        JsonNode problem = assertProblem(response, 403, "forbidden");
        assertThat(problem.has("roles")).as("the controller did not answer").isFalse();
        List<AuditRowSnapshot> denied = deniedRows();
        assertThat(denied).hasSize(1);
        AuditRowSnapshot row = denied.get(0);
        assertThat(row.action()).isEqualTo(DENIED);
        assertThat(row.entityId()).isEqualTo("identity:read");
        assertThat(row.outcome()).isEqualTo("denied");
        assertThat(row.actorId()).isEqualTo(cashier.account().value());
        assertThat(row.requestId().toString()).isEqualTo(problem.get("traceId").asString());
    }

    // --- AZ66: with the permission, 200 and no denial row ---

    @Test
    void anAuditorWhoSignedInReceives200AndNoDenialRow() throws SQLException {
        Member auditor = signedInAs(StaffRole.AUDITOR);

        HttpResponse<String> response = get(auditor.token());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).get("roles")).hasSize(StaffRole.values().length);
        assertThat(deniedRows()).isEmpty();
    }

    // --- AZ67: the chain of the institution still verifies ---

    @Test
    void theChainOfTheInstitutionIsIntactAfterTheDenialAndTheGrantedRead() throws SQLException {
        Member cashier = signedInAs(StaffRole.CASHIER);
        Member auditor = signedInAs(StaffRole.AUDITOR);
        assertProblem(get(cashier.token()), 403, "forbidden");
        assertThat(get(auditor.token()).statusCode()).isEqualTo(200);
        long rows = auditRows().size();

        AuditChainVerifier verifier = new DefaultAuditChainVerifier(transactionRunner(),
                new JooqAuditLogReader(dsl), new CanonicalAuditRowSerializer());

        assertThat(deniedRows()).hasSize(1);
        assertThat(verifier.verifyChainOf(institution))
                .asInstanceOf(InstanceOfAssertFactories.type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(rows));
    }

    // --- AZ68: sentinels of the query and of foreign headers reach neither the trail nor the logs ---

    @Test
    void sentinelsOfTheQueryAndOfForeignHeadersReachNeitherTheTrailNorTheLogs() throws SQLException {
        Member cashier = signedInAs(StaffRole.CASHIER);
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level level = root.getLevel();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(Level.DEBUG);
        LoggerFactory.getLogger("com.confia.criterion2.probe").warn("probe line of the capture");
        HttpResponse<String> response;
        try {
            response = process.send("GET", ROLES + "?q=SENTINEL-QUERY-1&role=SENTINEL-ROLE-2", null,
                    Map.of("Authorization", "Bearer " + cashier.token(),
                            "X-Sentinel", "SENTINEL-HEADER-3",
                            "X-Permission", "SENTINEL-PERMISSION-4"));
        } finally {
            root.setLevel(level);
            root.detachAppender(logged);
        }

        assertProblem(response, 403, "forbidden");
        assertThat(deniedRows()).hasSize(1);
        String[] sentinels = {"SENTINEL-QUERY-1", "SENTINEL-ROLE-2", "SENTINEL-HEADER-3",
                "SENTINEL-PERMISSION-4", cashier.token()};
        String trail = auditRows().stream().map(AuditRowSnapshot::toString)
                .collect(Collectors.joining("\n"));
        assertThat(trail).as("the trail is not empty, so the absence means something")
                .contains(DENIED).doesNotContain(sentinels);
        String ours = logged.list.stream()
                .filter(event -> event.getLoggerName().startsWith("com.confia"))
                .map(event -> event.getFormattedMessage() + event.getKeyValuePairs()
                        + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy()
                                .getMessage()))
                .collect(Collectors.joining("\n"));
        assertThat(logged.list).as("the appender is attached: it holds the probe line")
                .anyMatch(event -> event.getFormattedMessage().equals("probe line of the capture"));
        assertThat(ours).doesNotContain(sentinels);
        assertThat(response.body()).doesNotContain("SENTINEL-QUERY-1", "SENTINEL-ROLE-2",
                "SENTINEL-HEADER-3", "SENTINEL-PERMISSION-4", cashier.token());
    }

    // --- AZ70: the result is the same whatever another account of the process did before ---

    @Test
    void theResultIsTheSameForAnAccountAfterAnotherOneSpentItsCap() throws SQLException {
        Member spender = signedInAs(StaffRole.CASHIER);
        for (int i = 0; i < 12; i++) {
            assertProblem(get(spender.token()), 403, "forbidden");
        }
        long spent = deniedRows().stream().filter(row -> DENIED.equals(row.action())).count();
        assertThat(spent).as("the cap of the first account is spent: fewer rows than denials")
                .isEqualTo(10);
        long summariesBefore = deniedRows().stream().filter(row -> SUMMARY.equals(row.action())).count();

        Member fresh = signedInAs(StaffRole.CASHIER);
        HttpResponse<String> response = get(fresh.token());

        JsonNode problem = assertProblem(response, 403, "forbidden");
        List<AuditRowSnapshot> ofTheFreshAccount = deniedRows().stream()
                .filter(row -> fresh.account().value().equals(row.actorId())).toList();
        assertThat(ofTheFreshAccount).hasSize(1);
        assertThat(ofTheFreshAccount.get(0).action()).isEqualTo(DENIED);
        assertThat(ofTheFreshAccount.get(0).requestId().toString())
                .isEqualTo(problem.get("traceId").asString());
        assertThat(deniedRows().stream().filter(row -> SUMMARY.equals(row.action())).count())
                .isEqualTo(summariesBefore);
    }

    // --- AZ22: a change of role takes effect on the next request with the same token ---

    @Test
    void aChangeFromAuditorToCashierDeniesTheNextRequestWithTheSameToken() throws SQLException {
        Member member = signedInAs(StaffRole.AUDITOR);
        assertThat(get(member.token()).statusCode()).isEqualTo(200);
        assertThat(deniedRows()).isEmpty();

        changeRole(member.account(), StaffRole.CASHIER);
        HttpResponse<String> after = get(member.token());

        JsonNode problem = assertProblem(after, 403, "forbidden");
        assertThat(problem.has("roles")).isFalse();
        assertThat(deniedRows()).hasSize(1);
    }

    // --- Seeding and requests ---

    /** A new account with the role, signed in with its password and a TOTP code of its own secret. */
    private Member signedInAs(StaffRole role) throws SQLException {
        String email = "criterion2." + UUID.randomUUID() + "@colegio.edu.hn";
        StaffAccountId account = seedAccount(email, false, false);
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution.value())) {
            StaffRoleFixtures.assign(connection, institution.value(), account.value(), role.code());
            connection.commit();
        }
        HttpResponse<String> login = process.login(LoginProcess.freshAddress(), email, PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode body = json(login);
        assertThat(body.get("status").asString()).isEqualTo("second-factor-enrollment-required");
        String restricted = body.get("secondFactorToken").asString();
        HttpResponse<String> enrolled = process.postTo(ENROLLMENTS, restricted, null,
                "X-Forwarded-For", LoginProcess.freshAddress());
        assertThat(enrolled.statusCode()).isEqualTo(201);
        String code = TotpCodes.currentCode(json(enrolled).get("secret").asString());
        HttpResponse<String> verified = process.postTo(VERIFICATIONS, restricted,
                "{\"code\":\"" + code + "\"}", "X-Forwarded-For", LoginProcess.freshAddress());
        assertThat(verified.statusCode()).as("the first verification of the fixture").isEqualTo(200);
        JsonNode session = json(verified);
        assertThat(session.get("status").asString()).isEqualTo("authenticated");
        return new Member(account, session.get("accessToken").asString());
    }

    private void changeRole(StaffAccountId account, StaffRole role) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution.value());
                PreparedStatement statement = connection.prepareStatement("""
                        update identity_staff_account_role set role_code = ?
                         where institution_id = ? and account_id = ?
                        """)) {
            statement.setString(1, role.code());
            statement.setObject(2, institution.value());
            statement.setObject(3, account.value());
            assertThat(statement.executeUpdate()).isEqualTo(1);
            connection.commit();
        }
    }

    /** The rows of this institution whose action is a denial by permission or its summary. */
    private List<AuditRowSnapshot> deniedRows() {
        return auditRows().stream().filter(row -> row.action().startsWith(DENIED)).toList();
    }

    private HttpResponse<String> get(String token) {
        return process.send("GET", ROLES, null, Map.of("Authorization", "Bearer " + token));
    }
}

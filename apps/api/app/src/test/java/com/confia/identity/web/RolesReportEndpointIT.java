package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.token.AccessToken;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.shared.web.harness.HarnessProcess;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import com.confia.support.StaffRoleFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/identity/roles} on the real administrative process, started through the production
 * launcher against a real PostgreSQL with the least-privilege role of the application
 * (rbac-permission-matrix-and-audit-integration design.md D16; scenarios I293 to I299 and the two
 * scenarios of the role assigned on the server). Every token is made by the production issuer with a key
 * pair generated for the run; accounts and roles are seeded as the owner of the schema, the only role
 * that can write an assignment before change 8b; the sessions are seeded with SQL so each one is live.
 * Every test has an institution of its own.
 *
 * <p>The process has no counter on the use case, so "zero invocations" of the denied requests is shown
 * by what a denial answers (a problem and no {@code roles} member) and by the audit row the decider
 * wrote; the counted version is the harness of {@code AuthorizationEdgeIT}.
 */
class RolesReportEndpointIT extends CommittingPostgresIntegrationTest {

    private static final String ROUTE = "/api/v1/identity/roles";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(
                AdminProcessLauncher.signingKid(), keys.getPublic(), keys.getPrivate())));
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(),
                "confia_admin_app", TEST_PASSWORD, keys);
        port = ((WebServerApplicationContext) process).getWebServer().getPort();
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    /** One seeded staff member: the account, its live session and the token of that session. */
    private record Member(UUID account, UUID institution, UUID session, String token) {
    }

    // --- I293, I294, I296: the report ---

    @Test
    void anAuditorReceivesTheReportWithNoStoreAndTheExactShape() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());

        HttpResponse<String> response = get(auditor.token());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        JsonNode body = HarnessProcess.json(response);
        assertThat(body.propertyNames()).containsExactly("roles");
        for (JsonNode role : body.get("roles")) {
            assertThat(role.propertyNames()).containsExactly("code", "permissions");
        }
    }

    @Test
    void theBodyHoldsTheSixRolesInCatalogOrderWithThePermissionsOfTheMatrixSortedByCode()
            throws SQLException {
        JsonNode roles = HarnessProcess.json(get(member(StaffRole.AUDITOR.code()).token()))
                .get("roles");

        assertThat(roles).hasSize(StaffRole.values().length);
        for (int i = 0; i < StaffRole.values().length; i++) {
            StaffRole role = StaffRole.values()[i];
            List<String> expected = role.permissions().stream().map(Permission::code).sorted()
                    .toList();
            List<String> actual = new ArrayList<>();
            roles.get(i).get("permissions").forEach(node -> actual.add(node.asString()));

            assertThat(roles.get(i).get("code").asString()).isEqualTo(role.code());
            assertThat(actual).as("permissions of %s", role.code()).isEqualTo(expected);
        }
    }

    @Test
    void theCashierCarriesTheRequestToVoidAndNotTheVoid() throws SQLException {
        JsonNode roles = HarnessProcess.json(get(member(StaffRole.AUDITOR.code()).token()))
                .get("roles");

        List<String> cashier = new ArrayList<>();
        roles.get(2).get("permissions").forEach(node -> cashier.add(node.asString()));
        assertThat(roles.get(2).get("code").asString()).isEqualTo("cashier");
        assertThat(cashier).contains("invoicing:request-void").doesNotContain("invoicing:void");
    }

    @Test
    void theSuperAdminAndTheAdministratorAreAnsweredToo() throws SQLException {
        assertThat(get(member(StaffRole.SUPER_ADMIN.code()).token()).statusCode()).isEqualTo(200);
        assertThat(get(member(StaffRole.ADMINISTRATOR.code()).token()).statusCode()).isEqualTo(200);
    }

    // --- I295: nothing personal ---

    @Test
    void noEmailNameOrIdentifierOfTheAccountOrTheInstitutionReachesTheResponse() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());

        HttpResponse<String> response = get(auditor.token());

        assertThat(response.statusCode()).isEqualTo(200);
        String everything = response.body() + " " + response.headers().map();
        assertThat(everything).doesNotContain("@").doesNotContain("colegio.edu.hn")
                .doesNotContain(auditor.account().toString())
                .doesNotContain(auditor.institution().toString())
                .doesNotContain(auditor.session().toString())
                .doesNotContainIgnoringCase("email").doesNotContainIgnoringCase("password")
                .doesNotContain("Authorization");
    }

    // --- I296: deterministic ---

    @Test
    void twoIdenticalRequestsReceiveTheSameBytes() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());

        assertThat(get(auditor.token()).body()).isEqualTo(get(auditor.token()).body());
    }

    // --- I297: the roles that cannot read identity ---

    @Test
    void theCashierAccountingAndTheAcademicCoordinatorReceive403AndOneDenialRowEach()
            throws SQLException {
        for (StaffRole role : List.of(StaffRole.CASHIER, StaffRole.ACCOUNTING,
                StaffRole.ACADEMIC_COORDINATOR)) {
            Member member = member(role.code());

            HttpResponse<String> response = get(member.token());

            JsonNode problem = assertProblem(response, 403, "forbidden");
            assertThat(problem.has("roles")).as("the use case did not answer for %s", role).isFalse();
            assertThat(response.body()).doesNotContain("invoicing:request-void");
            List<String[]> rows = auditRowsOf(member.institution());
            assertThat(rows).as("one denial for %s", role).hasSize(1);
            assertThat(rows.get(0)[0]).isEqualTo("authorization.access.denied");
            assertThat(rows.get(0)[1]).isEqualTo("identity:read");
        }
    }

    @Test
    void aGrantedAccessLeavesNoAuditRow() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());

        assertThat(get(auditor.token()).statusCode()).isEqualTo(200);

        assertThat(auditRowsOf(auditor.institution())).isEmpty();
    }

    // --- I298: no idempotency ---

    @Test
    void theReportNeedsNoIdempotencyKeyAndLeavesNoMarker() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());
        long before = idempotencyMarkersOf(auditor.institution());

        HttpResponse<String> response = get(auditor.token());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(idempotencyMarkersOf(auditor.institution())).isEqualTo(before).isZero();
    }

    // --- The role assigned on the server decides; what the client claims does not ---

    @Test
    void aCashierWhoClaimsTheAuditorsPermissionInTheHeaderAndTheQueryIsStillDenied()
            throws SQLException {
        Member cashier = member(StaffRole.CASHIER.code());

        HttpResponse<String> response = send("GET", ROUTE + "?claimedPermission=identity:read&role=auditor",
                cashier.token(), "X-Permission", "identity:read", "X-Role", "auditor",
                "claimedPermission", "identity:read");

        assertProblem(response, 403, "forbidden");
        assertThat(auditRowsOf(cashier.institution())).hasSize(1);
    }

    @Test
    void anAuditorIsAnsweredOnTheRoleTheServerAssignedWhateverTheRequestSays() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());

        HttpResponse<String> response = send("GET", ROUTE + "?role=cashier", auditor.token(),
                "X-Role", "cashier", "X-Permission", "none");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void anAuditorWhoseTokenNamesAnotherInstitutionHasNoRoleThereAndIsDenied() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());
        UUID other = UUID.randomUUID();
        StaffRoleFixtures.committed(other, auditor.account(), null);
        UUID family = seedLiveFamily(other, auditor.account());

        AccessToken token = new AccessTokenIssuer(jws, Clock.fixed(now(), ZoneOffset.UTC))
                .issueAccess(auditor.account(), other, family,
                        Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD));

        HttpResponse<String> response = get(token.compact());

        JsonNode problem = assertProblem(response, 403, "forbidden");
        assertThat(problem.has("roles")).as("the use case did not answer").isFalse();
        List<String[]> rows = auditRowsOf(other);
        assertThat(rows).as("one denial row in the institution of the token").hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("authorization.access.denied");
        assertThat(rows.get(0)[1]).isEqualTo("identity:read");
    }

    @Test
    void anAccountWithoutAnAssignmentIsDenied() throws SQLException {
        Member nobody = member(null);

        HttpResponse<String> response = get(nobody.token());

        JsonNode problem = assertProblem(response, 403, "forbidden");
        assertThat(problem.has("roles")).as("the use case did not answer").isFalse();
        List<String[]> rows = auditRowsOf(nobody.institution());
        assertThat(rows).as("one denial row for the account without a role").hasSize(1);
        assertThat(rows.get(0)[0]).isEqualTo("authorization.access.denied");
        assertThat(rows.get(0)[1]).isEqualTo("identity:read");
    }

    // --- A token that cannot be accepted is answered before any permission is looked at ---

    @Test
    void anExpiredTokenAnswers401TokenExpiredAndLeavesNoDenialRow() throws SQLException {
        Member auditor = member(StaffRole.AUDITOR.code());
        String expired = new AccessTokenIssuer(jws, Clock.fixed(now().minus(Duration.ofHours(3)),
                ZoneOffset.UTC)).issueAccess(auditor.account(), auditor.institution(),
                auditor.session(), Set.of(AuthenticationMethod.PASSWORD,
                        AuthenticationMethod.ONE_TIME_PASSWORD)).compact();

        HttpResponse<String> response = get(expired);

        JsonNode problem = assertProblem(response, 401, "token-expired");
        assertThat(problem.has("roles")).isFalse();
        assertThat(auditRowsOf(auditor.institution())).isEmpty();
    }

    @Test
    void aTokenSignedWithAForeignKeyUnderTheKidOfTheProcessAnswers401TokenInvalid()
            throws SQLException, NoSuchAlgorithmException {
        Member auditor = member(StaffRole.AUDITOR.code());
        KeyPair foreign = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        CompactJws foreignJws = new CompactJws(SigningKeyRing.of(SigningKey.signing(
                AdminProcessLauncher.signingKid(), foreign.getPublic(), foreign.getPrivate())));
        String forged = new AccessTokenIssuer(foreignJws, Clock.fixed(now(), ZoneOffset.UTC))
                .issueAccess(auditor.account(), auditor.institution(), auditor.session(),
                        Set.of(AuthenticationMethod.PASSWORD,
                                AuthenticationMethod.ONE_TIME_PASSWORD)).compact();

        HttpResponse<String> response = get(forged);

        JsonNode problem = assertProblem(response, 401, "token-invalid");
        assertThat(problem.has("roles")).isFalse();
        assertThat(auditRowsOf(auditor.institution())).isEmpty();
    }

    // --- Authentication ---

    @Test
    void noCredentialAnswers401AuthenticationRequired() {
        HttpResponse<String> response = send("GET", ROUTE, null);

        assertProblem(response, 401, "authentication-required");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
    }

    // --- The token carries no role and no permission ---

    @Test
    void theTokenCarriesNoRoleNorPermissionClaimAndEachOneHasItsOwnJti() throws SQLException {
        Member first = member(StaffRole.AUDITOR.code());
        Member second = member(StaffRole.AUDITOR.code());

        JsonNode firstClaims = claimsOf(first.token());
        JsonNode secondClaims = claimsOf(second.token());

        for (JsonNode claims : List.of(firstClaims, secondClaims)) {
            assertThat(claims.has("role")).isFalse();
            assertThat(claims.has("roles")).isFalse();
            assertThat(claims.has("permissions")).isFalse();
            assertThat(claims.has("scope")).isFalse();
        }
        assertThat(firstClaims.get("jti").asString()).isNotEqualTo(secondClaims.get("jti").asString());
    }

    // --- Seeding ---

    /** A fresh account in a fresh institution with the role (none when null), a live session and its token. */
    private Member member(String roleCode) throws SQLException {
        UUID tenant = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(tenant, account, roleCode);
        UUID family = seedLiveFamily(tenant, account);
        AccessToken token = new AccessTokenIssuer(jws, Clock.fixed(now(), ZoneOffset.UTC))
                .issueAccess(account, tenant, family,
                        Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD));
        return new Member(account, tenant, family, token.compact());
    }

    private UUID seedLiveFamily(UUID tenant, UUID account) {
        UUID family = UUID.randomUUID();
        Instant created = now().minus(Duration.ofMinutes(5));
        transactionRunner().execute(
                new com.confia.shared.security.SecurityContext("", "system", tenant.toString(),
                        UUID.randomUUID().toString()),
                () -> dsl.execute("""
                        insert into identity_refresh_token_family
                            (institution_id, id, account_id, second_factor_verified,
                             user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                        values (?, ?, ?, true, ?, cast(? as timestamptz), cast(? as timestamptz),
                                cast(? as timestamptz))
                        """, tenant, family, account,
                        com.confia.identity.domain.UserAgentFingerprint.of("agente-uno").value(),
                        created.toString(), created.toString(),
                        created.plus(Duration.ofHours(12)).toString()));
        return family;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static JsonNode claimsOf(String compactToken) {
        String payload = compactToken.split("\\.")[1];
        String json = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
    }

    // --- Reading the database as the superuser of the container ---

    private static List<String[]> auditRowsOf(UUID tenant) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_id from shared_audit_log
                         where institution_id = ? order by id
                        """)) {
            statement.setObject(1, tenant);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new String[] {result.getString(1), result.getString(2)});
                }
            }
        }
        return rows;
    }

    private static long idempotencyMarkersOf(UUID tenant) throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from shared_idempotency_key where institution_id = ?")) {
            statement.setObject(1, tenant);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    // --- HTTP ---

    private static HttpResponse<String> get(String token) {
        return send("GET", ROUTE, token);
    }

    private static HttpResponse<String> send(String method, String path, String token,
            String... headers) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return execute(request.build());
    }

    private static HttpResponse<String> execute(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

package com.confia.cashbox.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The cash session routes on the real administrative process, started through the production launcher against a real
 * PostgreSQL with the least-privilege role of the application (docs/cortes/cashbox-sessions.md): the acceptance flow, the
 * permission of each role, the segregation of duties by the approver, the error codes, the permanent key of each write and the
 * isolation between institutions. Every token is made by the production issuer; institutions, accounts and amounts are fictional.
 */
class CashboxEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final String BASE = "/api/v1/cashbox/sessions";
    private static final String REASON = "Falto un billete de cien lempiras";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    @BeforeAll
    static void startTheProcess() throws NoSuchAlgorithmException {
        keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        jws = new CompactJws(SigningKeyRing.of(SigningKey.signing(AdminProcessLauncher.signingKid(), keys.getPublic(),
                keys.getPrivate())));
        process = AdminProcessLauncher.launch(SharedPostgresContainer.jdbcUrl(), "confia_admin_app", TEST_PASSWORD,
                keys, Map.of(RATE_LIMIT + "admin-read.request-limit", "10000",
                        RATE_LIMIT + "admin-write.request-limit", "10000",
                        RATE_LIMIT + "admin-read-account.request-limit", "10000",
                        RATE_LIMIT + "admin-write-account.request-limit", "10000"));
        port = ((WebServerApplicationContext) process).getWebServer().getPort();
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    private record Member(UUID account, UUID institution, String token) {
    }

    private static String money(String amount) {
        return "{\"amount\":\"" + amount + "\",\"currency\":\"HNL\"}";
    }

    private static String openBody(String openingFloat) {
        return "{\"openingFloat\":" + money(openingFloat) + "}";
    }

    private static String movementBody(String type, String amount, String notes) {
        return "{\"type\":\"" + type + "\",\"amount\":" + money(amount)
                + (notes == null ? "" : ",\"notes\":\"" + notes + "\"") + "}";
    }

    private static String closeBody(String declared, String reason) {
        return "{\"declaredAmount\":" + money(declared)
                + (reason == null ? "" : ",\"differenceReason\":\"" + reason + "\"") + "}";
    }

    private static String approveBody(String note) {
        return "{\"note\":\"" + note + "\"}";
    }

    private UUID institution() throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement s = c.prepareStatement("insert into organization_institution (id, legal_name, rtn, "
                        + "address, default_currency, locale, timezone) values (?, 'Colegio de prueba', '1', "
                        + "'Calle falsa', 'HNL', 'es-HN', 'America/Tegucigalpa')")) {
            s.setObject(1, institution);
            s.executeUpdate();
            c.commit();
        }
        return institution;
    }

    // --- The acceptance flow ---

    @Test
    void theCashierOpensMovesAndClosesWithADifferenceAndAnAdministratorApprovesIt() throws SQLException {
        UUID institution = institution();
        Member cashier = member(institution, StaffRole.CASHIER);
        Member administrator = member(institution, StaffRole.ADMINISTRATOR);

        HttpResponse<String> opened = post(BASE, cashier, openBody("500.00"), "open-1");
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        JsonNode session = HarnessProcess.json(opened);
        String id = session.get("id").asString();
        assertThat(session.get("status").asString()).isEqualTo("open");
        assertThat(session.get("cashierId").asString()).isEqualTo(cashier.account().toString());
        assertThat(session.get("openingFloat").get("amount").asString()).isEqualTo("500.00");
        assertThat(session.get("expectedAmount").get("currency").asString()).isEqualTo("HNL");
        assertThat(opened.body()).as("the key never leaves the server").doesNotContain("open-1");

        JsonNode deposit = HarnessProcess.json(post(BASE + "/" + id + "/movements", cashier,
                movementBody("deposit", "100.00", null), "move-1"));
        assertThat(deposit.get("expectedAfter").get("amount").asString()).isEqualTo("600.00");
        HttpResponse<String> withdrawal = post(BASE + "/" + id + "/movements", cashier,
                movementBody("withdrawal", "50.00", "Pago de proveedor"), "move-2");
        assertThat(withdrawal.statusCode()).isEqualTo(201);
        assertThat(HarnessProcess.json(withdrawal).get("expectedAfter").get("amount").asString()).isEqualTo("550.00");

        HttpResponse<String> current = get(BASE + "/current", cashier);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(current).get("expectedAmount").get("amount").asString()).isEqualTo("550.00");
        assertThat(HarnessProcess.json(current).get("movements")).hasSize(2);

        HttpResponse<String> closed = post(BASE + "/" + id + "/close", cashier, closeBody("540.00", REASON), "close-1");
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        JsonNode close = HarnessProcess.json(closed);
        assertThat(close.get("status").asString()).isEqualTo("pending_approval");
        assertThat(close.get("close").get("differenceAmount").get("amount").asString()).isEqualTo("-10.00");
        assertThat(close.get("close").get("expectedAmount").get("amount").asString()).isEqualTo("550.00");
        assertThat(close.get("close").get("declaredAmount").get("amount").asString()).isEqualTo("540.00");

        // The cashier holds no cashbox:approve; the administrator does, and is not the cashier.
        assertProblem(post(BASE + "/" + id + "/approve", cashier, approveBody("mine"), "approve-0"), 403, "forbidden");
        HttpResponse<String> approved = post(BASE + "/" + id + "/approve", administrator, approveBody("Revisado"),
                "approve-1");
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(approved).get("status").asString()).isEqualTo("closed");
        assertThat(HarnessProcess.json(approved).get("approval").get("approvedBy").asString())
                .isEqualTo(administrator.account().toString());
        assertProblem(post(BASE + "/" + id + "/approve", administrator, approveBody("otra vez"), "approve-2"), 409,
                "cash-session-not-awaiting-approval");
        assertThat(HarnessProcess.json(post(BASE + "/" + id + "/approve", administrator, approveBody("Revisado"),
                "approve-1")).get("status").asString()).as("the replay").isEqualTo("closed");

        assertProblem(get(BASE + "/current", cashier), 404, "resource-not-found");
        assertProblem(post(BASE + "/" + id + "/movements", cashier, movementBody("deposit", "1.00", null), "late"), 409,
                "cash-session-not-open");
        assertThat(HarnessProcess.json(get(BASE + "/" + id, administrator)).get("movements")).hasSize(2);
    }

    @Test
    void theSuperAdministratorCannotApproveItsOwnCloseButAnotherApproverCan() throws SQLException {
        UUID institution = institution();
        Member holder = member(institution, StaffRole.SUPER_ADMIN);
        Member administrator = member(institution, StaffRole.ADMINISTRATOR);
        String id = HarnessProcess.json(post(BASE, holder, openBody("100.00"), "open-1")).get("id").asString();
        assertThat(post(BASE + "/" + id + "/close", holder, closeBody("90.00", REASON), "close-1").statusCode())
                .isEqualTo(200);

        assertProblem(post(BASE + "/" + id + "/approve", holder, approveBody("mine"), "approve-1"), 403,
                "cash-approval-same-actor");
        assertThat(post(BASE + "/" + id + "/approve", administrator, approveBody("Revisado"), "approve-2").statusCode())
                .isEqualTo(200);
    }

    // --- The permission of each role ---

    @Test
    void eachRoleReachesOnlyWhatItsPermissionsAllow() throws SQLException {
        UUID institution = institution();
        Member cashier = member(institution, StaffRole.CASHIER);
        Member accounting = member(institution, StaffRole.ACCOUNTING);
        Member administrator = member(institution, StaffRole.ADMINISTRATOR);
        Member auditor = member(institution, StaffRole.AUDITOR);
        Member coordinator = member(institution, StaffRole.ACADEMIC_COORDINATOR);
        String id = HarnessProcess.json(post(BASE, cashier, openBody("100.00"), "open-1")).get("id").asString();

        for (Member noCash : new Member[] {accounting, administrator, auditor, coordinator}) {
            assertProblem(post(BASE, noCash, openBody("1.00"), "k"), 403, "forbidden");
            assertProblem(post(BASE + "/" + id + "/movements", noCash, movementBody("deposit", "1.00", null), "k"), 403,
                    "forbidden");
            assertProblem(post(BASE + "/" + id + "/close", noCash, closeBody("100.00", null), "k"), 403, "forbidden");
            assertProblem(get(BASE + "/current", noCash), 403, "forbidden");
        }
        for (Member cannotApprove : new Member[] {cashier, accounting, auditor, coordinator}) {
            assertProblem(post(BASE + "/" + id + "/approve", cannotApprove, approveBody("ok"), "k"), 403, "forbidden");
        }
        for (Member reader : new Member[] {cashier, accounting, administrator, auditor}) {
            assertThat(get(BASE, reader).statusCode()).as(reader.toString()).isEqualTo(200);
            assertThat(get(BASE + "/" + id, reader).statusCode()).isEqualTo(200);
        }
        assertProblem(get(BASE, coordinator), 403, "forbidden");
        assertThat(send("GET", BASE, null, null, null).statusCode()).isEqualTo(401);
        assertThat(send("POST", BASE, null, openBody("1.00"), "k").statusCode()).isEqualTo(401);
    }

    // --- The errors ---

    @Test
    void theRulesAnswerTheirProblemCodes() throws SQLException {
        UUID institution = institution();
        Member cashier = member(institution, StaffRole.CASHIER);
        Member other = member(institution, StaffRole.SUPER_ADMIN);

        assertProblem(post(BASE, cashier, openBody("100.00"), null), 400, "idempotency-key-missing");
        assertProblem(post(BASE, cashier, openBody("-1.00"), "k-neg"), 400, "validation-failed");
        assertProblem(post(BASE, cashier, openBody("1.005"), "k-dec"), 400, "validation-failed");
        assertProblem(post(BASE, cashier, "{\"openingFloat\":{\"amount\":\"1.00\",\"currency\":\"USD\"}}", "k-usd"), 400,
                "validation-failed");
        assertProblem(post(BASE, cashier, "{\"openingFloat\":1.5}", "k-num"), 400, "validation-failed");
        assertProblem(post(BASE, cashier, "{}", "k-empty"), 400, "validation-failed");
        String id = HarnessProcess.json(post(BASE, cashier, openBody("100.00"), "open-1")).get("id").asString();
        assertProblem(post(BASE, cashier, openBody("100.00"), "open-2"), 409, "cash-session-already-open");
        assertProblem(post(BASE, cashier, openBody("999.00"), "open-1"), 422, "idempotency-payload-mismatch");
        assertProblem(post(BASE, other, openBody("100.00"), "open-1"), 422, "idempotency-payload-mismatch");

        assertProblem(post(BASE + "/" + id + "/movements", cashier, movementBody("withdrawal", "100.01", null), "w-1"),
                409, "cash-expected-negative");
        assertProblem(post(BASE + "/" + id + "/movements", cashier, movementBody("deposit", "0.00", null), "d-0"), 400,
                "validation-failed");
        assertProblem(post(BASE + "/" + id + "/movements", cashier, movementBody("refund_out", "1.00", null), "r-1"),
                400, "validation-failed");
        assertProblem(post(BASE + "/" + id + "/movements", cashier, movementBody("adjustment", "-1.00", "corta"), "a-1"),
                400, "validation-failed");
        assertThat(post(BASE + "/" + id + "/movements", cashier, movementBody("adjustment", "-1.00",
                "Correccion del conteo del turno de la manana"), "a-2").statusCode()).isEqualTo(201);
        assertProblem(post(BASE + "/" + id + "/movements", other, movementBody("deposit", "1.00", null), "o-1"), 404,
                "resource-not-found");
        assertProblem(post(BASE + "/" + UUID.randomUUID() + "/movements", cashier, movementBody("deposit", "1.00", null),
                "u-1"), 404, "resource-not-found");

        assertProblem(post(BASE + "/" + id + "/close", cashier, closeBody("50.00", null), "c-1"), 400,
                "cash-difference-reason-required");
        assertProblem(post(BASE + "/" + id + "/close", cashier, closeBody("50.00", "muy corto"), "c-2"), 400,
                "validation-failed");
        assertThat(post(BASE + "/" + id + "/close", cashier, closeBody("99.00", null), "c-3").statusCode())
                .isEqualTo(200);
        assertProblem(post(BASE + "/" + id + "/close", cashier, closeBody("99.00", null), "c-4"), 409,
                "cash-session-not-open");
        assertProblem(get(BASE + "?size=500", cashier), 400, "validation-failed");
        assertProblem(get(BASE + "?status=bogus", cashier), 400, "validation-failed");
        assertProblem(get(BASE + "?sort=password", cashier), 400, "validation-failed");
    }

    // --- Isolation between institutions ---

    @Test
    void aSessionOfAnotherInstitutionLooksLikeOneThatDoesNotExist() throws SQLException {
        UUID institutionA = institution();
        UUID institutionB = institution();
        Member cashierA = member(institutionA, StaffRole.CASHIER);
        Member adminB = member(institutionB, StaffRole.ADMINISTRATOR);
        Member cashierB = member(institutionB, StaffRole.CASHIER);
        String id = HarnessProcess.json(post(BASE, cashierA, openBody("100.00"), "open-1")).get("id").asString();
        post(BASE, cashierB, openBody("5.00"), "open-b");

        assertProblem(get(BASE + "/" + id, adminB), 404, "resource-not-found");
        assertProblem(get(BASE + "/" + UUID.randomUUID(), adminB), 404, "resource-not-found");
        assertProblem(post(BASE + "/" + id + "/approve", adminB, approveBody("ok"), "approve-x"), 404,
                "resource-not-found");
        JsonNode listed = HarnessProcess.json(get(BASE, adminB));
        assertThat(listed.get("total").asLong()).isEqualTo(1);
        assertThat(listed.get("items").get(0).get("cashierId").asString()).isEqualTo(cashierB.account().toString());
    }

    // --- Seeding ---

    private Member member(UUID institution, StaffRole role) throws SQLException {
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, role.code());
        UUID family = UUID.randomUUID();
        Instant created = now().minus(Duration.ofMinutes(5));
        transactionRunner().execute(new SecurityContext("", "system", institution.toString(),
                UUID.randomUUID().toString()), () -> dsl.execute("""
                        insert into identity_refresh_token_family
                            (institution_id, id, account_id, second_factor_verified,
                             user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                        values (?, ?, ?, true, ?, cast(? as timestamptz), cast(? as timestamptz),
                                cast(? as timestamptz))
                        """, institution, family, account,
                        com.confia.identity.domain.UserAgentFingerprint.of("agente-uno").value(), created.toString(),
                        created.toString(), created.plus(Duration.ofHours(12)).toString()));
        String token = new AccessTokenIssuer(jws, Clock.fixed(now(), ZoneOffset.UTC))
                .issueAccess(account, institution, family,
                        Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD))
                .compact();
        return new Member(account, institution, token);
    }

    // --- HTTP ---

    private static HttpResponse<String> get(String path, Member member) {
        return send("GET", path, member.token(), null, null);
    }

    private static HttpResponse<String> post(String path, Member member, String body, String idempotencyKey) {
        return send("POST", path, member.token(), body, idempotencyKey);
    }

    private static HttpResponse<String> send(String method, String path, String token, String body,
            String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (!method.equals("GET")) {
            request.header("Origin", AdminProcessLauncher.ALLOWED_ORIGIN);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }
}

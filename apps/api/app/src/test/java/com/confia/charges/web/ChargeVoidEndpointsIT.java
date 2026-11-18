package com.confia.charges.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.charges.ChargesLedgerFixture;
import com.confia.charges.application.RunChargesIntegrityCheck;
import com.confia.identity.domain.StaffRole;
import com.confia.kernel.CurrencyCode;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.shared.jobs.RecurringRun;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.shared.web.harness.HarnessProcess;
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
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * The routes of cut 3b on the real administrative process, started through the production launcher against a real PostgreSQL
 * with the least-privilege role of the application (docs/cortes/charges-void-and-integrity.md): the void of a charge with its
 * permanent key, the list of the charges of a student, the reads of the integrity runs, the roles without the permission and the
 * isolation between institutions. The charges are the real ones of the accrual engine, executed in this JVM against the same
 * database; every token is made by the production issuer.
 */
class ChargeVoidEndpointsIT extends ChargesLedgerFixture {

    private static final String RATE_LIMIT = "confia.web.rate-limit.";
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

    private static String voidBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    // --- The void ---

    @Test
    void anAdministratorVoidsAChargeAndTheSameKeyAnswersTheSameVoidWhileAnotherKeyIsAConflict() throws SQLException {
        Env env = seed(2);
        accrueFebruary(env, "accrual-1");
        UUID charge = chargesByStudent(env).get(env.students().get(0));
        Member admin = member(env.institution(), StaffRole.ADMINISTRATOR.code());

        HttpResponse<String> response = post("/api/v1/charges/" + charge + "/void", admin, voidBody(REASON), "void-key-1");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode voided = HarnessProcess.json(response);
        assertThat(voided.propertyNames()).containsExactly("id", "chargeId", "status", "reversalTransactionId", "reason",
                "voidedBy", "voidedAt");
        assertThat(voided.get("chargeId").asString()).isEqualTo(charge.toString());
        assertThat(voided.get("status").asString()).isEqualTo("VOIDED");
        assertThat(voided.get("reason").asString()).isEqualTo(REASON);
        assertThat(voided.get("voidedBy").asString()).isEqualTo(admin.account().toString());
        assertThat(balanceOf(env, env.students().get(0))).isEqualByComparingTo("0.00");

        HttpResponse<String> replay = post("/api/v1/charges/" + charge + "/void", admin, voidBody(REASON), "void-key-1");
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(replay).get("id").asString()).isEqualTo(voided.get("id").asString());
        assertThat(replay.body()).as("the key never leaves the server").doesNotContain("void-key-1");
        assertProblem(post("/api/v1/charges/" + charge + "/void", admin, voidBody(REASON), "void-key-2"), 409,
                "charge-already-voided");
        assertProblem(post("/api/v1/charges/" + charge + "/void", admin,
                voidBody("Un motivo distinto del que se dio la primera vez"), "void-key-1"), 422,
                "idempotency-payload-mismatch");
        assertThat(scalar(env.institution(), "select count(*) from charges_charge_void")).isEqualTo(1);
        assertThat(scalar(env.institution(), "select count(*) from ledger_transaction where kind = 'REVERSAL'"))
                .isEqualTo(1);
    }

    @Test
    void theVoidNeedsItsKeyAReasonOfTheRightLengthAndAChargeOfTheInstitution() throws SQLException {
        Env env = seed(1);
        accrueFebruary(env, "accrual-1");
        UUID charge = chargesByStudent(env).get(env.students().get(0));
        Member admin = member(env.institution(), StaffRole.ADMINISTRATOR.code());
        String path = "/api/v1/charges/" + charge + "/void";

        assertProblem(post(path, admin, voidBody(REASON), null), 400, "idempotency-key-missing");
        assertProblem(post(path, admin, voidBody("muy corto"), "k"), 400, "validation-failed");
        assertProblem(post(path, admin, voidBody("x".repeat(501)), "k"), 400, "validation-failed");
        assertProblem(post(path, admin, "{\"reason\":\"                                  \"}", "k"), 400,
                "validation-failed");
        assertProblem(post(path, admin, "{}", "k"), 400, "validation-failed");
        assertProblem(post(path, admin, voidBody(REASON), "has space"), 400, "validation-failed");
        assertProblem(post("/api/v1/charges/" + UUID.randomUUID() + "/void", admin, voidBody(REASON), "k"), 404,
                "resource-not-found");
        assertThat(post(path, admin, voidBody("x".repeat(20)), "k-20").statusCode()).as("exactly 20 is enough")
                .isEqualTo(200);
        assertThat(scalar(env.institution(), "select count(*) from charges_charge_void")).isEqualTo(1);
    }

    @Test
    void onlyTheRolesThatHoldChargesVoidCanVoidAndEveryRoleThatReadsChargesCanList() throws SQLException {
        Env env = seed(1);
        accrueFebruary(env, "accrual-1");
        UUID student = env.students().get(0);
        UUID charge = chargesByStudent(env).get(student);
        String path = "/api/v1/charges/" + charge + "/void";

        for (StaffRole role : List.of(StaffRole.CASHIER, StaffRole.ACCOUNTING, StaffRole.AUDITOR)) {
            Member member = member(env.institution(), role.code());
            assertProblem(post(path, member, voidBody(REASON), "key-" + role.code()), 403, "forbidden");
            assertThat(get("/api/v1/charges/students/" + student + "/charges", member).statusCode())
                    .as(role.code()).isEqualTo(200);
        }
        assertThat(scalar(env.institution(), "select count(*) from charges_charge_void")).isZero();
        assertThat(send("POST", path, null, voidBody(REASON), "key-anonymous").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/charges/students/" + student + "/charges", null, null).statusCode())
                .isEqualTo(401);
        assertThat(post(path, member(env.institution(), StaffRole.SUPER_ADMIN.code()), voidBody(REASON), "key-super")
                .statusCode()).isEqualTo(200);
    }

    @Test
    void aUserOfAnotherInstitutionCannotVoidNorListNorSeeTheChargesOfThisOne() throws SQLException {
        Env mine = seed(1);
        accrueFebruary(mine, "accrual-1");
        UUID student = mine.students().get(0);
        UUID charge = chargesByStudent(mine).get(student);
        Env other = seed(0);
        Member theirs = member(other.institution(), StaffRole.ADMINISTRATOR.code());

        assertProblem(post("/api/v1/charges/" + charge + "/void", theirs, voidBody(REASON), "key-1"), 404,
                "resource-not-found");
        assertProblem(get("/api/v1/charges/students/" + student + "/charges", theirs), 404, "resource-not-found");
        assertThat(scalar(mine.institution(), "select count(*) from charges_charge_void")).isZero();
        assertThat(balanceOf(mine, student)).isEqualByComparingTo("1650.00");
    }

    // --- The list of the charges of a student ---

    @Test
    void theListShowsEveryAmountRoundedWithItsCurrencyAndTheDerivedStatusAndFilters() throws SQLException {
        Env env = seed(1);
        accrueFebruary(env, "accrual-1");
        UUID student = env.students().get(0);
        UUID charge = chargesByStudent(env).get(student);
        Member admin = member(env.institution(), StaffRole.ADMINISTRATOR.code());
        String path = "/api/v1/charges/students/" + student + "/charges";

        JsonNode page = HarnessProcess.json(get(path, admin));
        assertThat(page.propertyNames()).containsExactly("items", "total", "page", "size");
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(50);
        JsonNode item = page.get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(charge.toString());
        assertThat(item.get("conceptCode").asString()).isEqualTo("COL");
        assertThat(item.get("conceptName").asString()).isEqualTo("Colegiatura");
        assertThat(item.get("periodLabel").asString()).isEqualTo("2027-02");
        assertThat(item.get("dueOn").asString()).isEqualTo("2027-02-05");
        assertThat(item.get("origin").asString()).isEqualTo("AUTOMATIC");
        assertThat(item.get("base").get("amount").asString()).isEqualTo("1500.00");
        assertThat(item.get("base").get("currency").asString()).isEqualTo("HNL");
        assertThat(item.get("tax").get("amount").asString()).isEqualTo("150.00");
        assertThat(item.get("total").get("amount").asString()).isEqualTo("1650.00");
        assertThat(item.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(item.has("voided")).as("an active charge carries no void").isFalse();

        post("/api/v1/charges/" + charge + "/void", admin, voidBody(REASON), "void-key-1");

        JsonNode voided = HarnessProcess.json(get(path + "?status=VOIDED&sort=total,desc&page=0&size=10", admin))
                .get("items").get(0);
        assertThat(voided.get("status").asString()).isEqualTo("VOIDED");
        assertThat(voided.get("voided").get("reason").asString()).isEqualTo(REASON);
        assertThat(voided.get("voided").get("voidId").asString()).isNotBlank();
        assertThat(voided.get("voided").get("reversalTransactionId").asString()).isNotBlank();
        assertThat(HarnessProcess.json(get(path + "?status=ACTIVE", admin)).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(path + "?periodFrom=2027-02-02", admin)).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(path + "?periodFrom=2027-02-01&periodTo=2027-02-01", admin)).get("total")
                .asLong()).isEqualTo(1);
    }

    @Test
    void theListRefusesWhatIsOutOfRangeOrOutsideTheClosedSets() throws SQLException {
        Env env = seed(1);
        Member admin = member(env.institution(), StaffRole.ADMINISTRATOR.code());
        String path = "/api/v1/charges/students/" + env.students().get(0) + "/charges";

        assertProblem(get(path + "?size=500", admin), 400, "validation-failed");
        assertProblem(get(path + "?size=0", admin), 400, "validation-failed");
        assertProblem(get(path + "?page=-1", admin), 400, "validation-failed");
        assertProblem(get(path + "?status=PAID", admin), 400, "validation-failed");
        assertProblem(get(path + "?sort=password", admin), 400, "validation-failed");
        assertProblem(get(path + "?sort=total,sideways", admin), 400, "validation-failed");
        assertProblem(get(path + "?periodFrom=2027-03-01&periodTo=2027-02-01", admin), 400, "validation-failed");
        assertProblem(get(path + "?periodFrom=not-a-date", admin), 400, "validation-failed");
        assertProblem(get("/api/v1/charges/students/" + UUID.randomUUID() + "/charges", admin), 404,
                "resource-not-found");
    }

    // --- The integrity runs ---

    @Test
    void theIntegrityRunsAreReadWithTheirDiscrepanciesAndOnlyByTheInstitutionThatOwnsThem() throws SQLException {
        Env env = seed(1);
        accrueFebruary(env, "accrual-1");
        Member cashier = member(env.institution(), StaffRole.CASHIER.code());
        Env other = seed(0);
        Member theirs = member(other.institution(), StaffRole.ADMINISTRATOR.code());

        assertProblem(get("/api/v1/charges/integrity-runs/latest", cashier), 404, "resource-not-found");
        assertThat(HarnessProcess.json(get("/api/v1/charges/integrity-runs", cashier)).get("total").asLong()).isZero();

        night(env);
        JsonNode latest = HarnessProcess.json(get("/api/v1/charges/integrity-runs/latest", cashier));
        assertThat(latest.get("run").get("status").asString()).isEqualTo("OK");
        assertThat(latest.get("run").get("chargesChecked").asInt()).isEqualTo(1);
        assertThat(latest.get("run").get("postingsChecked").asInt()).isEqualTo(1);
        assertThat(latest.get("run").get("discrepancyCount").asInt()).isZero();
        assertThat(latest.get("findings")).isEmpty();

        postStrayCharge(env);
        try {
            night(env);
        } catch (RuntimeException expected) {
            assertThat(expected).hasMessageContaining("2 discrepancies");
        }
        JsonNode bad = HarnessProcess.json(get("/api/v1/charges/integrity-runs/latest", cashier));
        assertThat(bad.get("run").get("status").asString()).isEqualTo("DISCREPANCIES");
        JsonNode orphan = bad.get("findings").get(0);
        assertThat(orphan.get("kind").asString()).isEqualTo("ORPHAN_POSTING");
        assertThat(orphan.get("ledgerTransactionId").asString()).isNotBlank();
        assertThat(orphan.has("chargeId")).isFalse();
        assertThat(orphan.has("expected")).isFalse();
        JsonNode mismatch = bad.get("findings").get(1);
        assertThat(mismatch.get("kind").asString()).isEqualTo("RECEIVABLE_MISMATCH");
        assertThat(mismatch.get("expected").get("amount").asString()).isEqualTo("1650.00");
        assertThat(mismatch.get("actual").get("amount").asString()).isEqualTo("1660.00");
        assertThat(mismatch.get("actual").get("currency").asString()).isEqualTo("HNL");

        String runId = bad.get("run").get("id").asString();
        assertThat(HarnessProcess.json(get("/api/v1/charges/integrity-runs/" + runId, cashier)).get("findings"))
                .hasSize(2);
        JsonNode runs = HarnessProcess.json(get("/api/v1/charges/integrity-runs?size=1", cashier));
        assertThat(runs.get("total").asLong()).isEqualTo(2);
        assertThat(runs.get("items")).hasSize(1);
        assertThat(runs.get("items").get(0).get("id").asString()).as("most recent first").isEqualTo(runId);

        assertProblem(get("/api/v1/charges/integrity-runs/" + runId, theirs), 404, "resource-not-found");
        assertProblem(get("/api/v1/charges/integrity-runs/latest", theirs), 404, "resource-not-found");
        assertThat(HarnessProcess.json(get("/api/v1/charges/integrity-runs", theirs)).get("total").asLong()).isZero();
        assertProblem(get("/api/v1/charges/integrity-runs?size=500", cashier), 400, "validation-failed");
        assertThat(send("GET", "/api/v1/charges/integrity-runs", null, null).statusCode()).isEqualTo(401);
    }

    // --- Seeding and the night ---

    private void night(Env env) {
        Clock later = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
        integrityCheck(later).runFor(new InstitutionId(env.institution()),
                new RecurringRun(RunChargesIntegrityCheck.TASK_NAME, UUID.randomUUID(), later.instant()));
    }

    private void postStrayCharge(Env env) {
        Money money = Money.of("10.00", CurrencyCode.HNL);
        UUID sourceRef = UUID.randomUUID();
        LedgerPostingApi.Actor actor = new LedgerPostingApi.Actor(env.institution(), env.admin(), "system",
                UUID.randomUUID());
        transactionRunner().execute(new SecurityContext("", "system", env.institution().toString(),
                UUID.randomUUID().toString()), () -> ledger().postCharge(actor,
                        new LedgerPostingApi.ChargePosting(LocalDate.parse("2027-02-01"), "charges", sourceRef,
                                "charges:accrual:" + sourceRef,
                                List.of(new LedgerPostingApi.Line(env.receivable(), env.students().get(0),
                                        LedgerPostingApi.Side.DEBIT, money),
                                        new LedgerPostingApi.Line(env.revenue(), null, LedgerPostingApi.Side.CREDIT,
                                                money)))));
    }

    private Member member(UUID institution, String roleCode) throws SQLException {
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, roleCode);
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
        return send("GET", path, member.token(), null);
    }

    private static HttpResponse<String> post(String path, Member member, String body, String idempotencyKey) {
        return send("POST", path, member.token(), body, idempotencyKey);
    }

    private static HttpResponse<String> send(String method, String path, String token, String body) {
        return send(method, path, token, body, null);
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

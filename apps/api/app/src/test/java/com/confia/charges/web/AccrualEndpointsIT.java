package com.confia.charges.web;

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
 * The routes of the accrual engine on the real administrative process, started through the production launcher against a
 * real PostgreSQL with the least-privilege role of the application (docs/cortes/charges-accrual.md): the periods of a plan,
 * the preview, the request of a run with its permanent key, the reading of a run, the roles without the permission and the
 * isolation between institutions. The worker that executes a run is not part of this process; the execution itself is
 * proven in {@code AccrualEngineIT}. Every token is made by the production issuer and every institution is seeded as the
 * owner of the schema.
 */
class AccrualEndpointsIT extends CommittingPostgresIntegrationTest {

    private static final String PLANS = "/api/v1/charges/plans";
    private static final String RUNS = "/api/v1/charges/accrual-runs";
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

    /** What the owner seeded for an institution: an active plan of a grade with two students and the map of accounts. */
    private record Seeded(UUID plan, UUID concept) {
    }

    // --- The routes ---

    @Test
    void anAdministratorListsThePeriodsPreviewsRequestsAndReadsTheRunWithTheKeyOfTheRequest() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Seeded seeded = seed(admin);
        String planPath = PLANS + "/" + seeded.plan();

        JsonNode periods = HarnessProcess.json(get(planPath + "/accrual-periods", admin));
        assertThat(periods).hasSize(3);
        assertThat(periods.get(0).get("periodStart").asString()).isEqualTo("2027-02-01");
        assertThat(periods.get(0).get("label").asString()).isEqualTo("2027-02");
        assertThat(periods.get(0).get("dueOn").asString()).isEqualTo("2027-02-05");
        assertThat(periods.get(0).get("lastRun").isNull()).isTrue();

        HttpResponse<String> previewResponse = post(planPath + "/accrual-preview", admin,
                "{\"periodStart\":\"2027-02-01\"}", null);
        assertThat(previewResponse.statusCode()).as(previewResponse.body()).isEqualTo(200);
        JsonNode preview = HarnessProcess.json(previewResponse);
        assertThat(preview.get("studentsEvaluated").asInt()).isEqualTo(2);
        assertThat(preview.get("toCreate").asInt()).isEqualTo(2);
        assertThat(preview.get("blockers")).isEmpty();
        assertThat(preview.get("totals").get(0).get("base").get("amount").asString()).isEqualTo("3000.00");
        assertThat(preview.get("totals").get(0).get("base").get("currency").asString()).isEqualTo("HNL");
        assertThat(preview.get("totals").get(0).get("tax").get("amount").asString()).isEqualTo("300.00");
        assertThat(preview.get("totals").get(0).get("total").get("amount").asString()).isEqualTo("3300.00");
        assertThat(preview.get("items")).hasSize(2);
        String digest = preview.get("previewDigest").asString();
        assertThat(digest).matches("[0-9a-f]{64}");

        HttpResponse<String> queued = post(planPath + "/accrual-runs", admin,
                "{\"periodStart\":\"2027-02-01\",\"previewDigest\":\"" + digest + "\"}", "key-1");
        assertThat(queued.statusCode()).as(queued.body()).isEqualTo(202);
        JsonNode run = HarnessProcess.json(queued);
        assertThat(run.propertyNames()).containsExactly("id", "planId", "periodStart", "status", "previewChecked",
                "queuedAt", "startedAt", "finishedAt", "studentsEvaluated", "chargesCreated", "chargesExisting",
                "chargesFailed", "failureCode");
        assertThat(run.get("status").asString()).isEqualTo("QUEUED");
        assertThat(run.get("previewChecked").asBoolean()).isTrue();
        String runId = run.get("id").asString();

        // The same key and the same body answer the same run; the key itself never leaves the server.
        HttpResponse<String> replay = post(planPath + "/accrual-runs", admin,
                "{\"periodStart\":\"2027-02-01\",\"previewDigest\":\"" + digest + "\"}", "key-1");
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(HarnessProcess.json(replay).get("id").asString()).isEqualTo(runId);
        assertThat(replay.body()).doesNotContain("key-1");
        // Another body with the same key is refused; another key while the run is not finished is a conflict.
        assertProblem(post(planPath + "/accrual-runs", admin, "{\"periodStart\":\"2027-03-01\"}", "key-1"), 422,
                "idempotency-payload-mismatch");
        assertProblem(post(planPath + "/accrual-runs", admin, "{\"periodStart\":\"2027-02-01\"}", "key-2"), 409,
                "accrual-run-in-progress");

        JsonNode detail = HarnessProcess.json(get(RUNS + "/" + runId, admin));
        assertThat(detail.get("run").get("status").asString()).isEqualTo("QUEUED");
        assertThat(detail.get("items")).isEmpty();
        assertThat(detail.get("totalItems").asLong()).isZero();
        assertThat(HarnessProcess.json(get(RUNS + "?planId=" + seeded.plan(), admin)).get("total").asLong())
                .isEqualTo(1);
        assertThat(HarnessProcess.json(get(planPath + "/accrual-periods", admin)).get(0).get("lastRun").get("id")
                .asString()).isEqualTo(runId);
        assertThat(taskCount()).as("one job of the worker for the one run").isEqualTo(1);
    }

    @Test
    void theRequestNeedsItsKeyAndAValidBodyAndAPeriodOfThePlan() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        String planPath = PLANS + "/" + seed(admin).plan();

        assertProblem(post(planPath + "/accrual-runs", admin, "{\"periodStart\":\"2027-02-01\"}", null), 400,
                "idempotency-key-missing");
        assertProblem(post(planPath + "/accrual-runs", admin, "{\"periodStart\":\"2027-02-02\"}", "k"), 400,
                "validation-failed");
        assertProblem(post(planPath + "/accrual-runs", admin, "{}", "k"), 400, "validation-failed");
        assertProblem(post(planPath + "/accrual-runs", admin,
                "{\"periodStart\":\"2027-02-01\",\"previewDigest\":\"not-a-digest\"}", "k"), 400, "validation-failed");
        assertProblem(post(planPath + "/accrual-runs", admin, "{\"periodStart\":\"2030-02-01\"}", "k"), 400,
                "validation-failed");
        assertProblem(post(planPath + "/accrual-preview", admin, "{\"periodStart\":\"2030-02-01\"}", null), 400,
                "validation-failed");
        assertProblem(get(RUNS + "?size=500", admin), 400, "validation-failed");
        assertThat(taskCount()).isZero();
    }

    @Test
    void aRoleThatOnlyReadsCannotPreviewNorRequestButCanList() throws SQLException {
        Member admin = member(StaffRole.ADMINISTRATOR.code());
        Seeded seeded = seed(admin);
        Member cashier = sameInstitution(admin.institution(), StaffRole.CASHIER.code());
        String planPath = PLANS + "/" + seeded.plan();

        assertProblem(post(planPath + "/accrual-preview", cashier, "{\"periodStart\":\"2027-02-01\"}", null), 403,
                "forbidden");
        assertProblem(post(planPath + "/accrual-runs", cashier, "{\"periodStart\":\"2027-02-01\"}", "key-c"), 403,
                "forbidden");
        assertThat(get(planPath + "/accrual-periods", cashier).statusCode()).isEqualTo(200);
        assertThat(get(RUNS, cashier).statusCode()).isEqualTo(200);
        assertThat(send("GET", planPath + "/accrual-periods", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void aUserOfAnotherInstitutionSeesNothingOfTheRunsAndTheirPlan() throws SQLException {
        Member mine = member(StaffRole.ADMINISTRATOR.code());
        Seeded seeded = seed(mine);
        Member theirs = member(StaffRole.ADMINISTRATOR.code());
        String planPath = PLANS + "/" + seeded.plan();
        String runId = HarnessProcess.json(post(planPath + "/accrual-runs", mine, "{\"periodStart\":\"2027-02-01\"}",
                "key-1")).get("id").asString();

        assertProblem(get(planPath + "/accrual-periods", theirs), 404, "resource-not-found");
        assertProblem(post(planPath + "/accrual-preview", theirs, "{\"periodStart\":\"2027-02-01\"}", null), 404,
                "resource-not-found");
        assertProblem(post(planPath + "/accrual-runs", theirs, "{\"periodStart\":\"2027-02-01\"}", "key-t"), 404,
                "resource-not-found");
        assertProblem(get(RUNS + "/" + runId, theirs), 404, "resource-not-found");
        assertThat(HarnessProcess.json(get(RUNS, theirs)).get("total").asLong()).isZero();
        // The same key in another institution is another key.
        assertProblem(post(planPath + "/accrual-runs", theirs, "{\"periodStart\":\"2027-02-01\"}", "key-1"), 404,
                "resource-not-found");
    }

    // --- Seeding ---

    private Member member(String roleCode) throws SQLException {
        UUID institution = UUID.randomUUID();
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement statement = connection.prepareStatement("""
                        insert into organization_institution
                            (id, legal_name, rtn, address, default_currency, locale, timezone)
                        values (?, 'Colegio de prueba', '08011999123456', 'Tegucigalpa', 'HNL', 'es-HN',
                                'America/Tegucigalpa')
                        """)) {
            statement.setObject(1, institution);
            statement.executeUpdate();
            connection.commit();
        }
        return sameInstitution(institution, roleCode);
    }

    private Member sameInstitution(UUID institution, String roleCode) throws SQLException {
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

    private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    /** An active plan of one grade, monthly from February 2027, priced at a fictional 1,500.00 with a fictional 10 % tax. */
    private static Seeded seed(Member admin) throws SQLException {
        UUID institution = admin.institution();
        UUID year = UUID.randomUUID();
        UUID modality = UUID.randomUUID();
        UUID grade = UUID.randomUUID();
        UUID section = UUID.randomUUID();
        UUID concept = UUID.randomUUID();
        UUID rate = UUID.randomUUID();
        UUID list = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID receivable = UUID.randomUUID();
        UUID revenue = UUID.randomUUID();
        UUID tax = UUID.randomUUID();
        try (Connection c = StaffRoleFixtures.ownerTransaction(institution)) {
            execute(c, "insert into organization_academic_year (institution_id, id, name, start_date, end_date, status) "
                    + "values (?, ?, '2027', date '2027-01-01', date '2027-11-30', 'ACTIVE')", institution, year);
            execute(c, "insert into organization_modality (institution_id, id, code, name) values (?, ?, 'PRIM', 'Primaria')",
                    institution, modality);
            execute(c, "insert into organization_grade (institution_id, id, modality_id, name, ordinal) "
                    + "values (?, ?, ?, 'Primero', 1)", institution, grade, modality);
            execute(c, "insert into organization_section (institution_id, id, academic_year_id, grade_id, name) "
                    + "values (?, ?, ?, ?, 'A')", institution, section, year, grade);
            execute(c, "insert into catalog_tax_rate (institution_id, id, code, name) values (?, ?, 'TAXA', 'Tasa ficticia')",
                    institution, rate);
            execute(c, "insert into catalog_tax_rate_period (institution_id, id, tax_rate_id, rate_percent, valid_from, "
                    + "created_by) values (?, ?, ?, 10, date '2027-01-01', ?)", institution, UUID.randomUUID(), rate,
                    admin.account());
            execute(c, "insert into catalog_payment_concept (institution_id, id, code, name, category, is_taxable, "
                    + "is_recurring, tax_rate_id) values (?, ?, 'COL', 'Colegiatura', 'TUITION', true, true, ?)",
                    institution, concept, rate);
            execute(c, "insert into catalog_price_list (institution_id, id, academic_year_id, name, scope) "
                    + "values (?, ?, ?, 'General 2027', 'INSTITUTION')", institution, list, year);
            execute(c, "insert into catalog_price_list_item (institution_id, id, price_list_id, payment_concept_id, "
                    + "amount, currency, valid_from, created_by) values (?, ?, ?, ?, 1500.00, 'HNL', date '2027-01-01', ?)",
                    institution, UUID.randomUUID(), list, concept, admin.account());
            for (Object[] account : new Object[][] {{receivable, "1105", "ASSET", true}, {revenue, "4105", "INCOME", false},
                    {tax, "2105", "LIABILITY", false}}) {
                execute(c, "insert into ledger_account (institution_id, id, code, name, account_type, "
                        + "holds_student_balances) values (?, ?, ?, 'Cuenta de prueba', ?, ?)", institution, account[0],
                        account[1], account[2], account[3]);
            }
            execute(c, "insert into charges_posting_account (institution_id, id, purpose, ledger_account_id) "
                    + "values (?, ?, 'RECEIVABLE', ?)", institution, UUID.randomUUID(), receivable);
            execute(c, "insert into charges_posting_account (institution_id, id, purpose, ledger_account_id) "
                    + "values (?, ?, 'REVENUE', ?)", institution, UUID.randomUUID(), revenue);
            execute(c, "insert into charges_posting_account (institution_id, id, purpose, ledger_account_id) "
                    + "values (?, ?, 'TAX_PAYABLE', ?)", institution, UUID.randomUUID(), tax);
            execute(c, "insert into charges_plan (institution_id, id, academic_year_id, name, target_scope, target_ref_id, "
                    + "frequency, due_day_of_month) values (?, ?, ?, 'Primaria 2027', 'GRADE', ?, 'MONTHLY', 5)",
                    institution, plan, year, grade);
            execute(c, "insert into charges_plan_item (institution_id, id, plan_id, payment_concept_id, periods_count, "
                    + "first_period_on) values (?, ?, ?, ?, 3, date '2027-02-01')", institution, UUID.randomUUID(), plan,
                    concept);
            execute(c, "update charges_plan set status = 'ACTIVE' where institution_id = ? and id = ?", institution, plan);
            for (int i = 0; i < 2; i++) {
                UUID student = UUID.randomUUID();
                execute(c, "insert into students_student (institution_id, id, student_code, first_name, last_name, "
                        + "birth_date, sex) values (?, ?, ?, 'Ana', 'Prueba', date '2015-01-01', 'F')", institution,
                        student, "E-" + student.toString().substring(0, 8));
                execute(c, "insert into students_enrollment (institution_id, id, student_id, section_id, academic_year_id, "
                        + "enrolled_on) values (?, ?, ?, ?, ?, date '2027-01-10')", institution, UUID.randomUUID(),
                        student, section, year);
            }
            c.commit();
        }
        return new Seeded(plan, concept);
    }

    private static long taskCount() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from scheduled_tasks where task_name = 'charges.accrual-run'");
                var result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
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

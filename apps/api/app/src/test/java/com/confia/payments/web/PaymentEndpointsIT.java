package com.confia.payments.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.payments.PaymentsFixture;
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
 * The payment routes on the real administrative process, started through the production launcher against a real PostgreSQL with
 * the least-privilege role of the application (docs/cortes/payments-register.md): the acceptance flow, the permission of each role,
 * the segregation of duties, the error codes, the permanent key of each write, the scope of the cashier, the map of accounts and the
 * isolation between institutions. Every token is made by the production issuer; institutions, accounts and amounts are fictional.
 */
class PaymentEndpointsIT extends PaymentsFixture {

    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final String BASE = "/api/v1/payments";
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

    private record Member(UUID account, UUID institution, String token, UUID family) {
    }

    private static String money(String amount) {
        return "{\"amount\":\"" + amount + "\",\"currency\":\"HNL\"}";
    }

    private static String cashBody(UUID student, String amount) {
        return "{\"studentId\":\"" + student + "\",\"method\":\"CASH\",\"amount\":" + money(amount) + ",\"receivedOn\":\""
                + today() + "\"}";
    }

    private static String transferBody(UUID student, String amount, String reference) {
        return "{\"studentId\":\"" + student + "\",\"method\":\"BANK_TRANSFER\",\"amount\":" + money(amount)
                + ",\"receivedOn\":\"" + today() + "\",\"reference\":\"" + reference + "\",\"bankName\":\"BAC Credomatic\"}";
    }

    private static String rejectBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    /** The institution of the charges with two charges of 1,650.00 for its student and the open session of the cashier. */
    private record World(Pay pay, Member cashier, Member administrator, Member accountant, Member superAdmin,
            Member otherCashier) {

        UUID student() {
            return pay.student(0);
        }
    }

    private World world() throws SQLException {
        Pay pay = seedPayments(1);
        accrueFebruary(pay.env(), "feb");
        accrue(pay.env(), "mar", MARCH);
        UUID institution = pay.institution();
        World world = new World(pay, member(institution, StaffRole.CASHIER), member(institution, StaffRole.ADMINISTRATOR),
                member(institution, StaffRole.ACCOUNTING), member(institution, StaffRole.SUPER_ADMIN),
                member(institution, StaffRole.CASHIER));
        assertThat(post("/api/v1/cashbox/sessions", world.cashier(), "{\"openingFloat\":" + money("500.00") + "}", "open-1")
                .statusCode()).isEqualTo(201);
        return world;
    }

    // --- The acceptance flow ---

    @Test
    void aCashierCollectsInCashTheChargesAreAppliedOldestFirstAndTheCashGrows() throws SQLException {
        World world = world();

        HttpResponse<String> created = post(BASE, world.cashier(), cashBody(world.student(), "2000.00"), "pay-1");

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode payment = HarnessProcess.json(created);
        assertThat(payment.get("status").asString()).isEqualTo("confirmed");
        assertThat(payment.get("method").asString()).isEqualTo("CASH");
        assertThat(payment.get("amount").get("amount").asString()).isEqualTo("2000.00");
        assertThat(payment.get("amount").get("currency").asString()).isEqualTo("HNL");
        assertThat(payment.get("allocations").size()).isEqualTo(2);
        assertThat(payment.get("allocations").get(0).get("periodLabel").asString()).isEqualTo("2027-02");
        assertThat(payment.get("allocations").get(0).get("amount").get("amount").asString()).isEqualTo("1650.00");
        assertThat(payment.get("allocations").get(1).get("amount").get("amount").asString()).isEqualTo("350.00");
        assertThat(payment.get("allocated").get("amount").asString()).isEqualTo("2000.00");
        assertThat(payment.get("unallocated").get("amount").asString()).isEqualTo("0.00");
        assertThat(created.body()).as("the key never leaves the server").doesNotContain("pay-1");

        JsonNode session = HarnessProcess.json(get("/api/v1/cashbox/sessions/current", world.cashier()));
        assertThat(session.get("expectedAmount").get("amount").asString()).isEqualTo("2500.00");
        assertThat(session.get("movements").get(0).get("type").asString()).isEqualTo("payment_in");

        JsonNode charges = HarnessProcess.json(get("/api/v1/charges/students/" + world.student() + "/charges?sort=periodStart,asc",
                world.accountant()));
        assertThat(charges.get("items").get(0).get("paid").get("amount").asString()).isEqualTo("1650.00");
        assertThat(charges.get("items").get(0).get("outstanding").get("amount").asString()).isEqualTo("0.00");
        assertThat(charges.get("items").get(1).get("paid").get("amount").asString()).isEqualTo("350.00");
        assertThat(charges.get("items").get(1).get("outstanding").get("amount").asString()).isEqualTo("1300.00");
    }

    @Test
    void aSecondSubmitWithTheSameKeyAnswersTheSamePaymentAndAnotherBodyIsRefused() throws SQLException {
        World world = world();
        String id = HarnessProcess.json(post(BASE, world.cashier(), cashBody(world.student(), "100.00"), "pay-1"))
                .get("id").asString();

        HttpResponse<String> again = post(BASE, world.cashier(), cashBody(world.student(), "100.00"), "pay-1");

        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(HarnessProcess.json(again).get("id").asString()).isEqualTo(id);
        assertProblem(post(BASE, world.cashier(), cashBody(world.student(), "100.01"), "pay-1"), 422,
                "idempotency-payload-mismatch");
        assertProblem(post(BASE, world.cashier(), cashBody(world.student(), "100.00"), null), 400,
                "idempotency-key-missing");
        assertThat(HarnessProcess.json(get(BASE, world.accountant())).get("total").asLong()).isEqualTo(1);
    }

    @Test
    void withoutAnOpenSessionTheSameCashIsRefused() throws SQLException {
        World world = world();

        assertProblem(post(BASE, world.otherCashier(), cashBody(world.student(), "100.00"), "pay-1"), 409,
                "cash-session-not-open");
        assertThat(HarnessProcess.json(get(BASE, world.accountant())).get("total").asLong()).isZero();
    }

    // --- The transfer ---

    @Test
    void aDeclaredTransferDoesNotChangeTheBalanceTheAdministratorConfirmsItAndThenItIsApplied() throws SQLException {
        World world = world();
        JsonNode declared = HarnessProcess.json(post(BASE, world.cashier(), transferBody(world.student(), "2000.00",
                "BAC-000111"), "decl-1"));
        String id = declared.get("id").asString();

        assertThat(declared.get("status").asString()).isEqualTo("declared");
        assertThat(declared.get("allocations").size()).isZero();
        assertThat(owed(world.pay(), world.student())).isEqualByComparingTo("3300.00");

        HttpResponse<String> confirmed = post(BASE + "/" + id + "/confirm", world.administrator(), null, "conf-1");

        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(confirmed).get("status").asString()).isEqualTo("confirmed");
        assertThat(HarnessProcess.json(confirmed).get("allocations").size()).isEqualTo(2);
        assertThat(HarnessProcess.json(confirmed).get("decision").get("kind").asString()).isEqualTo("confirmed");
        assertThat(owed(world.pay(), world.student())).isEqualByComparingTo("1300.00");
        assertProblem(post(BASE + "/" + id + "/confirm", world.administrator(), null, "conf-2"), 409,
                "payment-not-declared");
        assertThat(post(BASE + "/" + id + "/confirm", world.administrator(), null, "conf-1").statusCode())
                .as("the same key replays").isEqualTo(200);
    }

    @Test
    void aRejectedTransferNeverChangesTheBalanceAndTheReasonIsKeptInTheDecision() throws SQLException {
        World world = world();
        String id = HarnessProcess.json(post(BASE, world.cashier(), transferBody(world.student(), "2000.00", "BAC-000111"),
                "decl-1")).get("id").asString();

        assertProblem(post(BASE + "/" + id + "/reject", world.administrator(), rejectBody("corto"), "rej-0"), 400,
                "validation-failed");
        HttpResponse<String> rejected = post(BASE + "/" + id + "/reject", world.administrator(),
                rejectBody("El banco no registra esta transferencia"), "rej-1");

        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        JsonNode body = HarnessProcess.json(rejected);
        assertThat(body.get("status").asString()).isEqualTo("rejected");
        assertThat(body.get("decision").get("reason").asString()).isEqualTo("El banco no registra esta transferencia");
        assertThat(body.get("allocations").size()).isZero();
        assertThat(owed(world.pay(), world.student())).isEqualByComparingTo("3300.00");
        assertProblem(post(BASE + "/" + id + "/confirm", world.administrator(), null, "conf-1"), 409, "payment-not-declared");
    }

    @Test
    void whoRegisteredTheTransferCannotDecideItAndTheSameReferenceIsNotDeclaredTwice() throws SQLException {
        World world = world();
        String id = HarnessProcess.json(post(BASE, world.superAdmin(), transferBody(world.student(), "10.00", "BAC-000111"),
                "decl-1")).get("id").asString();

        assertProblem(post(BASE + "/" + id + "/confirm", world.superAdmin(), null, "conf-1"), 403,
                "self-approval-forbidden");
        assertProblem(post(BASE + "/" + id + "/reject", world.superAdmin(), rejectBody("No es mi transferencia, creo"),
                "rej-1"), 403, "self-approval-forbidden");
        assertProblem(post(BASE, world.cashier(), transferBody(world.student(), "10.00", "BAC-000111"), "decl-2"), 409,
                "duplicate-transfer-reference");
        assertThat(owed(world.pay(), world.student())).isEqualByComparingTo("3300.00");
    }

    @Test
    void theChargesOfTheStudentCanBeNarrowedToWhatIsOutstandingAndEachOneSaysIfItIsSettled() throws SQLException {
        World world = world();
        assertThat(post(BASE, world.cashier(), cashBody(world.student(), "1650.00"), "pay-1").statusCode()).isEqualTo(201);
        String charges = "/api/v1/charges/students/" + world.student() + "/charges";

        JsonNode every = HarnessProcess.json(get(charges + "?sort=periodStart,asc", world.administrator()));
        JsonNode owed = HarnessProcess.json(get(charges + "?outstanding=true", world.administrator()));

        assertThat(every.get("items").get(0).get("settled").asBoolean()).isTrue();
        assertThat(every.get("items").get(1).get("settled").asBoolean()).isFalse();
        assertThat(owed.get("total").asLong()).isEqualTo(1);
        assertThat(owed.get("items").get(0).get("settled").asBoolean()).isFalse();
        assertThat(owed.get("items").get(0).get("outstanding").get("amount").asString()).isEqualTo("1650.00");
        assertThat(HarnessProcess.json(get(charges + "?outstanding=true&page=1&size=1", world.administrator()))
                .get("items").size()).isZero();
    }

    // --- A revoked session ---

    @Test
    void aRevokedSessionIsRefusedOnTheWriteAndOnTheReadOfThePayments() throws SQLException {
        World world = world();
        assertThat(post(BASE, world.cashier(), cashBody(world.student(), "100.00"), "live-1").statusCode()).isEqualTo(201);
        assertThat(get(BASE, world.cashier()).statusCode()).isEqualTo(200);

        revoke(world.cashier());

        assertProblem(post(BASE, world.cashier(), cashBody(world.student(), "100.00"), "revoked-1"), 401, "token-invalid");
        assertProblem(get(BASE, world.cashier()), 401, "token-invalid");
        assertThat(scalar(world.pay().institution(), "select count(*) from payments_payment where institution_id = ?",
                world.pay().institution())).as("the refused write left nothing").isEqualTo(1);
    }

    // --- Validation ---

    @Test
    void aBodyThatBreaksTheRulesOfTheAmountOrTheMethodIsRefusedAtTheEdge() throws SQLException {
        World world = world();
        UUID student = world.student();

        assertProblem(post(BASE, world.cashier(), cashBody(student, "0.00"), "k-0"), 400, "validation-failed");
        assertProblem(post(BASE, world.cashier(), cashBody(student, "-5.00"), "k-1"), 400, "validation-failed");
        assertProblem(post(BASE, world.cashier(), cashBody(student, "10.001"), "k-2"), 400, "validation-failed");
        assertProblem(post(BASE, world.cashier(), cashBody(student, "10.00").replace("HNL", "USD"), "k-3"), 400,
                "validation-failed");
        assertProblem(post(BASE, world.cashier(), cashBody(student, "10.00").replace("CASH", "CARD"), "k-4"), 400,
                "validation-failed");
        assertProblem(post(BASE, world.cashier(), transferBody(student, "10.00", "abc"), "k-5"), 400, "validation-failed");
        assertProblem(post(BASE, world.cashier(), cashBody(student, "10.00").replace(today().toString(),
                today().plusDays(1).toString()), "k-6"), 400, "validation-failed");
        // Cash is received on the day of the session: another day is refused and the problem names the field.
        HttpResponse<String> backDated = post(BASE, world.cashier(), cashBody(student, "10.00").replace(today().toString(),
                today().minusDays(1).toString()), "k-6b");
        assertProblem(backDated, 400, "validation-failed");
        assertThat(HarnessProcess.json(backDated).get("errors").get(0).get("field").asString()).isEqualTo("receivedOn");
        assertThat(HarnessProcess.json(backDated).get("errors").get(0).get("reason").asString())
                .isEqualTo("cash-received-today");
        // A transfer keeps the old rule: a past day is fine.
        assertThat(post(BASE, world.cashier(), transferBody(student, "10.00", "BAC-PAST01").replace(today().toString(),
                today().minusDays(3).toString()), "k-6c").statusCode()).isEqualTo(201);
        assertProblem(post(BASE, world.cashier(), cashBody(UUID.randomUUID(), "10.00"), "k-7"), 404, "resource-not-found");
        assertThat(HarnessProcess.json(get(BASE, world.accountant())).get("total").asLong()).as("only the past transfer").isEqualTo(1);
    }

    // --- Permissions and scope ---

    @Test
    void eachRoleDoesWhatItsPermissionsAllowAndNothingElse() throws SQLException {
        World world = world();
        String id = HarnessProcess.json(post(BASE, world.cashier(), transferBody(world.student(), "10.00", "BAC-000111"),
                "decl-1")).get("id").asString();

        assertProblem(post(BASE, world.administrator(), cashBody(world.student(), "10.00"), "k-1"), 403, "forbidden");
        assertProblem(post(BASE, world.accountant(), cashBody(world.student(), "10.00"), "k-2"), 403, "forbidden");
        assertProblem(post(BASE + "/" + id + "/confirm", world.cashier(), null, "k-3"), 403, "forbidden");
        assertProblem(post(BASE + "/" + id + "/reject", world.accountant(), rejectBody("El banco no registra esto"), "k-4"),
                403, "forbidden");
        assertThat(get(BASE, world.accountant()).statusCode()).isEqualTo(200);
        assertThat(get(BASE, world.administrator()).statusCode()).isEqualTo(200);
        assertProblem(get(BASE, null), 401, "authentication-required");
    }

    @Test
    void aCashierReadsOnlyItsOwnPaymentsAndAnotherCashierSeesA404() throws SQLException {
        World world = world();
        String id = HarnessProcess.json(post(BASE, world.cashier(), transferBody(world.student(), "10.00", "BAC-000111"),
                "decl-1")).get("id").asString();

        assertThat(get(BASE + "/" + id, world.cashier()).statusCode()).isEqualTo(200);
        assertProblem(get(BASE + "/" + id, world.otherCashier()), 404, "resource-not-found");
        assertThat(HarnessProcess.json(get(BASE, world.otherCashier())).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(BASE, world.cashier())).get("total").asLong()).isEqualTo(1);
        assertThat(HarnessProcess.json(get(BASE + "/" + id, world.accountant())).get("id").asString()).isEqualTo(id);
        JsonNode filtered = HarnessProcess.json(get(BASE + "?status=declared&method=BANK_TRANSFER&size=1&sort=amount,desc",
                world.administrator()));
        assertThat(filtered.get("items").size()).isEqualTo(1);
        assertProblem(get(BASE + "?size=500", world.administrator()), 400, "validation-failed");
        assertProblem(get(BASE + "?status=paid", world.administrator()), 400, "validation-failed");
    }

    @Test
    void anotherInstitutionSeesA404ForEveryRouteOfAPayment() throws SQLException {
        World world = world();
        World other = world();
        String id = HarnessProcess.json(post(BASE, world.cashier(), transferBody(world.student(), "10.00", "BAC-000111"),
                "decl-1")).get("id").asString();

        assertProblem(get(BASE + "/" + id, other.administrator()), 404, "resource-not-found");
        assertProblem(post(BASE + "/" + id + "/confirm", other.administrator(), null, "c-1"), 404, "resource-not-found");
        assertProblem(post(BASE + "/" + id + "/reject", other.administrator(), rejectBody("El banco no registra esto"),
                "r-1"), 404, "resource-not-found");
        assertThat(HarnessProcess.json(get(BASE, other.administrator())).get("total").asLong()).isZero();
        assertProblem(post(BASE, other.cashier(), cashBody(world.student(), "10.00"), "k-1"), 404, "resource-not-found");
    }

    // --- The charge with payments ---

    @Test
    void aChargeThatHasPaymentsCannotBeVoidedThroughTheApi() throws SQLException {
        World world = world();
        post(BASE, world.cashier(), cashBody(world.student(), "100.00"), "pay-1");
        List<UUID> charges = chargeIds(world.pay());

        assertProblem(post("/api/v1/charges/" + charges.get(0) + "/void", world.administrator(),
                "{\"reason\":\"Cobro duplicado por error de la secretaria\"}", "void-1"), 409, "charge-has-payments");
        assertThat(post("/api/v1/charges/" + charges.get(1) + "/void", world.administrator(),
                "{\"reason\":\"Cobro duplicado por error de la secretaria\"}", "void-2").statusCode()).isEqualTo(200);
    }

    // --- The map of accounts ---

    @Test
    void theMapOfCashAndBankIsReadByThoseWhoReadTheLedgerAndSetOnlyByThoseWhoLoadIt() throws SQLException {
        World world = world();
        JsonNode map = HarnessProcess.json(get(BASE + "/posting-accounts", world.accountant()));
        assertThat(map.get("items").size()).isEqualTo(2);
        assertThat(map.get("items").get(0).get("purpose").asString()).isEqualTo("BANK");
        long version = map.get("items").get(0).get("version").asLong();
        String body = "{\"entries\":[{\"purpose\":\"BANK\",\"ledgerAccountId\":\"" + world.pay().cashAccount()
                + "\",\"version\":" + version + "}]}";

        assertProblem(put(BASE + "/posting-accounts", world.administrator(), body), 403, "forbidden");
        assertThat(get(BASE + "/posting-accounts", world.cashier()).statusCode()).as("ledger:read").isEqualTo(200);
        HttpResponse<String> set = put(BASE + "/posting-accounts", world.superAdmin(), body);
        assertThat(set.statusCode()).as(set.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(set).get("items").get(0).get("version").asLong()).isEqualTo(version + 1);
        assertProblem(put(BASE + "/posting-accounts", world.superAdmin(), body), 409, "stale-version");
        String receivable = "{\"entries\":[{\"purpose\":\"CASH\",\"ledgerAccountId\":\"" + world.pay().env().receivable()
                + "\",\"version\":0}]}";
        assertProblem(put(BASE + "/posting-accounts", world.superAdmin(), receivable), 409, "posting-account-invalid");
        assertProblem(put(BASE + "/posting-accounts", world.superAdmin(), "{\"entries\":[{\"purpose\":\"CASH\","
                + "\"ledgerAccountId\":\"" + UUID.randomUUID() + "\"}]}"), 404, "resource-not-found");
    }

    @Test
    void withoutTheMapOfAccountsCashAndConfirmationAnswerPostingAccountsIncomplete() throws SQLException {
        Pay pay = seedPayments(1, false);
        accrueFebruary(pay.env(), "feb");
        Member cashier = member(pay.institution(), StaffRole.CASHIER);
        Member administrator = member(pay.institution(), StaffRole.ADMINISTRATOR);
        post("/api/v1/cashbox/sessions", cashier, "{\"openingFloat\":" + money("0.00") + "}", "open-1");

        assertProblem(post(BASE, cashier, cashBody(pay.student(0), "10.00"), "k-1"), 409, "posting-accounts-incomplete");
        String id = HarnessProcess.json(post(BASE, cashier, transferBody(pay.student(0), "10.00", "BAC-000111"), "k-2"))
                .get("id").asString();
        assertProblem(post(BASE + "/" + id + "/confirm", administrator, null, "c-1"), 409, "posting-accounts-incomplete");
    }

    // --- Seeding ---

    private List<UUID> chargeIds(Pay pay) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = StaffRoleFixtures.ownerTransaction(pay.institution());
                PreparedStatement s = c.prepareStatement("select id from charges_charge where institution_id = ? and "
                        + "student_id = ? order by period_start")) {
            s.setObject(1, pay.institution());
            s.setObject(2, pay.student(0));
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

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
        return new Member(account, institution, token, family);
    }

    /** Revokes the family of refresh tokens of the session: the access token that names it is refused from then on. */
    private static void revoke(Member member) throws SQLException {
        try (java.sql.Connection connection = StaffRoleFixtures.ownerTransaction(member.institution());
                java.sql.PreparedStatement statement = connection.prepareStatement("""
                        update identity_refresh_token_family
                           set revoked_at = now(), revoked_reason = 'password_change'
                         where institution_id = ? and id = ?
                        """)) {
            statement.setObject(1, member.institution());
            statement.setObject(2, member.family());
            assertThat(statement.executeUpdate()).isEqualTo(1);
            connection.commit();
        }
    }

    // --- HTTP ---

    private static HttpResponse<String> get(String path, Member member) {
        return send("GET", path, member == null ? null : member.token(), null, null);
    }

    private static HttpResponse<String> post(String path, Member member, String body, String idempotencyKey) {
        return send("POST", path, member.token(), body, idempotencyKey);
    }

    private static HttpResponse<String> put(String path, Member member, String body) {
        return send("PUT", path, member.token(), body, null);
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

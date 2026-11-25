package com.confia.scholarships.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.StaffRole;
import com.confia.scholarships.ScholarshipsFixture;
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
 * The scholarship routes on the real administrative process, started through the production launcher against a real PostgreSQL with
 * the least-privilege role of the application (docs/cortes/scholarships-awards.md): the permission of each role on the decisions,
 * the segregation of duties, the revoked session, the validation of the body and of the reason, the permanent key of each decision,
 * the closed scope of a scholarship with approved awards and the isolation between institutions. Every token is made by the production
 * issuer; institutions, people, codes and amounts are fictional.
 *
 * <p>This is the test that justifies leaving {@code /api/v1/scholarship} out of the revoked-session walk of {@code
 * AuthorizationEdgeIT} (that harness opens no database).
 */
class ScholarshipsEndpointsIT extends ScholarshipsFixture {

    private static final String RATE_LIMIT = "confia.web.rate-limit.";
    private static final String SCHOLARSHIPS = "/api/v1/scholarships";
    private static final String AWARDS = "/api/v1/scholarship-awards";
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

    /** An institution with two students, its academic year and the members of the roles that act on a scholarship. */
    private record World(Env env, UUID year, Member administrator, Member superAdmin, Member coordinator, Member cashier) {

        UUID institution() {
            return env.institution();
        }

        UUID student() {
            return env.students().get(0);
        }
    }

    private World world() throws SQLException {
        Env env = seed(2);
        UUID institution = env.institution();
        return new World(env, yearOf(institution), member(institution, StaffRole.ADMINISTRATOR),
                member(institution, StaffRole.SUPER_ADMIN), member(institution, StaffRole.ACADEMIC_COORDINATOR),
                member(institution, StaffRole.CASHIER));
    }

    private static String scholarshipBody(World w, String code) {
        return "{\"code\":\"" + code + "\",\"name\":\"Beca del 25 %\",\"benefitType\":\"PERCENTAGE\",\"percent\":\"25\","
                + "\"periodCap\":{\"amount\":\"500.00\",\"currency\":\"HNL\"},\"conceptIds\":[\"" + w.env().concept() + "\"]}";
    }

    private static String updateBody(World w, long version, String cap, String conceptIds) {
        return "{\"version\":" + version + ",\"name\":\"Beca del 25 %\",\"benefitType\":\"PERCENTAGE\",\"percent\":\"25\","
                + "\"periodCap\":" + (cap == null ? "null" : "{\"amount\":\"" + cap + "\",\"currency\":\"HNL\"}")
                + ",\"conceptIds\":[" + conceptIds + "]}";
    }

    private static String awardBody(World w, UUID scholarship) {
        return "{\"scholarshipId\":\"" + scholarship + "\",\"studentId\":\"" + w.student() + "\",\"academicYearId\":\""
                + w.year() + "\",\"validFrom\":\"2027-02-01\",\"validTo\":\"2027-06-30\"}";
    }

    private static String reasonBody(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static final String REASON_TEXT = "La familia ya no cumple con los requisitos";

    private static UUID idOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return UUID.fromString(HarnessProcess.json(response).get("id").asString());
    }

    private UUID scholarship(World w, String code) {
        return idOf(post(SCHOLARSHIPS, w.administrator(), scholarshipBody(w, code), null));
    }

    /** An award requested by the coordinator for the first student. */
    private UUID requestedAward(World w, UUID scholarship) {
        return idOf(post(AWARDS, w.coordinator(), awardBody(w, scholarship), null));
    }

    private UUID approvedAward(World w, UUID scholarship) {
        UUID award = requestedAward(w, scholarship);
        assertThat(post(AWARDS + "/" + award + "/approve", w.administrator(), null, "approve-" + award).statusCode()).isEqualTo(200);
        return award;
    }

    // --- The happy path ---

    @Test
    void aCoordinatorRequestsAnAdministratorApprovesAndLaterRevokesWithTheSameKeyAnsweringTheSameDecision() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        UUID award = requestedAward(w, scholarship);

        HttpResponse<String> approved = post(AWARDS + "/" + award + "/approve", w.administrator(), null, "approve-1");
        HttpResponse<String> replay = post(AWARDS + "/" + award + "/approve", w.administrator(), null, "approve-1");

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(approved).get("status").asString()).isEqualTo("approved");
        assertThat(HarnessProcess.json(replay).get("decision").get("id").asString())
                .isEqualTo(HarnessProcess.json(approved).get("decision").get("id").asString());
        assertThat(approved.body()).doesNotContain("approve-1");
        HttpResponse<String> revoked = post(AWARDS + "/" + award + "/revoke", w.administrator(), reasonBody(REASON_TEXT), "revoke-1");
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(revoked).get("status").asString()).isEqualTo("revoked");
    }

    // --- Permissions ---

    @Test
    void aCashierCannotApproveNorRejectAndNothingChanges() throws SQLException {
        World w = world();
        UUID award = requestedAward(w, scholarship(w, "BECA-25"));

        assertProblem(post(AWARDS + "/" + award + "/approve", w.cashier(), null, "k-1"), 403, "forbidden");
        assertProblem(post(AWARDS + "/" + award + "/reject", w.cashier(), reasonBody(REASON_TEXT), "k-2"), 403, "forbidden");

        assertThat(HarnessProcess.json(get(AWARDS + "/" + award, w.administrator())).get("status").asString())
                .isEqualTo("pending");
        assertThat(scalar(w.institution(), "select count(*) from scholarships_award_decision where institution_id = ?",
                w.institution())).isZero();
    }

    @Test
    void aCoordinatorCanRequestButCannotApproveRejectNorRevoke() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        UUID pending = requestedAward(w, scholarship);
        UUID approved = approvedAward(w, scholarship(w, "BECA-10"));

        assertProblem(post(AWARDS + "/" + pending + "/approve", w.coordinator(), null, "k-1"), 403, "forbidden");
        assertProblem(post(AWARDS + "/" + pending + "/reject", w.coordinator(), reasonBody(REASON_TEXT), "k-2"), 403,
                "forbidden");
        assertProblem(post(AWARDS + "/" + approved + "/revoke", w.coordinator(), reasonBody(REASON_TEXT), "k-3"), 403,
                "forbidden");

        assertThat(HarnessProcess.json(get(AWARDS + "/" + approved, w.administrator())).get("status").asString())
                .isEqualTo("approved");
        assertThat(get(AWARDS, w.cashier()).statusCode()).as("a cashier reads").isEqualTo(200);
        assertProblem(post(AWARDS, w.cashier(), awardBody(w, scholarship), null), 403, "forbidden");
        assertProblem(post(SCHOLARSHIPS, w.cashier(), scholarshipBody(w, "OTRA"), null), 403, "forbidden");
    }

    @Test
    void whoRequestedTheAwardCannotApproveNorRejectItBecauseOfSegregationOfDuties() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        // An administrator may both request and approve: the system refuses that one person does both.
        UUID award = idOf(post(AWARDS, w.administrator(), awardBody(w, scholarship), null));

        assertProblem(post(AWARDS + "/" + award + "/approve", w.administrator(), null, "self-1"), 403,
                "self-approval-forbidden");
        assertProblem(post(AWARDS + "/" + award + "/reject", w.administrator(), reasonBody(REASON_TEXT), "self-2"), 403,
                "self-approval-forbidden");

        assertThat(HarnessProcess.json(get(AWARDS + "/" + award, w.administrator())).get("status").asString())
                .isEqualTo("pending");
        assertThat(post(AWARDS + "/" + award + "/approve", w.superAdmin(), null, "other-1").statusCode()).isEqualTo(200);
    }

    // --- A revoked session ---

    @Test
    void aRevokedSessionIsRefusedOnTheWritesAndOnTheReadsOfTheScholarships() throws SQLException {
        World w = world();
        UUID award = requestedAward(w, scholarship(w, "BECA-25"));
        assertThat(get(AWARDS, w.administrator()).statusCode()).isEqualTo(200);

        revoke(w.administrator());

        assertProblem(post(AWARDS + "/" + award + "/approve", w.administrator(), null, "revoked-1"), 401, "token-invalid");
        assertProblem(get(AWARDS, w.administrator()), 401, "token-invalid");
        assertProblem(get(SCHOLARSHIPS, w.administrator()), 401, "token-invalid");
        assertProblem(post(SCHOLARSHIPS, w.administrator(), scholarshipBody(w, "NUEVA"), null), 401, "token-invalid");
        assertThat(send("GET", AWARDS, null, null, null).statusCode()).isEqualTo(401);
        assertThat(scalar(w.institution(), "select count(*) from scholarships_award_decision where institution_id = ?",
                w.institution())).as("the refused write left nothing").isZero();
    }

    // --- Validation ---

    @Test
    void aReasonUnderTwentyCharactersOrOverFiveHundredIsRefusedOnRejectAndRevoke() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        UUID pending = requestedAward(w, scholarship);
        UUID approved = approvedAward(w, scholarship(w, "BECA-10"));

        assertProblem(post(AWARDS + "/" + pending + "/reject", w.administrator(), reasonBody("x".repeat(19)), "k-1"), 400,
                "validation-failed");
        assertProblem(post(AWARDS + "/" + pending + "/reject", w.administrator(), reasonBody("x".repeat(501)), "k-2"), 400,
                "validation-failed");
        assertProblem(post(AWARDS + "/" + approved + "/revoke", w.administrator(), reasonBody("x".repeat(19)), "k-3"), 400,
                "validation-failed");
        assertProblem(post(AWARDS + "/" + approved + "/revoke", w.administrator(), "{}", "k-4"), 400, "validation-failed");

        assertThat(HarnessProcess.json(get(AWARDS + "/" + pending, w.administrator())).get("status").asString())
                .isEqualTo("pending");
        assertThat(HarnessProcess.json(get(AWARDS + "/" + approved, w.administrator())).get("status").asString())
                .isEqualTo("approved");
    }

    @Test
    void aBodyWithAPropertyThatTheRouteDoesNotDeclareIsRefused() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        UUID pending = requestedAward(w, scholarship);

        assertProblem(post(AWARDS + "/" + pending + "/reject", w.administrator(),
                "{\"reason\":\"" + REASON_TEXT + "\",\"status\":\"approved\"}", "k-1"), 400, "validation-failed");
        assertProblem(post(AWARDS, w.coordinator(), awardBody(w, scholarship).replace("}", ",\"status\":\"approved\"}"), null),
                400, "validation-failed");
        assertProblem(post(SCHOLARSHIPS, w.administrator(), scholarshipBody(w, "OTRA").replace("}", ",\"active\":false}"), null),
                400, "validation-failed");

        assertThat(HarnessProcess.json(get(AWARDS + "/" + pending, w.administrator())).get("status").asString())
                .isEqualTo("pending");
    }

    @Test
    void everyDecisionNeedsItsIdempotencyKey() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        UUID pending = requestedAward(w, scholarship);
        UUID approved = approvedAward(w, scholarship(w, "BECA-10"));

        assertProblem(post(AWARDS + "/" + pending + "/approve", w.administrator(), null, null), 400,
                "idempotency-key-missing");
        assertProblem(post(AWARDS + "/" + pending + "/reject", w.administrator(), reasonBody(REASON_TEXT), null), 400,
                "idempotency-key-missing");
        assertProblem(post(AWARDS + "/" + approved + "/revoke", w.administrator(), reasonBody(REASON_TEXT), null), 400,
                "idempotency-key-missing");

        assertThat(HarnessProcess.json(get(AWARDS + "/" + pending, w.administrator())).get("status").asString())
                .isEqualTo("pending");
    }

    @Test
    void theSameKeyForAnotherRequestIsAMismatch() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        UUID first = requestedAward(w, scholarship);
        UUID second = idOf(post(AWARDS, w.coordinator(),
                awardBody(w, scholarship).replace(w.student().toString(), w.env().students().get(1).toString()), null));
        assertThat(post(AWARDS + "/" + first + "/approve", w.administrator(), null, "shared-key").statusCode()).isEqualTo(200);

        assertProblem(post(AWARDS + "/" + second + "/approve", w.administrator(), null, "shared-key"), 422,
                "idempotency-payload-mismatch");
    }

    // --- The scope of a scholarship with approved awards ---

    @Test
    void withAnApprovedAwardTheCapCannotBeRaisedNorRemovedAndAConceptCannotBeAdded() throws SQLException {
        World w = world();
        UUID scholarship = scholarship(w, "BECA-25");
        String concept = "\"" + w.env().concept() + "\"";
        approvedAward(w, scholarship);

        assertProblem(put(SCHOLARSHIPS + "/" + scholarship, w.administrator(), updateBody(w, 0, "500.01", concept)), 409,
                "scholarship-scope-locked");
        assertProblem(put(SCHOLARSHIPS + "/" + scholarship, w.administrator(), updateBody(w, 0, null, concept)), 409,
                "scholarship-scope-locked");
        HttpResponse<String> lowered = put(SCHOLARSHIPS + "/" + scholarship, w.administrator(), updateBody(w, 0, "400.00", concept));
        assertThat(lowered.statusCode()).as(lowered.body()).isEqualTo(200);
        assertThat(HarnessProcess.json(lowered).get("periodCap").get("amount").asString()).isEqualTo("400.00");
    }

    // --- Isolation between institutions ---

    @Test
    void anotherInstitutionSeesA404ForEveryDecisionAndForTheAwardItself() throws SQLException {
        World mine = world();
        World theirs = world();
        UUID scholarship = scholarship(mine, "BECA-25");
        UUID award = requestedAward(mine, scholarship);
        UUID approved = approvedAward(mine, scholarship(mine, "BECA-10"));

        assertProblem(get(AWARDS + "/" + award, theirs.administrator()), 404, "resource-not-found");
        assertProblem(post(AWARDS + "/" + award + "/approve", theirs.administrator(), null, "x-1"), 404, "resource-not-found");
        assertProblem(post(AWARDS + "/" + award + "/reject", theirs.administrator(), reasonBody(REASON_TEXT), "x-2"), 404,
                "resource-not-found");
        assertProblem(post(AWARDS + "/" + approved + "/revoke", theirs.administrator(), reasonBody(REASON_TEXT), "x-3"), 404,
                "resource-not-found");
        assertProblem(get(SCHOLARSHIPS + "/" + scholarship, theirs.administrator()), 404, "resource-not-found");
        assertProblem(post(SCHOLARSHIPS + "/" + scholarship + "/deactivate", theirs.administrator(), null, null), 404,
                "resource-not-found");
        assertProblem(post(AWARDS, theirs.coordinator(), awardBody(theirs, scholarship), null), 404, "resource-not-found");
        assertThat(HarnessProcess.json(get(AWARDS, theirs.administrator())).get("total").asLong()).isZero();
        assertThat(HarnessProcess.json(get(AWARDS + "/" + award, mine.administrator())).get("status").asString())
                .isEqualTo("pending");
    }

    // --- Members and revocation ---

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
        try (Connection connection = StaffRoleFixtures.ownerTransaction(member.institution());
                PreparedStatement statement = connection.prepareStatement("""
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
        return send("GET", path, member.token(), null, null);
    }

    private static HttpResponse<String> post(String path, Member member, String body, String idempotencyKey) {
        return send("POST", path, member.token(), body, idempotencyKey);
    }

    private static HttpResponse<String> put(String path, Member member, String body) {
        return send("PUT", path, member.token(), body, null);
    }

    private static HttpResponse<String> send(String method, String path, String token, String body, String idempotencyKey) {
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

package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.AdminProcessLauncher;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.token.AccessToken;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.MfaPurpose;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.shared.web.harness.HarnessProcess;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /api/v1/auth/sessions/current} on the real administrative process, started through the
 * production launcher against a real PostgreSQL with the least-privilege role of the application
 * (session-tokens-and-web-layer design.md decisions 5, 7 and 13; scenarios I129 to I134, WE29, and
 * the HTTP half of OR05 and OR10 that task 2.2 could only prove in a unit). Every token is made
 * by the production issuer with a key pair generated for the run, which the process is started
 * with, and every family is seeded with SQL so the window of a scenario is exactly the one it names.
 */
class CurrentSessionEndpointIT extends CommittingPostgresIntegrationTest {

    private static final String ROUTE = "/api/v1/auth/sessions/current";
    private static final String ANA = "ana.martinez@colegio.edu.hn";
    private static final BouncyCastleArgon2PasswordHasher HASHER = new BouncyCastleArgon2PasswordHasher(
            Argon2Profile.floor(),
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32])));
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static KeyPair keys;
    private static CompactJws jws;
    private static ConfigurableApplicationContext process;
    private static int port;

    private final InstitutionId institution = new InstitutionId(UUID.randomUUID());
    private final InstitutionId otherInstitution = new InstitutionId(UUID.randomUUID());

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

    // --- seeding ---

    private static SecurityContext contextOf(InstitutionId of) {
        return new SecurityContext("", "system", of.value().toString(), UUID.randomUUID().toString());
    }

    private StaffAccountId seedAccount(InstitutionId of, String email) {
        StaffAccountId account = new StaffAccountId(UUID.randomUUID());
        String hash = HASHER.hash(PlainPassword.of("Cafetal de Copán 2026")).value();
        transactionRunner().execute(contextOf(of), () -> dsl.execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, ?, ?)
                """, of.value(), account.value(), LoginIdentifier.of(email).value(), hash, false));
        return account;
    }

    private UUID seedFamily(InstitutionId of, StaffAccountId account, boolean secondFactor,
            Instant createdAt, Instant lastUsedAt, Instant absoluteEnd, Instant revokedAt) {
        UUID family = UUID.randomUUID();
        transactionRunner().execute(contextOf(of), () -> dsl.execute("""
                insert into identity_refresh_token_family
                    (institution_id, id, account_id, second_factor_verified, user_agent_fingerprint,
                     created_at, last_used_at, absolute_expires_at, revoked_at, revoked_reason)
                values (?, ?, ?, ?, ?, cast(? as timestamptz), cast(? as timestamptz),
                        cast(? as timestamptz), cast(? as timestamptz), ?)
                """, of.value(), family, account.value(), secondFactor,
                UserAgentFingerprint.of("agente-uno").value(), createdAt.toString(),
                lastUsedAt.toString(), absoluteEnd.toString(),
                revokedAt == null ? null : revokedAt.toString(),
                revokedAt == null ? null : "password_change"));
        return family;
    }

    /** A family created five minutes ago and last used then: idle thirty minutes after that. */
    private UUID seedLiveFamily(InstitutionId of, StaffAccountId account, boolean secondFactor) {
        Instant created = now().minus(Duration.ofMinutes(5));
        return seedFamily(of, account, secondFactor, created, created,
                created.plus(Duration.ofHours(12)), null);
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private static AccessToken accessToken(StaffAccountId account, InstitutionId tenant,
            UUID session, Set<AuthenticationMethod> methods, Instant issuedAt) {
        return new AccessTokenIssuer(jws, Clock.fixed(issuedAt, java.time.ZoneOffset.UTC))
                .issueAccess(account.value(), tenant.value(), session, methods);
    }

    private static HttpResponse<String> get(String path, String... headers) {
        return send("GET", path, headers);
    }

    private static HttpResponse<String> send(String method, String path, String... headers) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
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

    private static HttpResponse<String> current(String token) {
        return get(ROUTE, "Authorization", "Bearer " + token);
    }

    private static String everythingOf(HttpResponse<String> response) {
        return response.body() + " " + response.headers().map();
    }

    // --- I129 and I130 ---

    @Test
    void aValidTokenOfALiveSessionAnswers200WithTheExactSessionAndNothingMore() {
        StaffAccountId ana = seedAccount(institution, ANA);
        Instant created = now().minus(Duration.ofMinutes(5));
        Instant lastUsed = now().minus(Duration.ofMinutes(2));
        Instant absoluteEnd = created.plus(Duration.ofHours(12));
        UUID family = seedFamily(institution, ana, true, created, lastUsed, absoluteEnd, null);
        AccessToken token = accessToken(ana, institution, family,
                Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD),
                now());

        HttpResponse<String> response = current(token.compact());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().firstValue("Set-Cookie"))
                .as("the answer of the current session sets no cookie (WE65)").isEmpty();
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        JsonNode body = HarnessProcess.json(response);
        List<String> fields = new ArrayList<>();
        body.propertyNames().forEach(fields::add);
        assertThat(fields).as("the closed list of seven fields, in this order").containsExactly(
                "sessionId", "accountId", "institutionId", "authenticationMethods",
                "accessTokenExpiresAt", "sessionExpiresAt", "idleExpiresAt");
        assertThat(body.get("sessionId").asString()).isEqualTo(family.toString());
        assertThat(body.get("accountId").asString()).isEqualTo(ana.value().toString());
        assertThat(body.get("institutionId").asString()).isEqualTo(institution.value().toString());
        assertThat(body.get("authenticationMethods").toString()).isEqualTo("[\"pwd\",\"otp\"]");
        assertThat(Instant.parse(body.get("accessTokenExpiresAt").asString()))
                .isEqualTo(token.expiresAt());
        assertThat(body.get("sessionExpiresAt").asString()).isEqualTo(absoluteEnd.toString());
        assertThat(body.get("idleExpiresAt").asString())
                .isEqualTo(lastUsed.plus(Duration.ofMinutes(30)).toString());
    }

    @Test
    void aPasswordOnlySessionListsOnlyThePasswordMethod() {
        StaffAccountId ana = seedAccount(institution, ANA);
        UUID family = seedLiveFamily(institution, ana, false);

        HttpResponse<String> response = current(accessToken(ana, institution, family,
                Set.of(AuthenticationMethod.PASSWORD), now()).compact());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(HarnessProcess.json(response).get("authenticationMethods").toString())
                .isEqualTo("[\"pwd\"]");
    }

    @Test
    void noPersonalDatumReachesTheBodyOrTheHeaders() {
        StaffAccountId ana = seedAccount(institution, ANA);
        UUID family = seedLiveFamily(institution, ana, false);

        HttpResponse<String> response = current(accessToken(ana, institution, family,
                Set.of(AuthenticationMethod.PASSWORD), now()).compact());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(everythingOf(response)).doesNotContainIgnoringCase("ana.martinez")
                .doesNotContainIgnoringCase("colegio.edu.hn").doesNotContain("email")
                .doesNotContain("password").doesNotContain("Authorization");
    }

    // --- I131 and I132 ---

    @Test
    void noCredentialAnswers401AuthenticationRequiredWithTheBearerChallenge() {
        HttpResponse<String> response = get(ROUTE);

        assertProblem(response, 401, "authentication-required");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
    }

    @Test
    void anExpiredAnAlteredAndARestrictedTokenAnswer401WithTheirOwnCode() {
        StaffAccountId ana = seedAccount(institution, ANA);
        UUID family = seedLiveFamily(institution, ana, false);
        Set<AuthenticationMethod> password = Set.of(AuthenticationMethod.PASSWORD);

        HttpResponse<String> expired = current(accessToken(ana, institution, family, password,
                now().minus(Duration.ofMinutes(11))).compact());
        HttpResponse<String> altered = current(withAlteredSignature(accessToken(ana, institution,
                family, password, now()).compact()));
        HttpResponse<String> restricted = current(new AccessTokenIssuer(jws, Clock.systemUTC())
                .issueMfa(ana.value(), institution.value(), MfaPurpose.MFA_VERIFY).compact());

        assertProblem(expired, 401, "token-expired");
        assertProblem(altered, 401, "token-invalid");
        assertProblem(restricted, 401, "token-invalid");
        assertThat(expired.headers().allValues("WWW-Authenticate"))
                .containsExactly("Bearer error=\"invalid_token\"");
        assertThat(altered.headers().allValues("WWW-Authenticate"))
                .containsExactly("Bearer error=\"invalid_token\"");
        assertThat(restricted.headers().allValues("WWW-Authenticate"))
                .containsExactly("Bearer error=\"invalid_token\"");
    }

    private static String withAlteredSignature(String compact) {
        int signatureStart = compact.lastIndexOf('.') + 1;
        int position = signatureStart + 10;
        char replaced = compact.charAt(position) == 'A' ? 'B' : 'A';
        return compact.substring(0, position) + replaced + compact.substring(position + 1);
    }

    // --- I133 ---

    @Test
    void aRevokedAndAnUnknownSessionWithAnUnexpiredTokenAreIdenticalToAnAlteredToken() {
        StaffAccountId ana = seedAccount(institution, ANA);
        Instant created = now().minus(Duration.ofMinutes(5));
        UUID revoked = seedFamily(institution, ana, false, created, created,
                created.plus(Duration.ofHours(12)), now().minus(Duration.ofMinutes(1)));
        Set<AuthenticationMethod> password = Set.of(AuthenticationMethod.PASSWORD);

        HttpResponse<String> revokedResponse = current(
                accessToken(ana, institution, revoked, password, now()).compact());
        HttpResponse<String> unknownResponse = current(
                accessToken(ana, institution, UUID.randomUUID(), password, now()).compact());
        HttpResponse<String> alteredResponse = current(withAlteredSignature(
                accessToken(ana, institution, revoked, password, now()).compact()));

        JsonNode revokedBody = assertProblem(revokedResponse, 401, "token-invalid");
        JsonNode unknownBody = assertProblem(unknownResponse, 401, "token-invalid");
        JsonNode alteredBody = assertProblem(alteredResponse, 401, "token-invalid");
        assertThat(withoutTrace(revokedBody)).isEqualTo(withoutTrace(unknownBody))
                .isEqualTo(withoutTrace(alteredBody));
        assertThat(revokedResponse.headers().allValues("WWW-Authenticate"))
                .isEqualTo(unknownResponse.headers().allValues("WWW-Authenticate"))
                .isEqualTo(alteredResponse.headers().allValues("WWW-Authenticate"));
    }

    @Test
    void anIdleAnAbsolutelyExpiredAndAForeignAccountSessionDoNotOpenTheRoute() {
        StaffAccountId ana = seedAccount(institution, ANA);
        StaffAccountId luis = seedAccount(institution, "luis.perez@colegio.edu.hn");
        Instant old = now().minus(Duration.ofMinutes(45));
        UUID idle = seedFamily(institution, ana, false, old, old, old.plus(Duration.ofHours(12)),
                null);
        UUID ended = seedFamily(institution, ana, false, now().minus(Duration.ofHours(13)),
                now().minus(Duration.ofMinutes(1)), now().minus(Duration.ofMinutes(10)), null);
        UUID anasFamily = seedLiveFamily(institution, ana, false);
        Set<AuthenticationMethod> password = Set.of(AuthenticationMethod.PASSWORD);

        assertProblem(current(accessToken(ana, institution, idle, password, now()).compact()),
                401, "token-invalid");
        assertProblem(current(accessToken(ana, institution, ended, password, now()).compact()),
                401, "token-invalid");
        assertProblem(current(accessToken(luis, institution, anasFamily, password, now())
                .compact()), 401, "token-invalid");
        assertThat(current(accessToken(ana, institution, anasFamily, password, now()).compact())
                .statusCode()).as("non-vacuous: the live family of Ana opens it").isEqualTo(200);
    }

    private static JsonNode withoutTrace(JsonNode problem) {
        ObjectNode copy = ((ObjectNode) problem).deepCopy();
        copy.remove("traceId");
        copy.remove("instance");
        return copy;
    }

    // --- OR05 and OR10, over HTTP (task 2.2 proved them only in a unit) ---

    @Test
    void aTokenOfOneInstitutionWithTheSessionOfAnotherIsInvalid() {
        StaffAccountId ana = seedAccount(institution, ANA);
        StaffAccountId bea = seedAccount(otherInstitution, "bea.lopez@escuela.edu.hn");
        UUID familyOfB = seedLiveFamily(otherInstitution, bea, false);
        Set<AuthenticationMethod> password = Set.of(AuthenticationMethod.PASSWORD);

        HttpResponse<String> crossed = current(
                accessToken(bea, institution, familyOfB, password, now()).compact());
        HttpResponse<String> crossedWithTheAccountOfA = current(
                accessToken(ana, institution, familyOfB, password, now()).compact());
        HttpResponse<String> own = current(
                accessToken(bea, otherInstitution, familyOfB, password, now()).compact());

        assertProblem(crossed, 401, "token-invalid");
        assertProblem(crossedWithTheAccountOfA, 401, "token-invalid");
        assertThat(own.statusCode()).as("non-vacuous: under its own institution it opens")
                .isEqualTo(200);
    }

    @Test
    void theDatabaseContextIsTheInstitutionOfTheTokenUnderTheRealRestrictedRole() {
        StaffAccountId ana = seedAccount(institution, ANA);
        StaffAccountId bea = seedAccount(otherInstitution, "bea.lopez@escuela.edu.hn");
        UUID familyOfA = seedLiveFamily(institution, ana, false);
        UUID familyOfB = seedLiveFamily(otherInstitution, bea, false);
        Set<AuthenticationMethod> password = Set.of(AuthenticationMethod.PASSWORD);

        JsonNode forA = HarnessProcess.json(
                current(accessToken(ana, institution, familyOfA, password, now()).compact()));
        JsonNode forB = HarnessProcess.json(current(
                accessToken(bea, otherInstitution, familyOfB, password, now()).compact()));

        assertThat(new JdbcTemplate(process.getBean(DataSource.class))
                .queryForObject("select current_user", String.class))
                .as("the process connects as the least-privilege role, never as the owner")
                .isEqualTo("confia_admin_app");
        assertThat(forA.get("institutionId").asString()).isEqualTo(institution.value().toString());
        assertThat(forA.get("sessionId").asString()).isEqualTo(familyOfA.toString());
        assertThat(forB.get("institutionId").asString())
                .isEqualTo(otherInstitution.value().toString());
        assertThat(forB.get("sessionId").asString()).isEqualTo(familyOfB.toString());
    }

    // --- I134 ---

    @Test
    void tenSuccessfulReadsChangeNothingAndAuditNothing() {
        StaffAccountId ana = seedAccount(institution, ANA);
        UUID family = seedLiveFamily(institution, ana, true);
        String before = storedSessions();
        int auditBefore = auditEntries();
        AccessToken token = accessToken(ana, institution, family,
                Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD),
                now());

        for (int i = 0; i < 10; i++) {
            assertThat(current(token.compact()).statusCode()).isEqualTo(200);
        }

        assertThat(storedSessions()).as("last_used_at and every row of V8 are as they were")
                .isEqualTo(before);
        assertThat(auditEntries()).as("no audit entry").isEqualTo(auditBefore);
    }

    private String storedSessions() {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetch("""
                select row_to_json(f)::text from identity_refresh_token_family f
                union all
                select row_to_json(t)::text from identity_refresh_token t
                order by 1
                """).formatCSV());
    }

    private int auditEntries() {
        return transactionRunner().execute(contextOf(institution),
                () -> new JooqAuditLogReader(dsl).pageOf(institution, 0, 500).size());
    }

    // --- WE29 ---

    @Test
    void theProductionRoutesAreTheLoginTheCurrentSessionAndTheEnrollmentAndEveryOtherAnonymousRequestIs401() {
        assertThat(AdminProcessLauncher.routesOf(process))
                .containsExactlyInAnyOrder("POST /api/v1/auth/sessions",
                        "GET /api/v1/auth/sessions/current", "POST /api/v1/auth/mfa/enrollments");

        assertProblem(get("/api/v1/auth/sessions"), 401, "authentication-required");
        assertProblem(get("/api/v1/anything"), 401, "authentication-required");
        assertProblem(send("POST", ROUTE, "Origin", AdminProcessLauncher.ALLOWED_ORIGIN), 401,
                "authentication-required");
    }

    @Test
    void anotherMethodOnTheRouteIsForbiddenToAnAuthenticatedActor() {
        StaffAccountId ana = seedAccount(institution, ANA);
        UUID family = seedLiveFamily(institution, ana, false);
        String token = accessToken(ana, institution, family,
                Set.of(AuthenticationMethod.PASSWORD), now()).compact();

        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertProblem(send(method, ROUTE, "Authorization", "Bearer " + token, "Origin",
                    AdminProcessLauncher.ALLOWED_ORIGIN), 403,
                    "forbidden");
        }
    }
}

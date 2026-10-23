package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.token.MfaPurpose;
import com.confia.shared.security.token.MfaTokenClaims;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jooq.Record;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /api/v1/auth/sessions} on the real administrative process, against a real PostgreSQL
 * and the real Argon2id (session-endpoints-cookie-and-csrf design.md decisions 11 and 12; scenarios
 * I149 to I158, I228, WE87 and the unlabelled ones of "La cadena administrativa no tiene estado"
 * and "Catálogo de códigos"). The process is the production one with the loopback as a trusted
 * proxy, so each test chooses the client address of its requests and no test shares a bucket of the
 * limiter with another. Every account is seeded with SQL and the hash the process will check.
 */
class LoginEndpointIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String ANA = "ana.martinez@colegio.edu.hn";
    private static final String AGENT = "agente-prueba";
    private static final String SESSION_COOKIE = "__Secure-confia_admin_sid";
    private static final String CSRF_COOKIE = "__Host-confia_csrf";

    private static LoginProcess process;

    @BeforeAll
    static void startTheProcess() {
        process = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of());
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    // --- I149, WE62, WE87 ---

    @Test
    void correctCredentialsWithoutASecondFactorStartASessionWithExactlyTwoCookies() {
        StaffAccountId ana = seedAccount(ANA, false, false);
        String address = LoginProcess.freshAddress();
        long sessionsBefore = process.createdHttpSessions();

        HttpResponse<String> response = process.post(LoginProcess.jsonOf(ANA, PASSWORD),
                "X-Forwarded-For", address, "User-Agent", AGENT);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("status", "accessToken",
                "accessTokenExpiresAt", "sessionExpiresAt", "idleExpiresAt");
        assertThat(body.get("status").asString()).isEqualTo("authenticated");
        AuthenticatedActor actor = process.verifier().verifyAccess(body.get("accessToken").asString());
        assertThat(actor.accountId()).isEqualTo(ana.value());
        assertThat(actor.institutionId()).isEqualTo(INSTITUTION.value());
        assertThat(actor.methods()).containsExactly(AuthenticationMethod.PASSWORD);
        assertThat(Instant.parse(body.get("sessionExpiresAt").asString()))
                .isAfter(Instant.parse(body.get("idleExpiresAt").asString()));
        assertThat(response.body()).as("no personal datum and no session identifier")
                .doesNotContain(ANA).doesNotContain("Ana").doesNotContain(actor.sessionId().toString());
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");

        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).as("exactly the session cookie and the CSRF cookie").hasSize(2);
        String session = cookieNamed(cookies, SESSION_COOKIE);
        assertThat(session).contains("HttpOnly").contains("Secure").contains("SameSite=Strict")
                .contains("Path=/api/v1/auth/sessions").doesNotContain("Domain")
                .doesNotContain("Max-Age").doesNotContain("Expires");
        assertThat(valueOf(session)).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        String csrf = cookieNamed(cookies, CSRF_COOKIE);
        assertThat(csrf).contains("Secure").contains("SameSite=Strict").contains("Path=/")
                .doesNotContain("HttpOnly");
        assertThat(cookies).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
        assertThat(process.createdHttpSessions())
                .as("the container created no HTTP session (WE87)").isEqualTo(sessionsBefore);

        Record family = familyOf(actor.sessionId());
        assertThat(family.get("account_id", UUID.class)).isEqualTo(ana.value());
        assertThat(family.get("second_factor_verified", Boolean.class)).isFalse();
        assertThat(family.get("ip", String.class)).isEqualTo(address);
        assertThat(family.get("user_agent_fingerprint", String.class))
                .isEqualTo(UserAgentFingerprint.of(AGENT).value());
        assertThat(auditRows("identity.session.started")).singleElement()
                .satisfies(row -> assertThat(row.entityId()).isEqualTo(actor.sessionId().toString()));
    }

    // --- I150 and I151 ---

    @Test
    void anAccountWithAnEnrolledSecondFactorGetsAVerificationTokenAndNoCookie() {
        StaffAccountId sofia = seedAccount("sofia.mejia@colegio.edu.hn", true, true);
        long sessionsBefore = process.createdHttpSessions();

        HttpResponse<String> response = process.login(LoginProcess.freshAddress(),
                "sofia.mejia@colegio.edu.hn", PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("status", "secondFactorToken",
                "secondFactorTokenExpiresAt");
        assertThat(body.get("status").asString()).isEqualTo("second-factor-required");
        MfaTokenClaims claims = process.verifier().verifyMfa(body.get("secondFactorToken").asString());
        assertThat(claims.purpose()).isEqualTo(MfaPurpose.MFA_VERIFY);
        assertThat(claims.accountId()).isEqualTo(sofia.value());
        assertThat(Duration.between(claims.issuedAt(), claims.expiresAt()))
                .isEqualTo(Duration.ofSeconds(300));
        assertThat(Instant.parse(body.get("secondFactorTokenExpiresAt").asString()))
                .isEqualTo(claims.expiresAt());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(count("identity_refresh_token")).isZero();
        assertThat(process.createdHttpSessions()).isEqualTo(sessionsBefore);
    }

    @Test
    void anAccountThatMustEnrollGetsAnEnrollmentTokenAndNoCookie() {
        StaffAccountId jorge = seedAccount("jorge.aguilar@colegio.edu.hn", true, false);

        HttpResponse<String> response = process.login(LoginProcess.freshAddress(),
                "jorge.aguilar@colegio.edu.hn", PASSWORD);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("status").asString()).isEqualTo("second-factor-enrollment-required");
        MfaTokenClaims claims = process.verifier().verifyMfa(body.get("secondFactorToken").asString());
        assertThat(claims.purpose()).isEqualTo(MfaPurpose.MFA_ENROLL);
        assertThat(claims.accountId()).isEqualTo(jorge.value());
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(count("identity_refresh_token_family")).isZero();
    }

    // --- I152 ---

    @Test
    void aRejectedLoginIs401AuthenticationFailedWithTheBearerChallengeAndNothingIssued() {
        seedAccount(ANA, false, false);
        long sessionsBefore = process.createdHttpSessions();

        HttpResponse<String> response = process.login(LoginProcess.freshAddress(), ANA, "otra-clave-1");

        JsonNode problem = assertProblem(response, 401, "authentication-failed");
        assertThat(problem.get("title").asString()).isEqualTo("Credenciales inválidas");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.body()).doesNotContain("accessToken").doesNotContain("secondFactorToken");
        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(process.createdHttpSessions()).isEqualTo(sessionsBefore);
        assertThat(auditRows("identity.login.failed")).hasSize(1);
    }

    // --- I153 ---

    @Test
    void aBodyWithoutAnIdentifierOrWithAnEmptyPasswordOrAnOverlongFieldIs400NamingTheField() {
        seedAccount(ANA, false, false);
        String address = LoginProcess.freshAddress();
        String overlong = "x".repeat(321) + "@colegio.edu.hn";

        HttpResponse<String> noIdentifier = process.post("{\"password\":\"" + PASSWORD + "\"}",
                "X-Forwarded-For", address);
        HttpResponse<String> emptyPassword = process.post(LoginProcess.jsonOf(ANA, ""),
                "X-Forwarded-For", address);
        HttpResponse<String> overlongIdentifier = process.post(LoginProcess.jsonOf(overlong, PASSWORD),
                "X-Forwarded-For", address);
        HttpResponse<String> overlongPassword = process.post(
                LoginProcess.jsonOf(ANA, "p".repeat(1025)), "X-Forwarded-For", address);

        assertThat(violationFields(assertProblem(noIdentifier, 400, "validation-failed")))
                .containsExactly("identifier");
        assertThat(violationFields(assertProblem(emptyPassword, 400, "validation-failed")))
                .containsExactly("password");
        assertThat(violationFields(assertProblem(overlongIdentifier, 400, "validation-failed")))
                .containsExactly("identifier");
        assertThat(violationFields(assertProblem(overlongPassword, 400, "validation-failed")))
                .containsExactly("password");
        assertThat(everythingOf(overlongIdentifier)).doesNotContain(overlong);
        assertThat(everythingOf(overlongPassword)).doesNotContain("p".repeat(1025));
        assertThat(auditRows()).as("the use case was invoked zero times").isEmpty();
        assertThat(count("identity_login_backoff")).isZero();
    }

    @Test
    void aBodyWithAnUnknownPropertyIs400AndNeverEchoesIt() {
        seedAccount(ANA, false, false);

        HttpResponse<String> response = process.post("{\"identifier\":\"" + ANA
                + "\",\"password\":\"" + PASSWORD + "\",\"role\":\"propiedad-extra-zeta\"}",
                "X-Forwarded-For", LoginProcess.freshAddress());

        assertProblem(response, 400, "validation-failed");
        assertThat(everythingOf(response)).doesNotContain("propiedad-extra-zeta");
        assertThat(auditRows()).isEmpty();
    }

    @Test
    void aPasswordOfThreeCharactersReachesTheUseCaseBecauseTheMinimumIsNotImposedOnEntry() {
        seedAccount(ANA, false, false);

        HttpResponse<String> response = process.login(LoginProcess.freshAddress(), ANA, "abc");

        assertProblem(response, 401, "authentication-failed");
        assertThat(auditRows("identity.login.failed")).hasSize(1);
    }

    @Test
    void anIdentifierThatCannotBeNormalizedToAnIdentifierIsRejectedLikeAnyOtherAndNeverFails() {
        String address = LoginProcess.freshAddress();

        for (String identifier : List.of("ab", "   ab   ", "  ")) {
            HttpResponse<String> response = process.post(LoginProcess.jsonOf(identifier, PASSWORD),
                    "X-Forwarded-For", address);
            assertThat(response.statusCode()).as("identifier '%s'", identifier).isIn(400, 401);
        }
    }

    // --- I154 ---

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "application/x-www-form-urlencoded"})
    void aContentTypeThatIsNotJsonIs415AndTheUseCaseRunsZeroTimes(String contentType) {
        seedAccount(ANA, false, false);

        HttpResponse<String> response = process.post(LoginProcess.jsonOf(ANA, PASSWORD),
                "Content-Type", contentType, "X-Forwarded-For", LoginProcess.freshAddress());

        assertProblem(response, 415, "unsupported-media-type");
        assertThat(everythingOf(response)).doesNotContain(contentType.substring(contentType.indexOf('/') + 1)
                .toUpperCase());
        assertThat(auditRows()).isEmpty();
        assertThat(count("identity_refresh_token_family")).isZero();
    }

    // --- I155 ---

    @Test
    void theInstitutionOfTheConfigurationIsTheOnlyOneAndAHeaderThatNamesAnotherIsIgnored() {
        StaffAccountId mine = seedAccount(ANA, false, false);
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        seedAccountIn(other, ANA, "Otra clave larga 2026", false, false);

        HttpResponse<String> response = process.post(LoginProcess.jsonOf(ANA, PASSWORD),
                "X-Forwarded-For", LoginProcess.freshAddress(),
                "X-Institution-Id", other.value().toString());

        assertThat(response.statusCode()).isEqualTo(200);
        AuthenticatedActor actor = process.verifier().verifyAccess(
                json(response).get("accessToken").asString());
        assertThat(actor.accountId()).isEqualTo(mine.value());
        assertThat(actor.institutionId()).isEqualTo(INSTITUTION.value());
    }

    // --- I228 ---

    @Test
    void theLoginNeitherReadsNorRequiresTheCsrfTokenAndOverwritesAnEarlierCookieWithoutRevokingIt() {
        seedAccount(ANA, false, false);
        String address = LoginProcess.freshAddress();
        HttpResponse<String> first = process.login(address, ANA, PASSWORD);
        String earlier = cookieNamed(first.headers().allValues("Set-Cookie"), SESSION_COOKIE);
        UUID earlierFamily = process.verifier().verifyAccess(
                json(first).get("accessToken").asString()).sessionId();

        HttpResponse<String> second = process.post(LoginProcess.jsonOf(ANA, PASSWORD),
                "X-Forwarded-For", address, "Cookie", SESSION_COOKIE + "=" + valueOf(earlier));

        assertThat(second.statusCode()).as("no X-CSRF-Token and the request was not refused").isEqualTo(200);
        List<String> cookies = second.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(valueOf(cookieNamed(cookies, SESSION_COOKIE))).isNotEqualTo(valueOf(earlier));
        assertThat(familyOf(earlierFamily).get("revoked_at")).as("the earlier family is not revoked")
                .isNull();
    }

    // --- Origin and CSRF ---

    @Test
    void aRequestWithoutAnAllowedOriginIs403CsrfInvalidAndTheUseCaseRunsZeroTimes() {
        seedAccount(ANA, false, false);

        HttpResponse<String> missing = process.postRaw(LoginProcess.jsonOf(ANA, PASSWORD),
                "Content-Type", "application/json", "X-Forwarded-For", LoginProcess.freshAddress());
        HttpResponse<String> foreign = process.post(LoginProcess.jsonOf(ANA, PASSWORD),
                "Origin", "https://evil.example", "X-Forwarded-For", LoginProcess.freshAddress());

        assertProblem(missing, 403, "csrf-invalid");
        assertProblem(foreign, 403, "csrf-invalid");
        assertThat(auditRows()).isEmpty();
    }

    // --- I156 ---

    @Test
    void theWaitOfTheBackoffHappensAfterTheCommitAndDoesNotDenyACorrectPassword() throws Exception {
        seedAccount(ANA, false, false);
        seedBackoff(ANA, 3);
        String address = LoginProcess.freshAddress();
        long started = System.nanoTime();

        CompletableFuture<HttpResponse<String>> pending = CompletableFuture.supplyAsync(
                () -> process.login(address, ANA, PASSWORD));
        boolean committedDuringTheWait = holdsWithin(20_000,
                () -> !auditRows("identity.login.succeeded").isEmpty());
        boolean stillWaiting = !pending.isDone();
        HttpResponse<String> response = pending.get();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(committedDuringTheWait).as("the entry was committed and visible").isTrue();
        assertThat(stillWaiting).as("the answer was still held back when the commit was seen").isTrue();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(elapsed).as("held back for the delay of the fourth attempt, 2 seconds")
                .isGreaterThanOrEqualTo(Duration.ofMillis(1900));
    }

    // --- I157 and I158 ---

    @Test
    void anExistingAccountAndAMissingOneAnswerTheSameWithoutPriorFailures() {
        seedAccount(ANA, false, false);

        HttpResponse<String> existing = process.login(LoginProcess.freshAddress(), ANA, "incorrecta-1");
        HttpResponse<String> missing = process.login(LoginProcess.freshAddress(),
                "nadie.registrado@colegio.edu.hn", "incorrecta-1");

        assertProblem(existing, 401, "authentication-failed");
        assertThat(differences(existing, missing)).isEmpty();
    }

    @Test
    void theyAnswerTheSameAfterThreeFailuresAndWaitTheSameDelay() {
        seedAccount(ANA, false, false);
        String missingIdentifier = "nadie.registrado@colegio.edu.hn";
        HttpResponse<String> existing = null;
        HttpResponse<String> missing = null;
        Duration existingWait = null;
        Duration missingWait = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            long t0 = System.nanoTime();
            existing = process.login(LoginProcess.freshAddress(), ANA, "incorrecta-" + attempt);
            existingWait = Duration.ofNanos(System.nanoTime() - t0);
        }
        for (int attempt = 1; attempt <= 4; attempt++) {
            long t0 = System.nanoTime();
            missing = process.login(LoginProcess.freshAddress(), missingIdentifier, "incorrecta-" + attempt);
            missingWait = Duration.ofNanos(System.nanoTime() - t0);
        }

        assertProblem(existing, 401, "authentication-failed");
        assertThat(differences(existing, missing)).isEmpty();
        assertThat(existingWait).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
        assertThat(missingWait).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
        assertThat(highestConsecutiveFailures()).isEqualTo(4);
    }

    @Test
    void theComparisonFlagsAnyDifferenceOfStatusBodyOrHeaderExceptTraceIdAndDate() {
        seedAccount(ANA, false, false);
        HttpResponse<String> rejected = process.login(LoginProcess.freshAddress(), ANA, "incorrecta-1");
        HttpResponse<String> invalid = process.post("{}", "X-Forwarded-For", LoginProcess.freshAddress());

        assertThat(differences(rejected, invalid)).as("a different status and body").isNotEmpty();
        assertThat(differences(rejected, rejected)).isEmpty();

        ObjectNode alteredBody = (ObjectNode) json(rejected).deepCopy();
        alteredBody.put("detail", "otro detalle");
        assertThat(differences(describe(rejected.statusCode(), json(rejected), rejected.headers().map()),
                describe(rejected.statusCode(), alteredBody, rejected.headers().map())))
                .anyMatch(difference -> difference.contains("detail"));
        Map<String, List<String>> alteredHeaders = new TreeMap<>(rejected.headers().map());
        alteredHeaders.put("x-extra", List.of("1"));
        assertThat(differences(describe(rejected.statusCode(), json(rejected), rejected.headers().map()),
                describe(rejected.statusCode(), json(rejected), alteredHeaders)))
                .anyMatch(difference -> difference.contains("x-extra"));
        ObjectNode otherTrace = (ObjectNode) json(rejected).deepCopy();
        otherTrace.put("traceId", UUID.randomUUID().toString());
        Map<String, List<String>> otherDate = new TreeMap<>(rejected.headers().map());
        otherDate.put("date", List.of("Thu, 01 Jan 1970 00:00:00 GMT"));
        assertThat(differences(describe(rejected.statusCode(), json(rejected), rejected.headers().map()),
                describe(rejected.statusCode(), otherTrace, otherDate)))
                .as("traceId and Date are the only members allowed to differ").isEmpty();
    }

    // --- The stateless chain ---

    @Test
    void noAnswerOfTheRouteCreatesAnHttpSession() {
        seedAccount(ANA, false, false);
        long sessionsBefore = process.createdHttpSessions();
        String address = LoginProcess.freshAddress();

        List<HttpResponse<String>> answers = new ArrayList<>();
        answers.add(process.login(address, ANA, PASSWORD));
        answers.add(process.login(address, ANA, "incorrecta-1"));
        answers.add(process.post("{}", "X-Forwarded-For", address));
        answers.add(process.post(LoginProcess.jsonOf(ANA, PASSWORD), "Content-Type", "text/plain",
                "X-Forwarded-For", address));
        answers.add(process.postRaw(LoginProcess.jsonOf(ANA, PASSWORD), "Content-Type",
                "application/json"));

        assertThat(answers).extracting(HttpResponse::statusCode).containsExactly(200, 401, 400, 415, 403);
        assertThat(answers).allSatisfy(answer -> assertThat(answer.headers().allValues("Set-Cookie"))
                .noneMatch(cookie -> cookie.startsWith("JSESSIONID")));
        assertThat(process.createdHttpSessions()).isEqualTo(sessionsBefore);
        assertThat(answers.get(1).headers().allValues("Set-Cookie")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private Record familyOf(UUID familyId) {
        return transactionRunner().execute(contextOf(INSTITUTION), () -> dsl.fetchSingle("""
                select account_id, second_factor_verified, revoked_at, host(ip_first_seen) as ip,
                       user_agent_fingerprint
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, INSTITUTION.value(), familyId));
    }

    private void seedBackoff(String email, int consecutiveFailures) {
        JooqLoginBackoffStore store = new JooqLoginBackoffStore(dsl);
        Instant now = Instant.now();
        transactionRunner().execute(contextOf(INSTITUTION), () -> {
            store.claim(INSTITUTION, fingerprintOf(email), now);
            store.save(INSTITUTION, fingerprintOf(email), new BackoffState(consecutiveFailures, now));
            return null;
        });
    }

    private static String cookieNamed(List<String> cookies, String name) {
        return cookies.stream().filter(cookie -> cookie.startsWith(name + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("no Set-Cookie named " + name + " in " + cookies));
    }

    private static String valueOf(String setCookie) {
        String pair = setCookie.substring(0, setCookie.indexOf(';') < 0 ? setCookie.length()
                : setCookie.indexOf(';'));
        return pair.substring(pair.indexOf('=') + 1);
    }

    private static List<String> fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        return names;
    }

    private static List<String> violationFields(JsonNode problem) {
        List<String> fields = new ArrayList<>();
        problem.get("errors").forEach(error -> fields.add(error.get("field").asString()));
        return fields;
    }

    /** What a response is, for comparing two of them: status, body and headers, as ordered maps. */
    private record Described(int status, JsonNode body, Map<String, List<String>> headers) {
    }

    private static Described describe(int status, JsonNode body, Map<String, List<String>> headers) {
        return new Described(status, body, new TreeMap<>(headers));
    }

    private static List<String> differences(HttpResponse<String> a, HttpResponse<String> b) {
        return differences(describe(a.statusCode(), json(a), a.headers().map()),
                describe(b.statusCode(), json(b), b.headers().map()));
    }

    /** Every difference between two responses other than the trace identifier and the date. */
    private static List<String> differences(Described a, Described b) {
        List<String> differences = new ArrayList<>();
        if (a.status() != b.status()) {
            differences.add("status " + a.status() + " against " + b.status());
        }
        ObjectNode bodyA = (ObjectNode) a.body().deepCopy();
        ObjectNode bodyB = (ObjectNode) b.body().deepCopy();
        bodyA.remove("traceId");
        bodyB.remove("traceId");
        List<String> members = new ArrayList<>(fieldNames(bodyA));
        fieldNames(bodyB).stream().filter(name -> !members.contains(name)).forEach(members::add);
        for (String member : members) {
            if (!java.util.Objects.equals(bodyA.get(member), bodyB.get(member))) {
                differences.add("body member " + member);
            }
        }
        Map<String, List<String>> headersA = new TreeMap<>(a.headers());
        Map<String, List<String>> headersB = new TreeMap<>(b.headers());
        headersA.remove("date");
        headersB.remove("date");
        List<String> names = new ArrayList<>(headersA.keySet());
        headersB.keySet().stream().filter(name -> !names.contains(name)).forEach(names::add);
        for (String name : names) {
            if (!java.util.Objects.equals(headersA.get(name), headersB.get(name))) {
                differences.add("header " + name);
            }
        }
        return differences;
    }

    /** Polls {@code condition} until it holds or {@code limitMillis} pass; a condition, never a pause. */
    private static boolean holdsWithin(long limitMillis, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + limitMillis * 1_000_000L;
        java.util.concurrent.CountDownLatch never = new java.util.concurrent.CountDownLatch(1);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                never.await(5, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return condition.getAsBoolean();
    }
}

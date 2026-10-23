package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.csrf.CsrfTokens;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /api/v1/auth/sessions/refresh} on the real administrative process, against a real
 * PostgreSQL (session-endpoints-cookie-and-csrf design.md decisions 5, 14 and 18; scenarios I186,
 * I188, I189, I190, I219, I220, WE63, WE81, WE88 and WE92). The sessions are the ones the login
 * endpoint hands out; the families and tokens a login cannot produce (revoked, ended, consumed long
 * ago) are seeded relative to the clock of the process, so nothing here waits for a window. Each
 * test chooses the client address of its requests, so no test shares a bucket of the limiter.
 */
class SessionRefreshEndpointIT extends SessionEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String MADE_UP = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";

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

    // --- I186: the normal refresh ---

    @Test
    void aRefreshRotatesAndReissuesBothCookiesWithTheCsrfBoundToTheSuccessor() {
        Opened session = openSession(process);
        long sessionsBefore = process.createdHttpSessions();

        HttpResponse<String> response = refresh(process, session);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(fieldNames(body)).containsExactlyInAnyOrder("status", "accessToken",
                "accessTokenExpiresAt", "sessionExpiresAt", "idleExpiresAt");
        assertThat(body.get("status").asString()).isEqualTo("authenticated");
        assertThat(process.verifier().verifyAccess(body.get("accessToken").asString()).sessionId())
                .as("a new access token of the same family").isEqualTo(session.sessionId());
        assertThat(body.get("accessToken").asString()).isNotEqualTo(session.accessToken());
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(2);
        String successor = cookieValue(response, SESSION_COOKIE);
        String csrf = cookieValue(response, CSRF_COOKIE);
        assertThat(successor).hasSize(43).isNotEqualTo(session.refresh());
        assertThat(cookies.get(0)).contains("HttpOnly").contains("Secure")
                .contains("SameSite=Strict").contains("Path=/api/v1/auth/sessions")
                .doesNotContain("Max-Age").doesNotContain("Expires").doesNotContain("Domain");
        assertThat(cookies.get(1)).contains("Secure").contains("SameSite=Strict").contains("Path=/")
                .doesNotContain("HttpOnly").doesNotContain("Max-Age");
        CsrfTokens tokens = process.context().getBean(CsrfTokens.class);
        assertThat(tokens.verify(csrf, successor)).as("the CSRF is bound to the successor").isTrue();
        assertThat(tokens.verify(csrf, session.refresh())).as("and not to the consumed token")
                .isFalse();
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(process.createdHttpSessions()).as("no HTTP session was created")
                .isEqualTo(sessionsBefore);
        assertThat(auditRows("identity.session.refreshed")).hasSize(1);
        assertThat(everythingOf(response)).doesNotContain(session.refresh());
    }

    @Test
    void theSuccessorOfARefreshRefreshesAgainAndTheConsumedTokenIsNoLongerAccepted() {
        Opened session = openSession(process);
        HttpResponse<String> first = refresh(process, session);
        String successor = cookieValue(first, SESSION_COOKIE);
        String csrf = cookieValue(first, CSRF_COOKIE);

        HttpResponse<String> second = refresh(process, LoginProcess.freshAddress(), successor, csrf);

        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(cookieValue(second, SESSION_COOKIE)).isNotEqualTo(successor);
    }

    // --- I187, the sequential half: a retry inside the grace window ---

    @Test
    void aRetryFromTheSameAgentInsideTheGraceWindowIsServedTheSameSuccessorWithAFreshCsrf() {
        Opened session = openSession(process);
        String address = LoginProcess.freshAddress();

        HttpResponse<String> fresh = refresh(process, address, session.refresh(), session.csrf());
        HttpResponse<String> grace = refresh(process, address, session.refresh(), session.csrf());

        assertThat(fresh.statusCode()).isEqualTo(200);
        assertThat(grace.statusCode()).isEqualTo(200);
        String successor = cookieValue(fresh, SESSION_COOKIE);
        assertThat(cookieValue(grace, SESSION_COOKIE)).isEqualTo(successor);
        CsrfTokens tokens = process.context().getBean(CsrfTokens.class);
        assertThat(tokens.verify(cookieValue(grace, CSRF_COOKIE), successor)).isTrue();
        assertThat(grace.headers().allValues("Set-Cookie")).hasSize(2);
        assertThat(auditRows("identity.session.refreshed")).hasSize(2);
    }

    // --- I188 and WE63, WE88: every rejection is the same answer and clears the cookies ---

    @Test
    void everyRejectionIsTheSameSessionExpiredAnswerAndDeletesBothCookies() {
        UUID revokedFamily = seedFamily("3 hours", "5 minutes", "9 hours", "logout");
        String revoked = seedOpenToken(revokedFamily, "5 minutes", "8 hours");
        UUID endedFamily = seedFamily("14 hours", "5 minutes", "-2 hours", null);
        String ended = seedOpenToken(endedFamily, "3 hours", "-1 hour");
        UUID liveFamily = seedFamily("3 hours", "5 minutes", "9 hours", null);
        String tokenExpired = seedOpenToken(liveFamily, "2 hours", "-1 hour");
        UUID reusedFamily = seedFamily("3 hours", "5 minutes", "9 hours", null);
        String reused = seedConsumedToken(reusedFamily, "2 hours", "1 hour");
        List<String> tokens = List.of(MADE_UP, revoked, ended, tokenExpired, reused);
        List<HttpResponse<String>> answers = new ArrayList<>();

        for (String token : tokens) {
            answers.add(refresh(process, LoginProcess.freshAddress(), token,
                    csrfFor(process, token)));
        }

        for (HttpResponse<String> answer : answers) {
            assertProblem(answer, 401, "session-expired");
            assertBothCookiesCleared(answer);
            assertThat(answer.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
            assertThat(withoutVolatileParts(answer)).isEqualTo(withoutVolatileParts(answers.get(0)));
        }
        assertThat(familyRow(reusedFamily).get("revoked_reason", String.class))
                .as("the reuse keeps its revocation and its entry").isEqualTo("reuse_detected");
        assertThat(auditRows("identity.session.reuse_detected")).hasSize(1);
        assertThat(familyRow(liveFamily).get("revoked_at")).isNull();
        assertThat(everythingOf(answers.get(4))).doesNotContain(reused);
    }

    // --- I219: no session cookie ---

    @Test
    void withoutTheSessionCookieAnAllowedOriginAndNoCsrfTheAnswerIsTheSameSessionExpired() {
        HttpResponse<String> rejected = refresh(process, LoginProcess.freshAddress(), MADE_UP,
                csrfFor(process, MADE_UP));

        HttpResponse<String> response = call(process, "POST", REFRESH, LoginProcess.freshAddress());

        assertProblem(response, 401, "session-expired");
        assertBothCookiesCleared(response);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
        assertThat(withoutVolatileParts(response)).isEqualTo(withoutVolatileParts(rejected));
        assertThat(auditRows("identity.session.refreshed")).isEmpty();
    }

    @Test
    void withoutTheSessionCookieAndWithoutAnAllowedOriginTheAnswerIs403() {
        Map<String, String> noOrigin = Map.of("X-Forwarded-For", LoginProcess.freshAddress());
        Map<String, String> foreign = Map.of("X-Forwarded-For", LoginProcess.freshAddress(),
                "Origin", "https://evil.example");

        assertProblem(process.send("POST", REFRESH, null, noOrigin), 403, "csrf-invalid");
        assertProblem(process.send("POST", REFRESH, null, foreign), 403, "csrf-invalid");
    }

    // --- I189, I220: what never reaches the use case ---

    @Test
    void aSessionCookieWithoutTheCsrfHeaderOrWithoutAnOriginIs403AndTheTokenIsUntouched() {
        Opened session = openSession(process);
        String address = LoginProcess.freshAddress();

        HttpResponse<String> noHeader = call(process, "POST", REFRESH, address, "Cookie",
                cookieHeader(session.refresh(), session.csrf()));
        HttpResponse<String> noOrigin = process.send("POST", REFRESH, null, Map.of(
                "X-Forwarded-For", address, "Cookie", cookieHeader(session.refresh(), session.csrf()),
                "X-CSRF-Token", session.csrf()));

        assertProblem(noHeader, 403, "csrf-invalid");
        assertProblem(noOrigin, 403, "csrf-invalid");
        assertThat(noHeader.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(noOrigin.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(auditRows("identity.session.refreshed")).as("the use case ran zero times").isEmpty();
        assertThat(refresh(process, session).statusCode()).as("the token is still open").isEqualTo(200);
    }

    @Test
    void twoSessionCookiesAreRefusedWithoutChoosingOne() {
        Opened session = openSession(process);
        String twoCookies = SESSION_COOKIE + "=" + session.refresh() + "; " + SESSION_COOKIE + "="
                + MADE_UP + "; " + CSRF_COOKIE + "=" + session.csrf();

        HttpResponse<String> response = call(process, "POST", REFRESH, LoginProcess.freshAddress(),
                "Cookie", twoCookies, "X-CSRF-Token", session.csrf());

        assertProblem(response, 403, "csrf-invalid");
        assertThat(auditRows("identity.session.refreshed")).isEmpty();
        assertThat(refresh(process, session).statusCode()).isEqualTo(200);
    }

    // --- I190 over HTTP: the binding ---

    @Test
    void anAgentAndAnAddressThatBothChangedAreRefusedAsSessionExpiredAndTheFamilyIsRevoked() {
        String email = "vinculo." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(email, false, false);
        String home = LoginProcess.freshAddress();
        HttpResponse<String> login = process.post(LoginProcess.jsonOf(email, PASSWORD),
                "User-Agent", "agente-uno", "X-Forwarded-For", home);
        UUID family = process.verifier().verifyAccess(json(login).get("accessToken").asString())
                .sessionId();
        String refresh = cookieValue(login, SESSION_COOKIE);
        String csrf = cookieValue(login, CSRF_COOKIE);

        HttpResponse<String> response = call(process, "POST", REFRESH, LoginProcess.freshAddress(),
                "User-Agent", "agente-dos", "Cookie", cookieHeader(refresh, csrf), "X-CSRF-Token", csrf);

        assertProblem(response, 401, "session-expired");
        assertBothCookiesCleared(response);
        assertThat(familyRow(family).get("revoked_reason", String.class))
                .isEqualTo("binding_changed");
        assertThat(auditRows("identity.session.binding_changed")).hasSize(1);
    }

    // --- WE92: the thirtieth rejection ---

    @Test
    void theThirtiethRejectionOfAnAddressRestrictsItAndTwentyNineDoNot() {
        String address = LoginProcess.freshAddress();
        Opened session = openSession(process, address);
        for (int rejection = 1; rejection <= 29; rejection++) {
            assertProblem(refresh(process, address, MADE_UP, csrfFor(process, MADE_UP)), 401,
                    "session-expired");
        }

        HttpResponse<String> correct = refresh(process, address, session.refresh(), session.csrf());
        assertThat(correct.statusCode()).as("with 29 rejections a correct refresh is served")
                .isEqualTo(200);

        String restricted = LoginProcess.freshAddress();
        for (int rejection = 1; rejection <= 30; rejection++) {
            assertProblem(refresh(process, restricted, MADE_UP, csrfFor(process, MADE_UP)), 401,
                    "session-expired");
        }
        HttpResponse<String> next = refresh(process, restricted, MADE_UP, csrfFor(process, MADE_UP));

        assertProblem(next, 429, "too-many-requests");
        assertThat(next.headers().firstValue("Retry-After")).isPresent();
        assertThat(next.headers().allValues("Set-Cookie")).as("the limiter answers before the handler")
                .isEmpty();
    }

    // --- WE81: other methods ---

    @Test
    void otherMethodsOnTheSessionRoutesAreNotServed() {
        for (String path : List.of(REFRESH, ALL, "/api/v1/auth/mfa/verifications")) {
            for (String method : List.of("GET", "PUT", "PATCH")) {
                HttpResponse<String> response = call(process, method, path,
                        LoginProcess.freshAddress());

                assertThat(response.statusCode() / 100).as("%s %s", method, path).isNotEqualTo(2);
            }
        }
        assertThat(auditRows("identity.session.refreshed")).isEmpty();
        assertThat(auditRows("identity.session.refresh_rejected")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private static List<String> fieldNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.propertyNames().forEach(names::add);
        return names;
    }

    /** Status, body and headers of an answer without what is meant to differ: the trace and the date. */
    private static Map<String, Object> withoutVolatileParts(HttpResponse<String> response) {
        ObjectNode body = (ObjectNode) json(response);
        body.remove("traceId");
        body.remove("instance");
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(response.headers().map());
        headers.remove("date");
        headers.remove("x-request-id");
        return Map.of("status", response.statusCode(), "body", body.toString(), "headers",
                headers.toString());
    }
}

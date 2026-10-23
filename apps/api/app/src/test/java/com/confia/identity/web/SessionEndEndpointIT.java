package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code DELETE /api/v1/auth/sessions/current} and {@code DELETE /api/v1/auth/sessions} on the real
 * administrative process, against a real PostgreSQL (session-endpoints-cookie-and-csrf design.md
 * decision 15; scenarios I194 to I197, I199, I200 to I202, I227, WE20, WE51 and the third clause of
 * WE92). The current session is closed with its cookie, with no access token at all (the access
 * token of a client whose access expired is exactly what it does not have); every session is closed
 * with the bearer token and needs no CSRF token, only an allowed {@code Origin}. Each test chooses
 * the client address of its requests.
 */
class SessionEndEndpointIT extends SessionEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String CURRENT_SESSION = "/api/v1/auth/sessions/current";
    private static final String MADE_UP = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";
    private static final String REVOKED = "identity.session.revoked";

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

    // --- I194: the current session ---

    @Test
    void closingTheCurrentSessionWithoutAnAccessTokenAnswers204DeletesTheCookiesAndRevokesWithLogout() {
        Opened session = openSession(process);

        HttpResponse<String> response = close(session.refresh(), session.csrf());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertBothCookiesCleared(response);
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(familyRow(session.sessionId()).get("revoked_reason", String.class))
                .isEqualTo("logout");
        assertThat(auditRows(REVOKED)).singleElement().satisfies(row ->
                assertThat(JSON.readTree(row.afterValue())).isEqualTo(
                        JSON.readTree("{\"reason\":\"logout\",\"families\":1}")));
        assertThat(refresh(process, session).statusCode()).as("the closed session cannot refresh")
                .isEqualTo(401);
        assertThat(everythingOf(response)).doesNotContain(session.refresh());
    }

    // --- I195: a consumed token closes the family as well ---

    @Test
    void theTokenOfARotationThatAlreadyHappenedClosesTheFamilyToo() {
        Opened session = openSession(process);
        assertThat(refresh(process, session).statusCode()).isEqualTo(200);

        HttpResponse<String> response = close(session.refresh(), session.csrf());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(familyRow(session.sessionId()).get("revoked_reason", String.class))
                .isEqualTo("logout");
    }

    // --- I196: uniform whatever the token ---

    @Test
    void anUnknownTokenAndAFamilyRevokedByReuseAnswerTheSameAndTheRevocationIsUntouched() {
        UUID reusedFamily = seedFamily("5 hours", "2 hours", "7 hours", "reuse_detected");
        String reused = seedOpenToken(reusedFamily, "5 hours", "3 hours");
        Object revokedAt = familyRow(reusedFamily).get("revoked_at");

        HttpResponse<String> unknown = close(MADE_UP, csrfFor(process, MADE_UP));
        HttpResponse<String> revoked = close(reused, csrfFor(process, reused));
        HttpResponse<String> malformed = close("not a token", csrfFor(process, "not a token"));

        for (HttpResponse<String> answer : List.of(unknown, revoked, malformed)) {
            assertThat(answer.statusCode()).isEqualTo(204);
            assertBothCookiesCleared(answer);
            assertThat(visible(answer)).isEqualTo(visible(unknown));
        }
        assertThat(familyRow(reusedFamily).get("revoked_reason", String.class))
                .isEqualTo("reuse_detected");
        assertThat(familyRow(reusedFamily).get("revoked_at")).isEqualTo(revokedAt);
        assertThat(auditRows(REVOKED)).as("no entry of a closing that closed nothing").isEmpty();
    }

    // --- I197: only the current session ---

    @Test
    void closingOneSessionByCookieLeavesTheOtherSessionOfTheAccountAlive() {
        String email = "dos." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(email, false, false);
        Opened first = loginAs(email);
        Opened second = loginAs(email);

        assertThat(close(first.refresh(), first.csrf()).statusCode()).isEqualTo(204);

        assertThat(familyRow(second.sessionId()).get("revoked_at")).isNull();
        assertThat(refresh(process, second).statusCode()).isEqualTo(200);
    }

    // --- I227 and WE20: no session cookie ---

    @Test
    void withoutTheSessionCookieTheClosingAnswers204WithNoEffectAndNoEntry() {
        HttpResponse<String> response = call(process, "DELETE", CURRENT_SESSION,
                LoginProcess.freshAddress());

        assertThat(response.statusCode()).isEqualTo(204);
        assertBothCookiesCleared(response);
        assertThat(auditRows(REVOKED)).isEmpty();
    }

    @Test
    void withoutAnAllowedOriginTheClosingIs403WithOrWithoutTheSessionCookie() {
        Opened session = openSession(process);
        String address = LoginProcess.freshAddress();

        HttpResponse<String> noCookie = process.send("DELETE", CURRENT_SESSION, null,
                Map.of("X-Forwarded-For", address));
        HttpResponse<String> foreign = process.send("DELETE", CURRENT_SESSION, null, Map.of(
                "X-Forwarded-For", address, "Origin", "https://evil.example", "Cookie",
                cookieHeader(session.refresh(), session.csrf()), "X-CSRF-Token", session.csrf()));

        assertProblem(noCookie, 403, "csrf-invalid");
        assertProblem(foreign, 403, "csrf-invalid");
        assertThat(familyRow(session.sessionId()).get("revoked_at")).isNull();
    }

    // --- I199: the cookie without the CSRF header ---

    @Test
    void withTheSessionCookieAndNoCsrfHeaderTheClosingIs403AndTheFamilyStaysAlive() {
        Opened session = openSession(process);

        HttpResponse<String> response = call(process, "DELETE", CURRENT_SESSION,
                LoginProcess.freshAddress(), "Cookie", cookieHeader(session.refresh(), session.csrf()));

        assertProblem(response, 403, "csrf-invalid");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(familyRow(session.sessionId()).get("revoked_at")).isNull();
        assertThat(auditRows(REVOKED)).isEmpty();
    }

    // --- I200, I201, WE51: closing every session ---

    @Test
    void closingEverySessionWithTheBearerTokenNeedsNoCsrfRevokesTheWholeAccountAndDeletesTheCookies() {
        String email = "todas." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(email, false, false);
        Opened one = loginAs(email);
        Opened two = loginAs(email);
        Opened three = loginAs(email);
        Opened other = openSession(process);

        HttpResponse<String> response = closeAll(one.accessToken(), LoginProcess.freshAddress());

        assertThat(response.statusCode()).isEqualTo(204);
        assertBothCookiesCleared(response);
        for (Opened closed : List.of(one, two, three)) {
            assertThat(familyRow(closed.sessionId()).get("revoked_reason", String.class))
                    .isEqualTo("logout");
        }
        assertThat(familyRow(other.sessionId()).get("revoked_at")).as("another account").isNull();
        assertThat(auditRows(REVOKED)).singleElement().satisfies(row ->
                assertThat(JSON.readTree(row.afterValue())).isEqualTo(
                        JSON.readTree("{\"reason\":\"logout\",\"families\":3}")));
        assertProblem(closeAll(one.accessToken(), LoginProcess.freshAddress()), 401, "token-invalid");
        assertProblem(closeAll(two.accessToken(), LoginProcess.freshAddress()), 401, "token-invalid");
        assertThat(refresh(process, other).statusCode()).isEqualTo(200);
    }

    @Test
    void closingEverySessionWithoutAnAllowedOriginIs403AndNoFamilyChanges() {
        Opened session = openSession(process);

        HttpResponse<String> noOrigin = process.send("DELETE", ALL, null, Map.of(
                "X-Forwarded-For", LoginProcess.freshAddress(), "Authorization",
                "Bearer " + session.accessToken()));
        HttpResponse<String> foreign = process.send("DELETE", ALL, null, Map.of(
                "X-Forwarded-For", LoginProcess.freshAddress(), "Authorization",
                "Bearer " + session.accessToken(), "Origin", "https://evil.example"));

        assertProblem(noOrigin, 403, "csrf-invalid");
        assertProblem(foreign, 403, "csrf-invalid");
        assertThat(familyRow(session.sessionId()).get("revoked_at")).isNull();
        assertThat(auditRows(REVOKED)).isEmpty();
    }

    @Test
    void closingEverySessionWithoutACredentialIsTheUniform401() {
        assertProblem(call(process, "DELETE", ALL, LoginProcess.freshAddress()), 401,
                "authentication-required");
    }

    // --- WE92, third clause: closings are not failures ---

    @Test
    void closingsDoNotCountAsFailuresOfTheAddress() {
        String address = LoginProcess.freshAddress();
        Opened session = openSession(process, address);
        for (int rejection = 1; rejection <= 29; rejection++) {
            assertProblem(refresh(process, address, MADE_UP, csrfFor(process, MADE_UP)), 401,
                    "session-expired");
        }
        for (int closing = 1; closing <= 10; closing++) {
            assertThat(call(process, "DELETE", CURRENT_SESSION, address).statusCode())
                    .isEqualTo(204);
        }

        HttpResponse<String> correct = refresh(process, address, session.refresh(), session.csrf());

        assertThat(correct.statusCode()).as("29 rejections and ten closings: not restricted")
                .isEqualTo(200);
    }

    // ---------------------------------------------------------------------------------------------

    private HttpResponse<String> close(String refresh, String csrf) {
        return call(process, "DELETE", CURRENT_SESSION, LoginProcess.freshAddress(), "Cookie",
                cookieHeader(refresh, csrf), "X-CSRF-Token", csrf);
    }

    private HttpResponse<String> closeAll(String accessToken, String address) {
        return call(process, "DELETE", ALL, address, "Authorization", "Bearer " + accessToken);
    }

    private Opened loginAs(String email) {
        HttpResponse<String> response = process.login(LoginProcess.freshAddress(), email, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        String access = json(response).get("accessToken").asString();
        return new Opened(access, process.verifier().verifyAccess(access).sessionId(),
                cookieValue(response, SESSION_COOKIE), cookieValue(response, CSRF_COOKIE));
    }

    /** Status, body and headers without the date, to compare two answers that must be the same. */
    private static Map<String, Object> visible(HttpResponse<String> response) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.putAll(response.headers().map());
        headers.remove("date");
        return Map.of("status", response.statusCode(), "body", response.body(), "headers",
                headers.toString());
    }
}

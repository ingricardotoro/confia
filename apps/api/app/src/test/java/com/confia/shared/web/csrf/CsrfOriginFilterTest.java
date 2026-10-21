package com.confia.shared.web.csrf;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.csrf.CsrfKeys;
import com.confia.shared.security.csrf.CsrfTokens;
import com.confia.shared.web.harness.HarnessProcess;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, scenarios WE43 to WE50, WE53, WE55 and WE89 to WE91, and the hard condition 3
 * (session-endpoints-cookie-and-csrf design.md, decision 2): the {@code Origin} rule and the
 * double-submit token with its HMAC bound to the refresh token, against the real administrative
 * chain behind the database-free harness. The harness controller emits and reads the cookies; no
 * production class emits one yet. Every rejection must be a {@code 403 csrf-invalid} and the
 * stand-in use case behind the route must record zero invocations.
 */
class CsrfOriginFilterTest {

    private static final String SESSION_COOKIE = "__Secure-confia_admin_sid";
    private static final String CSRF_COOKIE = "__Host-confia_csrf";
    private static final String ORIGIN = HarnessProcess.ALLOWED_ORIGIN;
    private static final String REFRESH = "/test/cookie/refresh";
    private static final String CURRENT = "/test/cookie/current";
    private static final String OTHER = "/test/cookie/other";

    private static final byte[] CURRENT_KEY = newKey();
    private static final byte[] PREVIOUS_KEY = newKey();
    private static final CsrfTokens CURRENT_TOKENS = new CsrfTokens(CsrfKeys.of(CURRENT_KEY, null));

    private static HarnessProcess process;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start(HarnessProcess.CSRF_KEY_PROPERTY + "=" + base64(CURRENT_KEY));
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    // ---- condition 3 --------------------------------------------------------------------------

    @Test
    void conditionThreeTheThreeAttacksOnTheCookieHarnessAreRefused() {
        assertConditionThree(process);
    }

    /**
     * WE55: the same assertions against the chain with the CSRF filter left out must fail, so the
     * condition does not pass for lack of anything to check.
     */
    @Test
    void theControlVariantWithTheFilterDisabledFailsTheConditionThreeAssertions() {
        try (HarnessProcess control = HarnessProcess.start("harness.csrf-filter=off")) {
            assertThatThrownBy(() -> assertConditionThree(control)).isInstanceOf(AssertionError.class);
        }
    }

    private static void assertConditionThree(HarnessProcess target) {
        Cookies mine = issueFrom(target);
        Cookies someoneElses = issueFrom(target);
        int before = target.calls().cookieRouteInvocations();

        // (a) the cookies and no X-CSRF-Token
        assertProblem(target.send("POST", REFRESH, "Origin", ORIGIN, "Cookie", mine.header()), 403,
                "csrf-invalid");
        // (b) a valid token of another session in the header and in the cookie
        assertProblem(target.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token",
                someoneElses.csrf(), "Cookie", cookieHeader(mine.sid(), someoneElses.csrf())), 403,
                "csrf-invalid");
        // (c) no Origin at all, with everything else valid
        assertProblem(target.send("POST", REFRESH, "X-CSRF-Token", mine.csrf(), "Cookie",
                mine.header()), 403, "csrf-invalid");

        assertThat(target.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    // ---- WE43 to WE46: Origin -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void anUnsafeMethodWithoutOriginIsRefused(String method) {
        int before = process.calls().cookieRouteInvocations();

        assertProblem(process.send(method, OTHER), 403, "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void anUnsafeMethodWithTheAllowedOriginReachesTheController(String method) {
        int before = process.calls().cookieRouteInvocations();

        assertThat(process.send(method, OTHER, "Origin", ORIGIN).statusCode()).isEqualTo(200);

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before + 1);
    }

    @Test
    void aForeignLookalikeNullOrInsecureOriginIsRefusedWithIdenticalAnswers() {
        int before = process.calls().cookieRouteInvocations();
        List<HttpResponse<String>> answers = new ArrayList<>();
        answers.add(process.send("POST", OTHER, "Origin", "https://evil.example"));
        answers.add(process.send("POST", OTHER, "Origin", "http://admin.colegio.edu.hn"));
        answers.add(process.send("POST", OTHER, "Origin", ORIGIN + ".evil.example"));
        answers.add(process.send("POST", OTHER, "Origin", "null"));
        answers.add(process.send("POST", OTHER, "Origin", ORIGIN, "Origin", ORIGIN));
        answers.add(process.send("POST", OTHER, "Origin", ORIGIN.toUpperCase()));
        answers.add(process.send("POST", OTHER, "Origin", ORIGIN + "/"));
        answers.add(process.send("POST", OTHER, "Origin", ""));

        JsonNode first = assertProblem(answers.get(0), 403, "csrf-invalid");
        for (HttpResponse<String> answer : answers) {
            JsonNode body = assertProblem(answer, 403, "csrf-invalid");
            for (String field : new String[] {"type", "title", "status", "detail"}) {
                assertThat(body.get(field)).as(field).isEqualTo(first.get(field));
            }
        }
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void aSafeMethodNeedsNoOrigin() {
        assertThat(process.get("/test/cookie/issue").statusCode()).isEqualTo(200);
        assertThat(process.send("HEAD", "/test/cookie/issue").statusCode()).isNotEqualTo(403);
        assertThat(process.send("OPTIONS", "/test/cookie/issue").statusCode()).isNotEqualTo(403);
    }

    @Test
    void anEmptyAllowedOriginsListRefusesEveryUnsafeRequest() {
        try (HarnessProcess closed = HarnessProcess.start(
                HarnessProcess.ALLOWED_ORIGINS_PROPERTY + "=")) {
            assertProblem(closed.send("POST", OTHER, "Origin", ORIGIN), 403, "csrf-invalid");
            assertProblem(closed.send("POST", OTHER, "Origin", "null"), 403, "csrf-invalid");
            assertProblem(closed.send("POST", OTHER), 403, "csrf-invalid");
            assertThat(closed.get("/test/cookie/issue").statusCode()).isEqualTo(200);
            assertThat(closed.calls().cookieRouteInvocations()).isZero();
        }
    }

    // ---- WE47 to WE50: the double-submit token -----------------------------------------------

    @Test
    void validCookiesAndAMatchingHeaderReachTheController() {
        Cookies mine = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        HttpResponse<String> refresh = process.send("POST", REFRESH, "Origin", ORIGIN,
                "X-CSRF-Token", mine.csrf(), "Cookie", mine.header());
        HttpResponse<String> end = process.send("DELETE", CURRENT, "Origin", ORIGIN,
                "X-CSRF-Token", mine.csrf(), "Cookie", mine.header());

        assertThat(refresh.statusCode()).isEqualTo(200);
        assertThat(refresh.body()).isEqualTo("refreshed:1:1");
        assertThat(end.statusCode()).isEqualTo(200);
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before + 2);
    }

    @Test
    void theCookieHarnessIssuesTheTwoCookiesWithTheirExactNames() {
        HttpResponse<String> issued = process.get("/test/cookie/issue");

        List<String> setCookies = issued.headers().allValues("Set-Cookie");
        assertThat(setCookies).hasSize(2);
        assertThat(setCookies.get(0)).startsWith(SESSION_COOKIE + "=");
        assertThat(setCookies.get(1)).startsWith(CSRF_COOKIE + "=");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST " + REFRESH, "DELETE " + CURRENT})
    void theCookiesWithoutTheHeaderAreRefused(String route) {
        Cookies mine = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        assertProblem(send(route, "Origin", ORIGIN, "Cookie", mine.header()), 403, "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void aValidTokenOfAnotherSessionIsRefused() {
        Cookies a = issueFrom(process);
        Cookies b = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", a.csrf(),
                "Cookie", cookieHeader(b.sid(), a.csrf())), 403, "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void aHeaderDifferentFromTheCsrfCookieIsRefusedEvenIfBothAreValidForTheSession() {
        Cookies mine = issueFrom(process);
        String second = CURRENT_TOKENS.issue(mine.sid());
        int before = process.calls().cookieRouteInvocations();

        assertThat(second).isNotEqualTo(mine.csrf());
        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", second,
                "Cookie", mine.header()), 403, "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void anAlteredFirstByteLastByteOrRandomPartIsRefusedWithIdenticalAnswers() {
        Cookies mine = issueFrom(process);
        String token = mine.csrf();
        String[] parts = token.split("\\.");
        byte[] mac = Base64.getUrlDecoder().decode(parts[1]);
        byte[] firstByte = mac.clone();
        firstByte[0] ^= 1;
        byte[] lastByte = mac.clone();
        lastByte[mac.length - 1] ^= 1;
        byte[] random = Base64.getUrlDecoder().decode(parts[0]);
        random[0] ^= 1;
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        List<String> altered = List.of(
                parts[0] + "." + encoder.encodeToString(firstByte),
                parts[0] + "." + encoder.encodeToString(lastByte),
                encoder.encodeToString(random) + "." + parts[1]);
        int before = process.calls().cookieRouteInvocations();

        JsonNode reference = null;
        for (String forged : altered) {
            HttpResponse<String> answer = process.send("POST", REFRESH, "Origin", ORIGIN,
                    "X-CSRF-Token", forged, "Cookie", cookieHeader(mine.sid(), forged));
            JsonNode body = assertProblem(answer, 403, "csrf-invalid");
            reference = reference == null ? body : reference;
            assertThat(body.get("detail")).isEqualTo(reference.get("detail"));
        }
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void aCsrfCookieWithoutAnyHeaderOrAHeaderWithoutTheCsrfCookieIsRefused() {
        Cookies mine = issueFrom(process);

        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", mine.csrf(),
                "Cookie", SESSION_COOKIE + "=" + mine.sid()), 403, "csrf-invalid");
        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", "",
                "Cookie", mine.header()), 403, "csrf-invalid");
        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", mine.csrf(),
                "Cookie", SESSION_COOKIE + "=" + mine.sid() + "; " + CSRF_COOKIE + "="), 403,
                "csrf-invalid");
    }

    // ---- WE89: a cookie route without a session cookie ------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"POST " + REFRESH, "DELETE " + CURRENT})
    void aCookieRouteWithoutASessionCookieNeedsNoTokenButNeedsOrigin(String route) {
        int before = process.calls().cookieRouteInvocations();

        assertThat(send(route, "Origin", ORIGIN).statusCode()).isEqualTo(200);
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before + 1);
        assertProblem(send(route), 403, "csrf-invalid");
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before + 1);
    }

    // ---- WE90: duplicated credentials --------------------------------------------------------------

    @Test
    void twoSessionCookiesAreRefusedEvenWithAValidTokenForOneOfThem() {
        Cookies mine = issueFrom(process);
        Cookies other = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", mine.csrf(),
                "Cookie", SESSION_COOKIE + "=" + other.sid() + "; " + SESSION_COOKIE + "="
                        + mine.sid() + "; " + CSRF_COOKIE + "=" + mine.csrf()), 403, "csrf-invalid");
        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", mine.csrf(),
                "Cookie", SESSION_COOKIE + "=" + mine.sid() + "; " + SESSION_COOKIE + "="
                        + other.sid() + "; " + CSRF_COOKIE + "=" + mine.csrf()), 403, "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void twoSessionCookiesInSeparateCookieHeadersAreRefusedToo() {
        Cookies mine = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", mine.csrf(),
                "Cookie", SESSION_COOKIE + "=" + mine.sid(), "Cookie", SESSION_COOKIE + "="
                        + mine.sid(), "Cookie", CSRF_COOKIE + "=" + mine.csrf()), 403,
                "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void twoCsrfCookiesAreRefused() {
        Cookies mine = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", mine.csrf(),
                "Cookie", mine.header() + "; " + CSRF_COOKIE + "=" + mine.csrf()), 403,
                "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void twoTokenHeadersAreRefused() {
        Cookies mine = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", mine.csrf(),
                "X-CSRF-Token", mine.csrf(), "Cookie", mine.header()), 403, "csrf-invalid");

        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    @Test
    void aCookieOfAnotherNameOrAnotherCaseIsNotTheSessionCookie() {
        int before = process.calls().cookieRouteInvocations();

        // Neither is the session cookie, so this is a cookie route without one: it needs no token.
        assertThat(process.send("POST", REFRESH, "Origin", ORIGIN, "Cookie",
                "x" + SESSION_COOKIE + "=abc; " + SESSION_COOKIE.toLowerCase() + "x=abc")
                .statusCode()).isEqualTo(200);
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before + 1);
    }

    @Test
    void aBearerRouteIsNotACookieRoute() {
        Cookies mine = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        // The token is not asked for on a route that is not a cookie route, even with the cookie.
        assertThat(process.send("POST", OTHER, "Origin", ORIGIN, "Cookie", mine.header())
                .statusCode()).isEqualTo(200);
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before + 1);
    }

    @Test
    void aCookieRouteIsMatchedByMethodAndPath() {
        Cookies mine = issueFrom(process);
        int before = process.calls().cookieRouteInvocations();

        // DELETE on the refresh path is not the cookie route (the route is POST); PUT likewise.
        assertThat(process.send("DELETE", REFRESH, "Origin", ORIGIN, "Cookie", mine.header())
                .statusCode()).isNotEqualTo(403);
        assertThat(process.calls().cookieRouteInvocations()).isEqualTo(before);
    }

    // ---- WE91: the previous key ---------------------------------------------------------------------

    @Test
    void aTokenIssuedWithThePreviousKeyIsAcceptedAndOneOfAStrangerKeyIsRefused() {
        String sid = "sid-" + base64(newKey());
        String oldToken = new CsrfTokens(CsrfKeys.of(PREVIOUS_KEY, null)).issue(sid);
        String strangerToken = new CsrfTokens(CsrfKeys.of(newKey(), null)).issue(sid);
        try (HarnessProcess rotated = HarnessProcess.start(
                HarnessProcess.CSRF_KEY_PROPERTY + "=" + base64(CURRENT_KEY),
                "confia.security.csrf.previous-key=" + base64(PREVIOUS_KEY))) {
            assertThat(rotated.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", oldToken,
                    "Cookie", cookieHeader(sid, oldToken)).statusCode()).isEqualTo(200);
            assertProblem(rotated.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token",
                    strangerToken, "Cookie", cookieHeader(sid, strangerToken)), 403,
                    "csrf-invalid");
        }
    }

    @Test
    void withoutAPreviousKeyATokenOfTheOldKeyIsRefused() {
        String sid = "sid-" + base64(newKey());
        String oldToken = new CsrfTokens(CsrfKeys.of(PREVIOUS_KEY, null)).issue(sid);

        assertProblem(process.send("POST", REFRESH, "Origin", ORIGIN, "X-CSRF-Token", oldToken,
                "Cookie", cookieHeader(sid, oldToken)), 403, "csrf-invalid");
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static HttpResponse<String> send(String route, String... headers) {
        String[] parts = route.split(" ");
        return process.send(parts[0], parts[1], headers);
    }

    private static Cookies issueFrom(HarnessProcess target) {
        HttpResponse<String> issued = target.get("/test/cookie/issue");
        assertThat(issued.statusCode()).isEqualTo(200);
        Map<String, String> values = new java.util.HashMap<>();
        for (String setCookie : issued.headers().allValues("Set-Cookie")) {
            String pair = setCookie.substring(0, setCookie.indexOf(';'));
            values.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
        }
        return new Cookies(values.get(SESSION_COOKIE), values.get(CSRF_COOKIE));
    }

    private static String cookieHeader(String sid, String csrf) {
        return SESSION_COOKIE + "=" + sid + "; " + CSRF_COOKIE + "=" + csrf;
    }

    private static byte[] newKey() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        return key;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** The values the harness issued: the session cookie (the refresh stand-in) and the CSRF cookie. */
    private record Cookies(String sid, String csrf) {
        String header() {
            return cookieHeader(sid, csrf);
        }
    }

}

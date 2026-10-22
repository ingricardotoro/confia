package com.confia.shared.web.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.csrf.CsrfKeys;
import com.confia.shared.security.csrf.CsrfTokens;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Specs/web-edge, scenario "Atributos de la cookie CSRF" [WE52] and the attributes of the session
 * cookie (session-endpoints-cookie-and-csrf design.md, decision 5): the exact attributes of the two
 * cookies the helper issues, the deletion that mirrors them, and the three results of reading the
 * session cookie. The values are generated at run time; no cookie value is a constant of the
 * repository (CLAUDE.md, regla 13).
 */
class SessionCookiesTest {

    private static final String SESSION = "__Secure-confia_admin_sid";
    private static final String CSRF = "__Host-confia_csrf";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CsrfTokens tokens = new CsrfTokens(CsrfKeys.of(newBytes(32), null));
    private final SessionCookies cookies = new SessionCookies(tokens);

    private static byte[] newBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** A refresh token of the shape the session change issues: 43 base64url characters. */
    private static String newRefreshToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(newBytes(32));
    }

    /** One {@code Set-Cookie} line split into its name, its value and its attributes. */
    private record Parsed(String name, String value, List<String> attributes) {

        static Parsed of(String line) {
            String[] parts = line.split("; ");
            int equals = parts[0].indexOf('=');
            return new Parsed(parts[0].substring(0, equals), parts[0].substring(equals + 1),
                    Arrays.stream(parts).skip(1).toList());
        }

        boolean has(String attribute) {
            return attributes.stream().anyMatch(a -> a.equalsIgnoreCase(attribute));
        }

        boolean hasAttributeNamed(String attributeName) {
            return attributes.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT)
                    .startsWith(attributeName.toLowerCase(Locale.ROOT) + "="));
        }
    }

    private static List<Parsed> setCookiesOf(MockHttpServletResponse response) {
        return response.getHeaders("Set-Cookie").stream().map(Parsed::of).toList();
    }

    private Parsed issued(String name, String refresh) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        cookies.issue(response, refresh);
        return setCookiesOf(response).stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow();
    }

    // --- Issue ---

    @Test
    void issuingWritesExactlyTwoSetCookieHeadersTheSessionOneFirst() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.issue(response, newRefreshToken());

        assertThat(setCookiesOf(response)).extracting(Parsed::name).containsExactly(SESSION, CSRF);
    }

    @Test
    void theSessionCookieCarriesTheRefreshTokenWithTheExactAttributes() {
        String refresh = newRefreshToken();

        Parsed session = issued(SESSION, refresh);

        assertThat(session.value()).isEqualTo(refresh).hasSize(43);
        assertThat(session.attributes()).containsExactlyInAnyOrder("Path=/api/v1/auth/sessions",
                "Secure", "HttpOnly", "SameSite=Strict");
        assertThat(session.hasAttributeNamed("Domain")).isFalse();
        assertThat(session.hasAttributeNamed("Max-Age")).isFalse();
        assertThat(session.hasAttributeNamed("Expires")).isFalse();
    }

    @Test
    void theCsrfCookieCarriesATokenBoundToTheRefreshWithTheExactAttributes() {
        String refresh = newRefreshToken();

        Parsed csrf = issued(CSRF, refresh);

        assertThat(tokens.verify(csrf.value(), refresh)).as("the token is bound to the refresh")
                .isTrue();
        assertThat(tokens.verify(csrf.value(), newRefreshToken())).isFalse();
        assertThat(csrf.attributes()).containsExactlyInAnyOrder("Path=/", "Secure",
                "SameSite=Strict");
        assertThat(csrf.has("HttpOnly")).as("the single-page application reads it").isFalse();
        assertThat(csrf.hasAttributeNamed("Domain")).isFalse();
        assertThat(csrf.hasAttributeNamed("Max-Age")).isFalse();
        assertThat(csrf.hasAttributeNamed("Expires")).isFalse();
    }

    @Test
    void issuingAgainRotatesBothValuesTogether() {
        String first = newRefreshToken();
        String second = newRefreshToken();

        assertThat(issued(SESSION, first).value()).isNotEqualTo(issued(SESSION, second).value());
        assertThat(issued(CSRF, first).value()).isNotEqualTo(issued(CSRF, second).value());
    }

    @Test
    void issuingKeepsTheCookiesAlreadyOnTheResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", "other=1; Path=/");

        cookies.issue(response, newRefreshToken());

        assertThat(response.getHeaders("Set-Cookie")).hasSize(3);
        assertThat(response.getHeaders("Set-Cookie").get(0)).isEqualTo("other=1; Path=/");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"short-value", "has space in it and is longer than forty-three chars!!",
            "a;b=c_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ñ_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa=", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa+/"})
    void aValueThatIsNotARefreshTokenIsRefusedAndNeverEchoed(String notARefreshToken) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> cookies.issue(response, notARefreshToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(notARefreshToken);

        assertThat(response.getHeaders("Set-Cookie")).as("nothing is written").isEmpty();
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 42, 44, 64})
    void aTextOfTheWrongLengthIsRefused(int length) {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> cookies.issue(response, "a".repeat(length)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void anEmptyRefreshTokenIsRefused() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> cookies.issue(response, ""))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test
    void aNullRefreshTokenIsRefused() {
        assertThatThrownBy(() -> cookies.issue(new MockHttpServletResponse(), null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- Clear ---

    @Test
    void clearingWritesBothNamesEmptyWithMaxAgeZeroAndTheSameAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.clear(response);

        List<Parsed> cleared = setCookiesOf(response);
        assertThat(cleared).extracting(Parsed::name).containsExactly(SESSION, CSRF);
        Parsed session = cleared.get(0);
        Parsed csrf = cleared.get(1);
        assertThat(session.value()).isEmpty();
        assertThat(session.attributes()).contains("Path=/api/v1/auth/sessions", "Secure", "HttpOnly",
                "SameSite=Strict", "Max-Age=0");
        assertThat(csrf.value()).isEmpty();
        assertThat(csrf.attributes()).contains("Path=/", "Secure", "SameSite=Strict", "Max-Age=0");
        assertThat(csrf.has("HttpOnly")).isFalse();
        assertThat(session.hasAttributeNamed("Domain")).isFalse();
        assertThat(csrf.hasAttributeNamed("Domain")).isFalse();
    }

    @Test
    void theClearingHasTheSamePathSecureAndSameSiteAsTheIssueSoABrowserReplacesTheCookie() {
        String refresh = newRefreshToken();
        MockHttpServletResponse cleared = new MockHttpServletResponse();
        cookies.clear(cleared);

        for (Parsed clearing : setCookiesOf(cleared)) {
            Parsed issued = issued(clearing.name(), refresh);
            assertThat(clearing.attributes()).containsAll(issued.attributes());
        }
    }

    // --- Read ---

    private static MockHttpServletRequest requestWith(Cookie... cookies) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookies);
        return request;
    }

    @Test
    void aRequestWithoutCookiesHasNoSessionCookie() {
        assertThat(cookies.read(new MockHttpServletRequest()))
                .isInstanceOf(SessionCookie.Absent.class);
    }

    @Test
    void aRequestWithOtherCookiesHasNoSessionCookie() {
        MockHttpServletRequest request = requestWith(new Cookie(CSRF, "x"), new Cookie("sid", "y"),
                new Cookie("x" + SESSION, "z"), new Cookie("confia_admin_sid", "w"));

        assertThat(cookies.read(request)).isInstanceOf(SessionCookie.Absent.class);
    }

    @Test
    void oneSessionCookieIsPresentWithItsText() {
        String refresh = newRefreshToken();

        SessionCookie read = cookies.read(requestWith(new Cookie("other", "1"),
                new Cookie(SESSION, refresh), new Cookie(CSRF, "x")));

        assertThat(read).isInstanceOfSatisfying(SessionCookie.Present.class,
                present -> assertThat(present.text()).isEqualTo(refresh));
    }

    @Test
    void anEmptySessionCookieIsPresentNeverAbsentSoItMeansWhatTheCsrfFilterMeans() {
        SessionCookie read = cookies.read(requestWith(new Cookie(SESSION, "")));

        assertThat(read).isInstanceOfSatisfying(SessionCookie.Present.class,
                present -> assertThat(present.text()).isEmpty());
    }

    @Test
    void twoSessionCookiesAreDuplicatedNeverChoosingOne() {
        SessionCookie read = cookies.read(requestWith(new Cookie(SESSION, newRefreshToken()),
                new Cookie(SESSION, newRefreshToken())));

        assertThat(read).isInstanceOf(SessionCookie.Duplicated.class);
    }

    @Test
    void twoSessionCookiesWithTheSameValueAreStillDuplicated() {
        String refresh = newRefreshToken();

        SessionCookie read = cookies.read(requestWith(new Cookie(SESSION, refresh),
                new Cookie("other", "1"), new Cookie(SESSION, refresh)));

        assertThat(read).isInstanceOf(SessionCookie.Duplicated.class);
    }

    @Test
    void aPresentCookieNeverPrintsItsText() {
        String refresh = newRefreshToken();

        SessionCookie read = cookies.read(requestWith(new Cookie(SESSION, refresh)));

        assertThat(read.toString()).doesNotContain(refresh);
    }
}

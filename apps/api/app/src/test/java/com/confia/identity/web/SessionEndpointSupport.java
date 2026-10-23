package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.shared.security.csrf.CsrfTokens;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * What the integration tests of the cookie routes of the session share
 * (session-endpoints-cookie-and-csrf design.md decisions 14 and 15): a real login that hands out the
 * two cookies, the requests of the browser (cookie header, CSRF header, allowed {@code Origin}, a
 * client address of the test's own) and the families and tokens a real login cannot produce, seeded
 * with SQL relative to the clock of the process so that nothing here waits. Abstract, so the {@code
 * *IT} naming rule does not apply to it.
 */
abstract class SessionEndpointSupport extends LoginEndpointSupport {

    static final String REFRESH = "/api/v1/auth/sessions/refresh";
    static final String CURRENT = "/api/v1/auth/sessions/current";
    static final String ALL = "/api/v1/auth/sessions";
    static final String SESSION_COOKIE = "__Secure-confia_admin_sid";
    static final String CSRF_COOKIE = "__Host-confia_csrf";

    /** The session a real login opened: the access token and the two cookies it handed out. */
    record Opened(String accessToken, UUID sessionId, String refresh, String csrf) {
    }

    /** A token seeded with SQL, and the text of it. */
    record Seeded(UUID familyId, String refresh) {
    }

    /** An account with no second factor, logged in from a client address of its own. */
    Opened openSession(LoginProcess process) {
        return openSession(process, LoginProcess.freshAddress());
    }

    Opened openSession(LoginProcess process, String address) {
        String email = "sesion." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(email, false, false);
        HttpResponse<String> response = process.login(address, email, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        String access = body.get("accessToken").asString();
        return new Opened(access, process.verifier().verifyAccess(access).sessionId(),
                cookieValue(response, SESSION_COOKIE), cookieValue(response, CSRF_COOKIE));
    }

    /** The value of the cookie {@code name} in the {@code Set-Cookie} headers of a response. */
    static String cookieValue(HttpResponse<String> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(line -> line.startsWith(name + "=")).findFirst()
                .map(line -> line.substring(name.length() + 1, line.indexOf(';'))).orElseThrow();
    }

    static String cookieHeader(String refresh, String csrf) {
        return SESSION_COOKIE + "=" + refresh + "; " + CSRF_COOKIE + "=" + csrf;
    }

    /** A request of the browser: the allowed {@code Origin}, a client address, and the extra headers. */
    HttpResponse<String> call(LoginProcess process, String method, String path, String address,
            String... headers) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("Origin", LoginProcess.ORIGIN);
        all.put("X-Forwarded-For", address);
        for (int i = 0; i < headers.length; i += 2) {
            all.put(headers[i], headers[i + 1]);
        }
        return process.send(method, path, null, all);
    }

    /** A refresh with the cookie of the session, the cookie of the CSRF and the header that repeats it. */
    HttpResponse<String> refresh(LoginProcess process, String address, String refresh, String csrf) {
        return call(process, "POST", REFRESH, address, "Cookie", cookieHeader(refresh, csrf),
                "X-CSRF-Token", csrf);
    }

    HttpResponse<String> refresh(LoginProcess process, Opened session) {
        return refresh(process, LoginProcess.freshAddress(), session.refresh(), session.csrf());
    }

    /** The CSRF token the process would issue for a refresh token, valid for any text. */
    static String csrfFor(LoginProcess process, String refresh) {
        return process.context().getBean(CsrfTokens.class).issue(refresh);
    }

    static PlainRefreshToken newToken() {
        return PlainRefreshToken.generate(new SecureRandom());
    }

    // --- seeds, relative to the clock of the process ---

    /**
     * A family of a new account. The three durations are those before now of its creation and last
     * use and, after now, of its absolute end (PostgreSQL intervals, such as {@code 5 minutes}).
     */
    UUID seedFamily(String createdAgo, String lastUsedAgo, String endsIn, String revokedReason) {
        StaffAccountId account = seedAccount("semilla." + UUID.randomUUID() + "@colegio.edu.hn",
                false, false);
        UUID familyId = UUID.randomUUID();
        transactionRunner().execute(contextOf(institution()), () -> dsl.execute("""
                insert into identity_refresh_token_family
                    (institution_id, id, account_id, second_factor_verified, user_agent_fingerprint,
                     created_at, last_used_at, absolute_expires_at, revoked_at, revoked_reason)
                values (?, ?, ?, false, ?, now() - cast(? as interval), now() - cast(? as interval),
                        now() + cast(? as interval),
                        case when cast(? as text) is null then null else now() end, cast(? as text))
                """, institution().value(), familyId, account.value(),
                UserAgentFingerprint.of(null).value(), createdAgo, lastUsedAgo, endsIn,
                revokedReason, revokedReason));
        return familyId;
    }

    /** An unconsumed token of {@code familyId}, issued {@code issuedAgo} ago and ending {@code expiresIn} from now. */
    String seedOpenToken(UUID familyId, String issuedAgo, String expiresIn) {
        PlainRefreshToken token = newToken();
        transactionRunner().execute(contextOf(institution()),
                () -> insertToken(UUID.randomUUID(), familyId, token, issuedAgo, expiresIn));
        return token.value();
    }

    /** A token consumed {@code consumedAgo} ago, with its open successor; the text of the consumed one. */
    String seedConsumedToken(UUID familyId, String issuedAgo, String consumedAgo) {
        PlainRefreshToken consumed = newToken();
        UUID consumedId = UUID.randomUUID();
        UUID successorId = UUID.randomUUID();
        transactionRunner().execute(contextOf(institution()), () -> {
            dsl.execute("""
                    insert into identity_refresh_token
                        (institution_id, id, family_id, token_hash, issued_at, expires_at,
                         consumed_at, consumed_user_agent_fingerprint, replaced_by)
                    values (?, ?, ?, ?, now() - cast(? as interval), now() + interval '6 hours',
                            now() - cast(? as interval), ?, ?)
                    """, institution().value(), consumedId, familyId,
                    RefreshTokenHash.of(consumed).value(), issuedAgo, consumedAgo,
                    UserAgentFingerprint.of(null).value(), successorId);
            return insertToken(successorId, familyId, newToken(), consumedAgo, "6 hours");
        });
        return consumed.value();
    }

    private int insertToken(UUID tokenId, UUID familyId, PlainRefreshToken token, String issuedAgo,
            String expiresIn) {
        return dsl.execute("""
                insert into identity_refresh_token
                    (institution_id, id, family_id, token_hash, issued_at, expires_at)
                values (?, ?, ?, ?, now() - cast(? as interval), now() + cast(? as interval))
                """, institution().value(), tokenId, familyId, RefreshTokenHash.of(token).value(),
                issuedAgo, expiresIn);
    }

    // --- reads ---

    org.jooq.Record familyRow(UUID familyId) {
        return transactionRunner().execute(contextOf(institution()), () -> dsl.fetchSingle("""
                select revoked_at, revoked_reason, last_used_at
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, institution().value(), familyId));
    }

    /** Both {@code Set-Cookie} lines that delete the two cookies, in the order the helper writes them. */
    static void assertBothCookiesCleared(HttpResponse<String> response) {
        List<String> cookies = response.headers().allValues("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies.get(0)).startsWith(SESSION_COOKIE + "=;").contains("Max-Age=0")
                .contains("Path=/api/v1/auth/sessions").contains("HttpOnly").contains("Secure")
                .contains("SameSite=Strict");
        assertThat(cookies.get(1)).startsWith(CSRF_COOKIE + "=;").contains("Max-Age=0")
                .contains("Path=/").contains("Secure").contains("SameSite=Strict");
    }
}

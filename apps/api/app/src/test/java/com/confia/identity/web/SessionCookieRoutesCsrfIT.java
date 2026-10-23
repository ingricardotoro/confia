package com.confia.identity.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Hard condition 3, on the real routes (session-endpoints-cookie-and-csrf design.md decision 2; scenario
 * WE54, the counterpart of WE53, which the harness proves in {@code CsrfOriginFilterTest}): the
 * refresh and the end of the current session, with the session cookie present, are refused with
 * {@code 403 csrf-invalid} when the request carries no {@code X-CSRF-Token}, when it carries the
 * CSRF token of another session, and when it carries no {@code Origin}. That makes six results, and
 * for each of them the use case is invoked zero times: no entry of the trail is written and the
 * family and its token are as they were. The control that comes first sends the same requests done
 * right and shows they are served, so the six refusals are not the answer to a request that could
 * never pass.
 */
class SessionCookieRoutesCsrfIT extends SessionEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String CURRENT_SESSION = "/api/v1/auth/sessions/current";

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

    @Test
    void theControlSameRequestsDoneRightAreServed() {
        Opened forRefresh = openSession(process);
        Opened forClosing = openSession(process);

        HttpResponse<String> refreshed = refresh(process, forRefresh);
        HttpResponse<String> closed = call(process, "DELETE", CURRENT_SESSION,
                LoginProcess.freshAddress(), "Cookie",
                cookieHeader(forClosing.refresh(), forClosing.csrf()), "X-CSRF-Token",
                forClosing.csrf());

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(closed.statusCode()).isEqualTo(204);
    }

    @Test
    void theRefreshAndTheEndOfTheCurrentSessionRefuseTheThreeForgeriesAndRunTheUseCaseZeroTimes() {
        Opened victim = openSession(process);
        Opened attacker = openSession(process);
        List<String> actions = List.of("identity.session.refreshed",
                "identity.session.refresh_rejected", "identity.session.revoked",
                "identity.session.reuse_detected");
        String cookie = cookieHeader(victim.refresh(), victim.csrf());
        String foreignCsrf = attacker.csrf();
        String foreignCookie = cookieHeader(victim.refresh(), foreignCsrf);
        Object lastUsedBefore = familyRow(victim.sessionId()).get("last_used_at");

        for (String route : List.of("POST " + REFRESH, "DELETE " + CURRENT_SESSION)) {
            String method = route.substring(0, route.indexOf(' '));
            String path = route.substring(route.indexOf(' ') + 1);
            String address = LoginProcess.freshAddress();

            HttpResponse<String> noToken = call(process, method, path, address, "Cookie", cookie);
            HttpResponse<String> otherSession = call(process, method, path, address, "Cookie",
                    foreignCookie, "X-CSRF-Token", foreignCsrf);
            HttpResponse<String> noOrigin = process.send(method, path, null, Map.of(
                    "X-Forwarded-For", address, "Cookie", cookie, "X-CSRF-Token", victim.csrf()));

            assertProblem(noToken, 403, "csrf-invalid");
            assertProblem(otherSession, 403, "csrf-invalid");
            assertProblem(noOrigin, 403, "csrf-invalid");
            for (HttpResponse<String> refused : List.of(noToken, otherSession, noOrigin)) {
                assertThat(refused.headers().allValues("Set-Cookie")).as("%s writes no cookie", route)
                        .isEmpty();
            }
        }

        assertThat(auditRows()).as("the use cases ran zero times: nothing was written")
                .noneMatch(row -> actions.contains(row.action()));
        assertThat(familyRow(victim.sessionId()).get("revoked_at")).isNull();
        assertThat(familyRow(victim.sessionId()).get("last_used_at"))
                .as("the family was not used").isEqualTo(lastUsedBefore);
        assertThat(refresh(process, LoginProcess.freshAddress(), victim.refresh(), victim.csrf())
                .statusCode()).as("the token of the victim is still the open one").isEqualTo(200);
    }
}

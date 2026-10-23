package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.csrf.CsrfTokens;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Two tabs of one browser refreshing at once (session-endpoints-cookie-and-csrf design.md decision
 * 14; scenario I187): both present the same refresh token with the same agent, one is a fresh
 * rotation and the other a retry inside the grace window, both answer {@code 200} with the same
 * successor, and whichever pair of cookies the browser ends up holding (the session cookie of one
 * response with the CSRF cookie of the other, in any of the four combinations) is coherent: the
 * CSRF token validates against the shared successor. The process is the real one and the requests
 * are real and concurrent; the test waits for the answers, never for the clock.
 */
class TwoTabRefreshConcurrencyIT extends SessionEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());

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
    void twoTabsRefreshingTheSameTokenAtOnceEndWithTheSameSuccessorAndACsrfThatValidatesAgainstIt()
            throws InterruptedException, ExecutionException, TimeoutException {
        Opened session = openSession(process);
        String address = LoginProcess.freshAddress();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService tabs = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = tabs.submit(() -> {
                start.await();
                return refresh(process, address, session.refresh(), session.csrf());
            });
            Future<HttpResponse<String>> second = tabs.submit(() -> {
                start.await();
                return refresh(process, address, session.refresh(), session.csrf());
            });
            start.countDown();

            HttpResponse<String> a = first.get(60, TimeUnit.SECONDS);
            HttpResponse<String> b = second.get(60, TimeUnit.SECONDS);

            assertThat(a.statusCode()).isEqualTo(200);
            assertThat(b.statusCode()).isEqualTo(200);
            String successor = cookieValue(a, SESSION_COOKIE);
            assertThat(cookieValue(b, SESSION_COOKIE)).as("the same successor").isEqualTo(successor);
            assertThat(successor).isNotEqualTo(session.refresh());
            CsrfTokens tokens = process.context().getBean(CsrfTokens.class);
            for (String sessionCookie : List.of(cookieValue(a, SESSION_COOKIE),
                    cookieValue(b, SESSION_COOKIE))) {
                for (String csrfCookie : List.of(cookieValue(a, CSRF_COOKIE),
                        cookieValue(b, CSRF_COOKIE))) {
                    assertThat(tokens.verify(csrfCookie, sessionCookie))
                            .as("every pair of cookies the browser can hold is coherent").isTrue();
                }
            }
            assertThat(auditRows("identity.session.refreshed")).hasSize(2);
            assertThat(auditRows("identity.session.refreshed").stream()
                    .map(row -> JSON.readTree(row.afterValue()).get("rotation").asString()).sorted())
                    .as("one fresh and one grace").containsExactly("fresh", "grace");
            assertThat(refresh(process, address, successor, cookieValue(b, CSRF_COOKIE))
                    .statusCode()).as("the pair is usable for the next refresh").isEqualTo(200);
        } finally {
            tabs.shutdownNow();
        }
    }
}

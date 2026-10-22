package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/web-edge, scenario "Ninguna otra respuesta emite cookies" [WE65] (session-endpoints-cookie-
 * and-csrf design.md, decision 5): the cookie helper exists in this change and no route calls it
 * yet, so no response of the real administrative process carries a {@code Set-Cookie}, and none
 * creates an HTTP session (a session would announce itself with a {@code JSESSIONID} cookie). It
 * covers the public documentation routes (the {@code local} profile publishes them), the routes
 * the chain denies, and the {@code 401} answers of {@code GET /api/v1/auth/sessions/current}. The
 * {@code 200} of that route is asserted next to its other answers in {@code
 * CurrentSessionEndpointIT}. The process has no database and runs in the {@code local} profile.
 */
class NoStraySetCookieTest {

    private static final String CURRENT = "/api/v1/auth/sessions/current";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static OpenApiProcess process;

    @BeforeAll
    static void start() {
        process = OpenApiProcess.start("admin", "local");
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    private static HttpResponse<String> send(String method, String path, String... headers) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + process.port() + path))
                .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + path, e);
        }
    }

    private static void assertNoCookie(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Set-Cookie")).as("Set-Cookie of the answer")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
            "/swagger-ui/index.html"})
    void thePublicDocumentationRoutesSetNoCookie(String path) {
        HttpResponse<String> response = process.get(path);

        assertThat(response.statusCode()).as("%s is served, so the check looks at a real answer", path)
                .isIn(200, 302);
        assertNoCookie(response);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/x", "/api/v1/auth/sessions/refresh", "/api/v1/auth/sessions", "/error"})
    void aDeniedRouteSetsNoCookie(String path) {
        HttpResponse<String> response = process.get(path);

        assertThat(response.statusCode()).isIn(401, 403, 404);
        assertNoCookie(response);
    }

    @Test
    void theCurrentSessionWithoutACredentialAnswers401WithNoCookie() {
        HttpResponse<String> response = process.get(CURRENT);

        assertThat(response.statusCode()).isEqualTo(401);
        assertNoCookie(response);
    }

    @Test
    void theCurrentSessionWithAGarbageTokenAnswers401WithNoCookie() {
        HttpResponse<String> response = send("GET", CURRENT, "Authorization", "Bearer not-a-token");

        assertThat(response.statusCode()).isEqualTo(401);
        assertNoCookie(response);
    }

    @Test
    void aRequestThatSendsAForgedSessionCookieIsAnsweredWithNoCookieBack() {
        // The server never reflects, rotates or clears a cookie it did not issue: nothing issues
        // one in this change.
        HttpResponse<String> response = send("GET", CURRENT, "Cookie",
                "__Secure-confia_admin_sid=" + "a".repeat(43) + "; __Host-confia_csrf=" + "b".repeat(87));

        assertThat(response.statusCode()).isEqualTo(401);
        assertNoCookie(response);
    }

    @Test
    void anUnsafeMethodRefusedBeforeTheChainSetsNoCookie() {
        HttpResponse<String> response = send("POST", "/api/v1/auth/sessions/refresh");

        assertThat(response.statusCode()).isEqualTo(403);
        assertNoCookie(response);
    }
}

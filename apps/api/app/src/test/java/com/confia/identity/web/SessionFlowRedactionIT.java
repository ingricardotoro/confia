package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.confia.identity.domain.TotpAlgorithm;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.token.MfaTokenClaims;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * A whole session flow on the real administrative process, with every log event captured at {@code
 * TRACE} (session-endpoints-cookie-and-csrf design.md decision 14; scenarios I209 and I210): login
 * with a second factor owed, enrollment, first verification, refresh and close of the current
 * session, and a close of every session with the bearer token. The structured log is swept for the
 * password, every access, refresh and restricted token, every CSRF token, the TOTP secret, the code
 * presented, the recovery codes, the {@code jti} of the tokens, and for the two cookies and the
 * {@code Authorization} header with a value, in the message, the arguments, the MDC and the
 * exception of every event.
 *
 * <p><b>The sweep never puts what it searched into its own failure message</b>: on a leak it names the
 * label and the length of the secret only. The negative control runs first and demands that a CSRF
 * token logged on purpose fails the same sweep.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionFlowRedactionIT extends SessionEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String ENROLLMENTS = "/api/v1/auth/mfa/enrollments";
    private static final String VERIFICATIONS = "/api/v1/auth/mfa/verifications";
    private static final Duration PERIOD = Duration.ofSeconds(30);

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

    // --- I210: the control, first ---

    @Test
    @Order(1)
    void aCsrfTokenLoggedOnPurposeFailsTheSweepNamingOnlyItsLabel() {
        Opened session = openSession(process);
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("a CSRF token", session.csrf());

        List<String> captured = capturing(() ->
                LoggerFactory.getLogger("session.flow.control").trace("seeded value {}", session.csrf()));

        assertThatThrownBy(() -> assertNothingLeaks(captured, secrets))
                .isInstanceOf(AssertionError.class).hasMessageContaining("a CSRF token")
                .hasMessageNotContaining(session.csrf());
    }

    @Test
    @Order(2)
    void aSecretInTheMdcOrInAnExceptionFailsTheSweepToo() {
        Map<String, String> secrets = Map.of("a secret", "Zm9vYmFyLWRvLW5vdC1wcmludA");

        List<String> inMdc = capturing(() -> {
            org.slf4j.MDC.put("seeded", "Zm9vYmFyLWRvLW5vdC1wcmludA");
            try {
                LoggerFactory.getLogger("session.flow.control").trace("nothing here");
            } finally {
                org.slf4j.MDC.remove("seeded");
            }
        });
        List<String> inException = capturing(() ->
                LoggerFactory.getLogger("session.flow.control").trace("nothing here",
                        new IllegalStateException("Zm9vYmFyLWRvLW5vdC1wcmludA")));

        assertThatThrownBy(() -> assertNothingLeaks(inMdc, secrets)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertNothingLeaks(inException, secrets))
                .isInstanceOf(AssertionError.class);
        assertNothingLeaks(List.of("a line with nothing of the kind"), secrets);
    }

    // --- I209: the whole flow ---

    @Test
    @Order(3)
    void theWholeFlowLeavesNoSecretInTheStructuredLog() {
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("the password", PASSWORD);
        String email = "flujo." + UUID.randomUUID() + "@colegio.edu.hn";
        seedAccount(email, true, false);

        List<String> captured = capturing(() -> {
            HttpResponse<String> login = process.login(LoginProcess.freshAddress(), email, PASSWORD);
            assertThat(login.statusCode()).isEqualTo(200);
            String restricted = json(login).get("secondFactorToken").asString();
            secrets.put("the restricted token", restricted);
            MfaTokenClaims claims = process.verifier().verifyMfa(restricted);
            secrets.put("the jti of the restricted token", claims.tokenId().toString());

            HttpResponse<String> enrollment = process.postTo(ENROLLMENTS, restricted, null,
                    "X-Forwarded-For", LoginProcess.freshAddress());
            assertThat(enrollment.statusCode()).isEqualTo(201);
            JsonNode enrolled = json(enrollment);
            String totpSecret = enrolled.get("secret").asString();
            secrets.put("the TOTP secret", totpSecret);
            int index = 0;
            for (JsonNode code : enrolled.get("recoveryCodes")) {
                secrets.put("recovery code " + index++, code.asString());
            }

            String presented = codeOf(totpSecret);
            secrets.put("the TOTP code presented", presented);
            HttpResponse<String> verification = process.postTo(VERIFICATIONS, restricted,
                    "{\"code\":\"" + presented + "\"}", "X-Forwarded-For",
                    LoginProcess.freshAddress());
            assertThat(verification.statusCode()).isEqualTo(200);
            Opened opened = watch(verification, secrets);

            HttpResponse<String> refreshed = refresh(process, LoginProcess.freshAddress(),
                    opened.refresh(), opened.csrf());
            assertThat(refreshed.statusCode()).isEqualTo(200);
            Opened rotated = watch(refreshed, secrets);

            HttpResponse<String> closed = call(process, "DELETE", CURRENT, LoginProcess.freshAddress(),
                    "Cookie", cookieHeader(rotated.refresh(), rotated.csrf()), "X-CSRF-Token",
                    rotated.csrf());
            assertThat(closed.statusCode()).isEqualTo(204);

            Opened other = openSession(process);
            secrets.put("the access token of the second session", other.accessToken());
            secrets.put("the refresh token of the second session", other.refresh());
            secrets.put("the CSRF token of the second session", other.csrf());
            secrets.put("the jti of the second access token", process.verifier()
                    .verifyAccess(other.accessToken()).tokenId().toString());
            HttpResponse<String> closedAll = call(process, "DELETE", ALL, LoginProcess.freshAddress(),
                    "Authorization", "Bearer " + other.accessToken());
            assertThat(closedAll.statusCode()).isEqualTo(204);
        });

        assertThat(captured).as("the capture did record the flow").hasSizeGreaterThan(50);
        secrets.put("the session cookie with a value", SESSION_COOKIE + "=");
        secrets.put("the CSRF cookie with a value", CSRF_COOKIE + "=");
        secrets.put("an Authorization header with a bearer value", "Bearer ey");
        assertNothingLeaks(captured, secrets);
    }

    // ---------------------------------------------------------------------------------------------

    /** Registers the tokens and the cookies a response handed out and returns them. */
    private static Opened watch(HttpResponse<String> response, Map<String, String> secrets) {
        String access = json(response).get("accessToken").asString();
        AuthenticatedActor actor = process.verifier().verifyAccess(access);
        String refresh = cookieValue(response, SESSION_COOKIE);
        String csrf = cookieValue(response, CSRF_COOKIE);
        int n = secrets.size();
        secrets.put("an access token (" + n + ")", access);
        secrets.put("the jti of an access token (" + n + ")", actor.tokenId().toString());
        secrets.put("a refresh token (" + n + ")", refresh);
        secrets.put("a CSRF token (" + n + ")", csrf);
        return new Opened(access, actor.sessionId(), refresh, csrf);
    }

    /**
     * Runs {@code action} with the root logger at {@code TRACE} and a capture attached, and returns
     * every piece of text the events carried: message, each argument, the keys and values of the MDC
     * and the exception with its causes.
     */
    private static List<String> capturing(Runnable action) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.setLevel(Level.TRACE);
        root.addAppender(appender);
        try {
            action.run();
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
        List<String> texts = new ArrayList<>();
        for (ILoggingEvent event : new ArrayList<>(appender.list)) {
            texts.add(event.getFormattedMessage());
            texts.add(String.valueOf(event.getMessage()));
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) {
                    texts.add(String.valueOf(argument));
                }
            }
            event.getMDCPropertyMap().forEach((key, value) -> {
                texts.add(key);
                texts.add(value);
            });
            if (event.getThrowableProxy() != null) {
                texts.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
        }
        return texts;
    }

    /**
     * The failure message carries the label and the length of the secret and never its value or the
     * text it was searched in.
     */
    private static void assertNothingLeaks(List<String> captured, Map<String, String> secrets) {
        String haystack = String.join("\n", captured);
        secrets.forEach((label, value) -> {
            if (haystack.contains(value)) {
                fail("captured log unexpectedly contains %s (%d characters); the value itself is "
                        + "deliberately withheld from this failure message", label, value.length());
            }
        });
    }

    private static String codeOf(String base32) {
        long counter = TotpAlgorithm.counterFor(Instant.now(), PERIOD);
        return TotpAlgorithm.generate(decodeBase32(base32), counter).value();
    }

    private static byte[] decodeBase32(String text) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        byte[] out = new byte[text.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char c : text.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) (buffer >> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }
}

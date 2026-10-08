package com.confia.shared.web.request;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.harness.HarnessProcess;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Specs/web-edge, "Los registros no contienen cabeceras ni cuerpos" (web-edge-foundations
 * design.md, decision 22; CLAUDE.md regla 11). The root logger and every protected prefix are put at
 * {@code TRACE}, the worst case, and three requests (accepted, denied and failing with a
 * {@code 500}) carry a bearer token, a cookie and a body. No formatted message, argument, MDC entry
 * or stack trace of any event may contain any of the three values.
 *
 * <p>The values are placeholders, not secrets. The verifier is shown to fail on an event logged on
 * purpose, and the guard is shown to be the cause by taking it away: without it the same requests
 * do leak into the log at that level.
 */
class SensitiveDataLoggingTest {

    private static final String BEARER = "SECRETO-A";
    private static final String COOKIE = "SECRETO-B";
    private static final String BODY = "SECRETO-C";
    private static final String QUERY = "SECRETO-D";
    private static final List<String> SECRETS = List.of(BEARER, COOKIE, BODY, QUERY);

    /** Every way an event can carry text, searched for each secret; one entry per hit. */
    static List<String> violations(List<ILoggingEvent> events, List<String> secrets) {
        List<String> found = new ArrayList<>();
        for (ILoggingEvent event : events) {
            Map<String, String> carriers = new LinkedHashMap<>();
            carriers.put("message", event.getFormattedMessage());
            carriers.put("message template", event.getMessage());
            carriers.put("arguments", java.util.Arrays.toString(event.getArgumentArray()));
            carriers.put("MDC", String.valueOf(event.getMDCPropertyMap()));
            carriers.put("stack trace", event.getThrowableProxy() == null ? ""
                    : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            carriers.forEach((carrier, text) -> {
                for (String secret : secrets) {
                    if (text != null && text.contains(secret)) {
                        found.add(event.getLoggerName() + " " + event.getLevel() + " " + carrier
                                + " contains " + secret);
                    }
                }
            });
        }
        return found;
    }

    /** The messages the two failing routes of the harness throw; a stack trace holds each one. */
    private static final String FAIL_OPEN = "sentinel-fail-open";
    private static final String FAIL_AUTHENTICATED = "sentinel-fail-authenticated";

    /** The token, its payload segment and the payload it decodes to: all three are secrets. */
    private static List<String> secretsOf(String token) {
        String payload = token.split("[.]")[1];
        String decoded = new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8);
        List<String> secrets = new ArrayList<>(SECRETS);
        secrets.addAll(List.of(token, payload, decoded));
        return secrets;
    }

    private static List<ILoggingEvent> logOf(HarnessProcess process, String token) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Map<Logger, Level> before = new LinkedHashMap<>();
        List<Logger> raised = new ArrayList<>(List.of(root));
        SensitiveLogGuard.PROTECTED_PREFIXES.forEach(prefix -> raised.add(context.getLogger(prefix)));
        raised.forEach(logger -> before.put(logger, logger.getLevel()));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        raised.forEach(logger -> logger.setLevel(Level.TRACE));
        try {
            process.sendWithBody("GET", "/test/open", BODY, "Authorization", "Bearer " + BEARER,
                    "Cookie", "sid=" + COOKIE);
            process.sendWithBody("POST", "/x", BODY, "Authorization", "Bearer " + BEARER,
                    "Cookie", "sid=" + COOKIE);
            process.sendWithBody("GET", "/test/boom", BODY,
                    "Cookie", "sid=" + COOKIE);
            process.get("/test/open?token=" + QUERY);
            process.get("/x?token=" + QUERY);
            // A VALID token on two routes that fail with a 500: the token reaches the translator
            // and the log of an unexpected failure, on a public route and on an authenticated one.
            process.get("/test/fail-open", "Authorization", "Bearer " + token);
            process.get("/test/fail-authenticated", "Authorization", "Bearer " + token);
        } finally {
            root.detachAppender(appender);
            before.forEach(Logger::setLevel);
        }
        return new ArrayList<>(appender.list);
    }

    @Test
    void noEventOfAnyRequestCarriesTheAuthorizationTheCookieOrTheBody() {
        try (HarnessProcess process = HarnessProcess.start()) {
            String token = process.tokens().access();
            List<ILoggingEvent> events = logOf(process, token);

            assertThat(events).as("non-vacuous: the requests did produce events")
                    .anyMatch(event -> event.getThrowableProxy() != null);
            assertThat(events).as("non-vacuous: both failing routes ran with a valid token")
                    .anyMatch(event -> event.getThrowableProxy() != null && ThrowableProxyUtil
                            .asString(event.getThrowableProxy()).contains(FAIL_OPEN))
                    .anyMatch(event -> event.getThrowableProxy() != null && ThrowableProxyUtil
                            .asString(event.getThrowableProxy()).contains(FAIL_AUTHENTICATED));
            assertThat(violations(events, secretsOf(token))).isEmpty();
            assertThat(events).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains("generated security password"));
        }
    }

    @Test
    void theVerifierReportsASecretLoggedOnPurposeInEveryPlaceAnEventCarriesText() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger probe = (Logger) LoggerFactory.getLogger("com.confia.test.negative-control");
        probe.addAppender(appender);
        try {
            probe.info("token {}", BEARER);
            probe.info("plain message " + COOKIE);
            org.slf4j.MDC.put("leak", BODY);
            probe.info("with MDC");
            probe.error("with trace", new IllegalStateException("boom " + BEARER));
        } finally {
            org.slf4j.MDC.remove("leak");
            probe.detachAndStopAllAppenders();
        }

        assertThat(violations(appender.list, SECRETS)).containsExactlyInAnyOrder(
                "com.confia.test.negative-control INFO message contains SECRETO-A",
                "com.confia.test.negative-control INFO arguments contains SECRETO-A",
                "com.confia.test.negative-control INFO message contains SECRETO-B",
                "com.confia.test.negative-control INFO message template contains SECRETO-B",
                "com.confia.test.negative-control INFO MDC contains SECRETO-C",
                "com.confia.test.negative-control ERROR MDC contains SECRETO-C",
                "com.confia.test.negative-control ERROR stack trace contains SECRETO-A");
    }

    @Test
    void withoutTheGuardTheSameRequestsDoLeakIntoTheLogAtTrace() {
        try (HarnessProcess process = HarnessProcess.start()) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            SensitiveLogGuard guard = process.context().getBean(SensitiveLogGuard.class);
            assertThat(context.getTurboFilterList()).as("the guard is installed").contains(guard);
            context.getTurboFilterList().remove(guard);
            try {
                String token = process.tokens().access();
                List<String> leaks = violations(logOf(process, token), secretsOf(token));

                assertThat(leaks).as("the guard is what keeps each secret out")
                        .anyMatch(leak -> leak.startsWith("org.apache.coyote.")
                                && leak.endsWith("contains " + BEARER))
                        .anyMatch(leak -> leak.startsWith("org.apache.tomcat.util.http.")
                                && leak.endsWith("contains " + COOKIE))
                        .anyMatch(leak -> leak.startsWith("org.apache.coyote.")
                                && leak.endsWith("contains " + BODY))
                        .anyMatch(leak -> leak.startsWith("org.springframework.security.")
                                && leak.endsWith("contains " + QUERY));
            } finally {
                context.addTurboFilter(guard);
            }
        }
    }

    @Test
    void theGuardDeniesDebugAndTraceOfEveryProtectedLoggerAndNothingElse() {
        for (String prefix : SensitiveLogGuard.PROTECTED_PREFIXES) {
            for (Level level : List.of(Level.TRACE, Level.DEBUG)) {
                assertThat(SensitiveLogGuard.denies(level, prefix)).as("%s %s", level, prefix)
                        .isTrue();
                assertThat(SensitiveLogGuard.denies(level, prefix + ".Http11InputBuffer"))
                        .as("%s below %s", level, prefix).isTrue();
            }
            for (Level level : List.of(Level.INFO, Level.WARN, Level.ERROR)) {
                assertThat(SensitiveLogGuard.denies(level, prefix)).as("%s %s", level, prefix)
                        .isFalse();
            }
            assertThat(SensitiveLogGuard.denies(Level.DEBUG, prefix + "x"))
                    .as("a longer name that only shares the text is not below %s", prefix).isFalse();
        }
        assertThat(SensitiveLogGuard.denies(Level.TRACE, "com.confia.shared.web")).isFalse();
    }
}

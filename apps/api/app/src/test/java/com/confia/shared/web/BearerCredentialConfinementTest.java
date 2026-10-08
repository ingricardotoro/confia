package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.authentication.ActorAuthentication;
import com.confia.shared.web.harness.HarnessProcess;
import com.confia.shared.web.harness.HarnessTokens;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Specs/web-edge, what the bearer filter confines (session-tokens-and-web-layer design.md, decision
 * 5): a credential is read from {@code Authorization: Bearer} and from nowhere else, nothing the
 * filter does creates a session or a cookie, the principal holds no token and no authority, and no
 * log event at {@code TRACE} carries a token. Kept apart from {@code
 * AccessTokenAuthenticationFilterTest}, which covers what the filter accepts and refuses.
 *
 * <p>This class runs a single harness: a second Spring Boot start in the same JVM re-initializes
 * logging and would remove the guard that keeps raw headers out of the log.
 */
class BearerCredentialConfinementTest {

    private static HarnessProcess process;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void aCredentialOutsideTheAuthorizationHeaderIsIgnored() {
        String token = process.tokens().access();
        List<HttpResponse<String>> responses = List.of(
                process.get("/test/whoami?access_token=" + token),
                process.get("/test/whoami", "Cookie", "access_token=" + token),
                process.sendWithBody("GET", "/test/whoami", "access_token=" + token),
                process.get("/test/whoami", "X-Access-Token", token),
                process.get("/test/whoami", "X-Access-Token", bearer(token)));

        for (HttpResponse<String> response : responses) {
            assertProblem(response, 401, "authentication-required");
        }
        assertThat(process.get("/test/actor?access_token=" + token).body())
                .as("not even on a public route").contains("false");
        assertThat(process.get("/test/whoami", "Authorization", bearer(token)).statusCode())
                .as("non-vacuous: the same token in the header is accepted").isEqualTo(200);
    }

    @Test
    void noRequestCreatesASessionOrACookie() {
        int before = process.sessionsCreated();
        String token = process.tokens().access();

        List<HttpResponse<String>> responses = List.of(
                process.get("/test/whoami", "Authorization", bearer(token)),
                process.get("/test/whoami", "Authorization", bearer("broken")),
                process.get("/test/whoami"));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactly(200, 401, 401);
        for (HttpResponse<String> response : responses) {
            assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        }
        assertThat(process.sessionsCreated()).isEqualTo(before);
    }


    // --- The chain around the filter ---

    @Test
    void theFilterSitsBeforeTheAnonymousFilterInTheAdminChain() {
        List<jakarta.servlet.Filter> admin = process.securityFilters();
        int filter = indexOf(admin, AccessTokenAuthenticationFilter.class);

        assertThat(filter).isGreaterThanOrEqualTo(0);
        assertThat(filter).isLessThan(indexOf(admin, AnonymousAuthenticationFilter.class));
    }

    private static int indexOf(List<jakarta.servlet.Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }

    // --- The principal ---

    @Test
    void thePrincipalCarriesTheActorAndNeitherTheTokenNorAnyAuthority() {
        UUID tokenId = UUID.randomUUID();
        AuthenticatedActor actor = new AuthenticatedActor(HarnessTokens.ACCOUNT,
                HarnessTokens.INSTITUTION, HarnessTokens.SESSION, tokenId,
                Set.of(AuthenticationMethod.PASSWORD), Instant.EPOCH, Instant.EPOCH.plusSeconds(600));

        ActorAuthentication authentication = new ActorAuthentication(actor);

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo(actor);
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getAuthorities()).isEmpty();
        assertThat(authentication.getName()).isEqualTo(HarnessTokens.ACCOUNT.toString());
        assertThat(authentication.toString()).doesNotContain(tokenId.toString());
    }

    // --- Nothing of the token reaches a log ---

    @Test
    void noLogEventAtTraceCarriesAnyTokenValue() {
        String valid = process.tokens().access();
        String expired = process.tokens().accessAt(Instant.now().minus(1, ChronoUnit.HOURS));
        List<String> secrets = List.of("SECRETO-TOKEN", valid, expired, valid.split("[.]")[2]);

        List<ILoggingEvent> events = logAtTrace(() -> {
            process.get("/test/whoami", "Authorization", bearer("SECRETO-TOKEN"));
            process.get("/test/whoami", "Authorization", bearer(valid));
            process.get("/test/open", "Authorization", bearer(expired));
            process.get("/test/whoami?access_token=SECRETO-TOKEN", "X-Access-Token", "SECRETO-TOKEN");
        });

        assertThat(events).as("non-vacuous: the requests did produce events").isNotEmpty();
        assertThat(violations(events, secrets)).isEmpty();
    }

    @Test
    void theLogVerifierReportsATokenLoggedOnPurpose() {
        List<ILoggingEvent> events = logAtTrace(() -> LoggerFactory
                .getLogger("com.confia.test.negative-control").info("token {}", "SECRETO-TOKEN"));

        assertThat(violations(events, List.of("SECRETO-TOKEN"))).isNotEmpty();
    }

    private static List<ILoggingEvent> logAtTrace(Runnable requests) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        Map<Logger, Level> before = new LinkedHashMap<>();
        List<Logger> raised = new ArrayList<>(List.of(root));
        List.of("org.springframework.security", "org.apache.coyote", "org.apache.tomcat",
                "com.confia.shared.web").forEach(prefix -> raised.add(context.getLogger(prefix)));
        raised.add(context.getLogger("com.confia.test.negative-control"));
        raised.forEach(logger -> before.put(logger, logger.getLevel()));
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        raised.forEach(logger -> logger.setLevel(Level.TRACE));
        try {
            requests.run();
        } finally {
            root.detachAppender(appender);
            before.forEach(Logger::setLevel);
        }
        return new ArrayList<>(appender.list);
    }

    private static List<String> violations(List<ILoggingEvent> events, List<String> secrets) {
        List<String> found = new ArrayList<>();
        for (ILoggingEvent event : events) {
            String text = event.getFormattedMessage() + "|" + event.getMessage() + "|"
                    + java.util.Arrays.toString(event.getArgumentArray()) + "|"
                    + event.getMDCPropertyMap() + "|"
                    + (event.getThrowableProxy() == null ? ""
                            : ThrowableProxyUtil.asString(event.getThrowableProxy()));
            for (String secret : secrets) {
                if (text.contains(secret)) {
                    found.add(event.getLoggerName() + " " + event.getLevel() + " contains a secret");
                }
            }
        }
        return found;
    }
}

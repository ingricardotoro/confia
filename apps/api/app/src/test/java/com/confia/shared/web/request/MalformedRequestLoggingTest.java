package com.confia.shared.web.request;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.harness.HarnessProcess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Review S-3 of the close of web-edge-foundations (CLAUDE.md regla 11): Tomcat logs the first
 * malformed request a connection processor sees at {@code INFO}, and its message carries the whole
 * request target, query string included. Anyone can send one, with no session, so the guard denies
 * {@code INFO} of that one logger too. Each test starts its own process, because Tomcat writes that
 * line at {@code INFO} only once per processor and at {@code DEBUG} after it.
 */
class MalformedRequestLoggingTest {

    private static final String PROCESSOR = "org.apache.coyote.http11.Http11Processor";
    private static final String QUERY = "SECRETO-E";

    /** A target with a character Tomcat refuses, so the request never reaches the application. */
    private static String sendMalformed(HarnessProcess process) {
        try (Socket socket = new Socket("localhost", process.port())) {
            socket.getOutputStream().write(("GET /x?token=" + QUERY + "^ HTTP/1.1\r\n"
                    + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<ILoggingEvent> logOfAMalformedRequest(HarnessProcess process) {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        try {
            assertThat(sendMalformed(process)).startsWith("HTTP/1.1 400");
        } finally {
            root.detachAppender(appender);
        }
        return new ArrayList<>(appender.list);
    }

    @Test
    void theQueryStringOfAMalformedRequestNeverReachesTheLog() {
        try (HarnessProcess process = HarnessProcess.start()) {
            List<ILoggingEvent> events = logOfAMalformedRequest(process);

            assertThat(SensitiveDataLoggingTest.violations(events, List.of(QUERY))).isEmpty();
        }
    }

    @Test
    void withoutTheGuardTheFirstMalformedRequestDoesLeakAtInfo() {
        try (HarnessProcess process = HarnessProcess.start()) {
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            SensitiveLogGuard guard = process.context().getBean(SensitiveLogGuard.class);
            context.getTurboFilterList().remove(guard);
            try {
                List<String> leaks = SensitiveDataLoggingTest.violations(
                        logOfAMalformedRequest(process), List.of(QUERY));

                assertThat(leaks).as("non-vacuous: Tomcat does write the target at INFO")
                        .anyMatch(leak -> leak.startsWith(PROCESSOR + " INFO"));
            } finally {
                context.addTurboFilter(guard);
            }
        }
    }

    @Test
    void onlyTheInfoOfTheProcessorIsDeniedAndWarnStillPasses() {
        assertThat(SensitiveLogGuard.denies(Level.INFO, PROCESSOR)).isTrue();
        assertThat(SensitiveLogGuard.denies(Level.WARN, PROCESSOR)).isFalse();
        assertThat(SensitiveLogGuard.denies(Level.ERROR, PROCESSOR)).isFalse();
        assertThat(SensitiveLogGuard.denies(Level.INFO, "org.apache.coyote.http11.Http11NioProtocol"))
                .as("the start and stop lines of the connector still pass").isFalse();
        assertThat(SensitiveLogGuard.denies(Level.INFO, PROCESSOR + "x")).isFalse();
    }
}

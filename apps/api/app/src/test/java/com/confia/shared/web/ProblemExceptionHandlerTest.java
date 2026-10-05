package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.DomainException;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemExceptionHandler;
import com.confia.shared.web.problem.ProblemResponses;
import java.io.IOException;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.List;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The branches of {@link ProblemExceptionHandler} that the harness cannot reach with a real client:
 * a response that is already committed, and the shapes in which a vanished client shows up. The
 * answers on the wire are proven in {@code ProblemTranslationTest}.
 */
class ProblemExceptionHandlerTest {

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ProblemExceptionHandler.class);
    private Level levelBefore;

    private final ProblemExceptionHandler handler = new ProblemExceptionHandler(
            new ProblemResponses(catalog()));

    private static StaticMessageSource catalog() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ProblemCode code : ProblemCode.values()) {
            messages.addMessage(code.titleKey(), java.util.Locale.forLanguageTag("es-HN"), "title");
            messages.addMessage(code.detailKey(), java.util.Locale.forLanguageTag("es-HN"), "detail");
        }
        return messages;
    }

    @BeforeEach
    void capture() {
        levelBefore = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
        logger.setLevel(levelBefore);
    }

    @Test
    void aResponseThatIsAlreadyCommittedIsNotWrittenToAndTheExceptionIsStillLogged()
            throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.setCommitted(true);

        handler.unexpected(new IllegalStateException("half the body already left"),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(logged.list.get(0).getThrowableProxy().getMessage())
                .isEqualTo("half the body already left");
    }

    /**
     * An I/O error is a vanished client only when the server's own write side says so, by the type
     * that wraps it. The same words come out of a database, a cache or a mail server whose
     * connection dropped, and answering that as a client that left would hand a live caller an
     * empty {@code 2xx} for an operation that failed.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Broken pipe", "Connection reset by peer"})
    void anIoErrorIsNeverAVanishedClientWhateverItsTextSays(String message) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new IOException(message), new MockHttpServletRequest("GET", "/x"),
                response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error").doesNotContain(message);
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void aDatabaseFailureWhoseRootCauseSaysConnectionResetIsAnInternalError() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Exception failure = new SQLException("could not read the ledger",
                new SocketException("Connection reset by peer"));

        handler.unexpected(failure, new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error");
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void aClientAbortOfTheContainerAnywhereInTheCauseChainIsAVanishedClient() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        Exception failure = new IllegalStateException("could not write",
                new ClientAbortException(new IOException("Broken pipe")));

        handler.unexpected(failure, new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).as("never a 2xx for a response nobody received")
                .isEqualTo(ProblemExceptionHandler.CLIENT_CLOSED_REQUEST);
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG);
        assertThat(logged.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void securityExceptionsAreRethrownForTheSecurityChainToAnswer() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");

        assertThatThrownBy(() -> handler.unexpected(new AccessDeniedException("no"), request,
                new MockHttpServletResponse())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> handler.unexpected(new BadCredentialsException("no"), request,
                new MockHttpServletResponse())).isInstanceOf(BadCredentialsException.class);
        assertThat(logged.list).as("not an unforeseen failure: nothing logged as an error").isEmpty();
    }

    @Test
    void everyHandlerLeavesACommittedResponseAlone() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");

        handler.noResource(new NoResourceFoundException(HttpMethod.GET, "/x", "x"), request,
                response);
        handler.unreadableRequest(new IllegalStateException("x"), request, response);
        handler.domain(new TestDomainException("forbidden"), request, response);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void aDomainErrorWhoseCodeIsAServerErrorIsLoggedAtErrorAndAnUnknownCodeIsNamedInTheLog()
            throws IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse server = new MockHttpServletResponse();
        MockHttpServletResponse unknown = new MockHttpServletResponse();
        MockHttpServletResponse client = new MockHttpServletResponse();

        handler.domain(new TestDomainException("internal-error"), request, server);
        handler.domain(new TestDomainException("payment-frozen"), request, unknown);
        handler.domain(new TestDomainException("forbidden"), request, client);

        assertThat(server.getStatus()).isEqualTo(500);
        assertThat(unknown.getStatus()).isEqualTo(500);
        assertThat(unknown.getContentAsString()).doesNotContain("payment-frozen");
        assertThat(client.getStatus()).isEqualTo(403);
        assertThat(logged.list).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.ERROR, Level.ERROR);
        assertThat(logged.list.get(1).getFormattedMessage()).contains("payment-frozen");
    }

    /** A business error with a code of the caller's choosing. */
    private static final class TestDomainException extends DomainException {
        TestDomainException(String code) {
            super(code, "internal: row 8841");
        }
    }

    @Test
    void anIoErrorThatIsNotAVanishedClientIsAnUnforeseenFailure() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new IOException("No space left on device"),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error")
                .doesNotContain("space");
        assertThat(logged.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    @Test
    void theAsyncRequestNotUsableExceptionOfSpringIsAVanishedClient() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new AsyncRequestNotUsableException("ServletOutputStream failed"),
                new MockHttpServletRequest("GET", "/x"), response);

        assertThat(response.getStatus()).isEqualTo(ProblemExceptionHandler.CLIENT_CLOSED_REQUEST);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(List.copyOf(logged.list)).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG);
    }
}

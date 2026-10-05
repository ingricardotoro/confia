package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemExceptionHandler;
import com.confia.shared.web.problem.ProblemResponses;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

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

    @ParameterizedTest
    @ValueSource(strings = {"Broken pipe", "Connection reset by peer"})
    void aVanishedClientIsRecognizedByTheTextOfItsIoErrorToo(String message) throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.unexpected(new IOException(message), new MockHttpServletRequest("GET", "/x"),
                response);

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
        assertThat(logged.list.get(0).getThrowableProxy()).isNull();
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

        assertThat(response.getContentAsString()).isEmpty();
        assertThat(List.copyOf(logged.list)).extracting(ILoggingEvent::getLevel)
                .containsExactly(Level.DEBUG);
    }
}

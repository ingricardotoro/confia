package com.confia.shared.web.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.problem.ProblemResponses;
import java.util.List;
import org.apache.catalina.Container;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;

/**
 * The parts of {@link ProblemErrorReportValve} that the real processes cannot reach: an answer
 * the client never receives, and an installation on a container that is not a standard host (the
 * suggestions S1 and S2 of the independent review of task 2.2a). The log event of an answered
 * rejection is proven on the real processes in {@code ContainerRejectionsTest}.
 */
class ProblemErrorReportValveLogTest {

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(ProblemErrorReportValve.class);

    @BeforeEach
    void capture() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logged);
    }

    @Test
    void aClientThatWentAwayLeavesOneFixedEventWithNoDataAndNoException() {
        Request request = mock(Request.class);
        Response response = mock(Response.class);
        when(response.getStatus()).thenReturn(414);
        when(response.setErrorReported()).thenReturn(true);
        doThrow(new IllegalStateException("jdbc:postgresql://host/db password=x"))
                .when(response).resetBuffer();
        ProblemErrorReportValve valve =
                new ProblemErrorReportValve(new ProblemResponses(new StaticMessageSource()));

        assertThatCode(() -> valve.report(request, response, null)).doesNotThrowAnyException();

        List<ILoggingEvent> events = logged.list;
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getFormattedMessage())
                .isEqualTo("container rejection could not be answered: the response was closed");
        assertThat(events.get(0).getArgumentArray()).isNull();
        assertThat(events.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void installingOnAnythingButAStandardHostFailsTheStartNamingTheProblem() {
        ProblemResponses problems = new ProblemResponses(new StaticMessageSource());

        assertThatThrownBy(() -> ProblemErrorReportValve.install(mock(Container.class), problems))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("can only be replaced on a StandardHost");
        assertThatThrownBy(() -> ProblemErrorReportValve.install(null, problems))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent is absent");
    }
}

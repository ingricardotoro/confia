package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.RequestContextFilter;
import com.confia.shared.web.request.SecurityHeadersFilter;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Specs/web-edge, "El identificador de petición lo genera el servidor" (design.md decision 11),
 * without a Spring context: what only a unit can pin down. The filter owns the {@code requestId}
 * logging key, so a value that was already there is replaced for the request and the key is empty
 * when the request ends; a pooled thread therefore never carries an id to the next request. The
 * last resort rethrows when the response is committed, writes nothing internal otherwise, and the
 * filter runs right after the security headers filter and ahead of the security chain.
 */
class RequestContextFilterUnitTest {

    private static final String SECRET_MESSAGE = "jdbc:postgresql://host/db password=hunter2";

    private final RequestContextFilter filter = new RequestContextFilter(writer());
    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestContextFilter.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureTheFilterLog() {
        logged.start();
        logger.addAppender(logged);
        MDC.remove(RequestContextFilter.MDC_KEY);
    }

    @AfterEach
    void releaseTheFilterLog() {
        logger.detachAppender(logged);
        MDC.remove(RequestContextFilter.MDC_KEY);
    }

    private static ProblemResponses writer() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ProblemCode code : ProblemCode.values()) {
            messages.addMessage(code.titleKey(), Locale.forLanguageTag("es-HN"), "title");
            messages.addMessage(code.detailKey(), Locale.forLanguageTag("es-HN"), "detail");
        }
        return new ProblemResponses(messages);
    }

    private void run(MockHttpServletRequest request, MockHttpServletResponse response,
            FilterChain chain) throws Exception {
        filter.doFilter(request, response, chain);
    }

    @Test
    void insideTheChainTheLogContextAndTheRequestAttributeHoldTheSameServerUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        List<String> seen = new ArrayList<>();

        run(request, new MockHttpServletResponse(), (req, res) -> {
            seen.add(MDC.get(RequestContextFilter.MDC_KEY));
            seen.add((String) req.getAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE));
        });

        assertThat(seen).hasSize(2);
        assertThat(UUID.fromString(seen.get(0))).isNotNull();
        assertThat(seen.get(1)).isEqualTo(seen.get(0));
    }

    @Test
    void theLogContextIsEmptyAfterASuccessfulChain() throws Exception {
        run(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
                (req, res) -> assertThat(MDC.get(RequestContextFilter.MDC_KEY)).isNotNull());

        assertThat(MDC.get(RequestContextFilter.MDC_KEY)).isNull();
    }

    @Test
    void theLogContextIsEmptyAfterAChainThatThrows() throws Exception {
        run(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException(SECRET_MESSAGE);
        });

        assertThat(MDC.get(RequestContextFilter.MDC_KEY)).isNull();
    }

    @Test
    void aForeignValueUnderTheKeyIsReplacedForTheRequestAndGoneAfterwards() throws Exception {
        MDC.put(RequestContextFilter.MDC_KEY, "foreign-value");
        List<String> seen = new ArrayList<>();

        run(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
                (req, res) -> seen.add(MDC.get(RequestContextFilter.MDC_KEY)));

        assertThat(seen).hasSize(1);
        assertThat(seen.get(0)).isNotEqualTo("foreign-value");
        assertThat(UUID.fromString(seen.get(0))).isNotNull();
        assertThat(MDC.get(RequestContextFilter.MDC_KEY)).as("the filter's id does not remain")
                .isNull();
    }

    @Test
    void aCommittedResponseGetsTheSameExceptionBackAndNoBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException(SECRET_MESSAGE);

        assertThatThrownBy(() -> run(new MockHttpServletRequest("GET", "/x"), response,
                (req, res) -> {
                    res.flushBuffer();
                    throw failure;
                })).isSameAs(failure);

        assertThat(response.isCommitted()).isTrue();
        assertThat(response.getContentAsString()).as("nothing is written over a sent response")
                .isEmpty();
    }

    @Test
    void anUncommittedResponseBecomesAnInternalErrorWithNothingInternalInIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(new MockHttpServletRequest("GET", "/x"), response, (req, res) -> {
            throw new IOException(SECRET_MESSAGE);
        });

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("internal-error")
                .doesNotContain(SECRET_MESSAGE).doesNotContain("hunter2")
                .doesNotContain("IOException").doesNotContain("java.io");
    }

    @Test
    void theFullExceptionGoesToTheServerLogAndNotToTheClient() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException(SECRET_MESSAGE);

        run(new MockHttpServletRequest("GET", "/x"), response, (req, res) -> {
            throw failure;
        });

        assertThat(response.getContentAsString()).doesNotContain(SECRET_MESSAGE)
                .doesNotContain("IllegalStateException");
        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(logged.list.get(0).getThrowableProxy().getMessage()).isEqualTo(SECRET_MESSAGE);
    }

    @Test
    void itRunsRightAfterTheSecurityHeadersFilterAndAheadOfTheSecurityChain() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 10);
        assertThat(filter.getOrder()).isGreaterThan(new SecurityHeadersFilter().getOrder());
        // Spring Boot registers the security filter chain proxy at order -100.
        assertThat(filter.getOrder()).isLessThan(-100);
    }
}

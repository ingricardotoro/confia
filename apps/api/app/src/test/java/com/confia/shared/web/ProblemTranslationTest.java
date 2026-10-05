package com.confia.shared.web;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.harness.HarnessProcess;
import com.confia.shared.web.problem.ProblemExceptionHandler;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, "Las respuestas de error no exponen detalles internos", "Un fallo de validación
 * produce {@code validation-failed} sin repetir los valores rechazados" and "Catálogo de códigos de
 * error y estados HTTP" (web-edge-foundations design.md, decisions 8 to 10): every exception a
 * controller can raise, through the real security chain and the real translator, and what a client
 * sees of it. The translator logs before it writes, so what a test reads from the log was logged
 * before the response reached the client and no wait is needed.
 */
class ProblemTranslationTest {

    private static final String SENSITIVE = HarnessProcess.SENSITIVE_VALUE;
    private static final String JSON_TYPE = "application/json";

    private static HarnessProcess process;

    @BeforeAll
    static void start() {
        process = HarnessProcess.start();
    }

    @AfterAll
    static void stop() {
        process.close();
    }

    private static HttpResponse<String> post(String body, String contentType) {
        return process.sendWithBody("POST", "/test/validated", body, "Content-Type", contentType);
    }

    private static HttpResponse<String> postJson(String body) {
        return post(body, JSON_TYPE);
    }

    private static String everythingOf(HttpResponse<String> response) {
        return response.body() + " " + response.headers().map();
    }

    /** The events {@link ProblemExceptionHandler} logged while {@code action} ran, at DEBUG and up. */
    private static List<ILoggingEvent> loggedWhile(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ProblemExceptionHandler.class);
        Level before = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(before);
        }
        synchronized (appender) {
            return new ArrayList<>(appender.list);
        }
    }

    private static void assertNoViolationList(JsonNode body) {
        assertThat(body.has("errors")).as("the response has no errors member").isFalse();
    }

    @Test
    void aTruncatedBodyAnswers400WithoutTheParserMessageAndWithoutAViolationList() {
        HttpResponse<String> response = postJson("{\"name\": \"" + SENSITIVE);

        JsonNode problem = assertProblem(response, 400, "validation-failed");
        assertNoViolationList(problem);
        assertThat(problem.get("instance").asString())
                .as("answered by the translator, which repeats the route, and not by the container")
                .isEqualTo("/test/validated");
        assertThat(everythingOf(response)).doesNotContain(SENSITIVE)
                .doesNotContainIgnoringCase("unexpected").doesNotContainIgnoringCase("end-of-input")
                .doesNotContainIgnoringCase("jackson").doesNotContainIgnoringCase("parse")
                .doesNotContain("line:").doesNotContain("column");
    }

    @Test
    void aMissingHeaderAMissingParameterAndATypeMismatchAreAllValidationFailures() {
        HttpResponse<String> noHeader = process.get("/test/required?count=1");
        HttpResponse<String> noParameter = process.get("/test/required", "X-Needed", "a");
        HttpResponse<String> notANumber = process.get("/test/required?count=" + SENSITIVE,
                "X-Needed", "a");

        for (HttpResponse<String> response : List.of(noHeader, noParameter, notANumber)) {
            JsonNode problem = assertProblem(response, 400, "validation-failed");
            assertNoViolationList(problem);
            assertThat(problem.get("instance").asString()).as("answered by the translator")
                    .isEqualTo("/test/required");
            assertThat(everythingOf(response)).doesNotContain(SENSITIVE)
                    .doesNotContain("X-Needed").doesNotContainIgnoringCase("NumberFormat")
                    .doesNotContainIgnoringCase("convert").doesNotContain("java.");
        }
        assertThat(process.get("/test/required?count=1", "X-Needed", "a").statusCode())
                .as("non-vacuous: the same route with both inputs is accepted").isEqualTo(200);
    }

    @Test
    void theFirewallRejectionCarriesNoViolationListAndNoRawRejectedBytes() {
        HttpResponse<String> response = process.get("/x%0aSet-Cookie:%20a=b;c?token=" + SENSITIVE);

        JsonNode problem = assertProblem(response, 400, "validation-failed");
        assertNoViolationList(problem);
        assertThat(problem.get("instance").asString())
                .as("the path as the client wrote it, undecoded and without the query")
                .isEqualTo("/x%0aSet-Cookie:%20a=b;c");
        assertThat(response.body()).as("no raw control character in the JSON").doesNotContain("\n")
                .doesNotContain("\r");
        assertThat(everythingOf(response)).doesNotContain(SENSITIVE);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    // ---- unsupported media type and unknown resources ----------------------------------------

    @Test
    void aContentTypeTheRouteDoesNotConsumeAnswers415WithoutNamingIt() {
        HttpResponse<String> response = post("hello", "text/plain");

        JsonNode problem = assertProblem(response, 415, "unsupported-media-type");
        assertNoViolationList(problem);
        assertThat(everythingOf(response)).doesNotContain("text/plain")
                .doesNotContain(JSON_TYPE).doesNotContain("HttpMediaType")
                .doesNotContainIgnoringCase("content-type '");
        assertBaseSecurityHeaders(response);
        assertThat(postJson("{\"name\":\"ok\"}").statusCode())
                .as("non-vacuous: the media type the route does consume is accepted").isEqualTo(200);
    }

    // ---- no internal detail ------------------------------------------------------------------

    @Test
    void anUnforeseenExceptionAnswers500WithNothingInternalInTheBodyOrTheHeaders() {
        HttpResponse<String> response = process.get("/test/boom");

        JsonNode problem = assertProblem(response, 500, "internal-error");
        assertNoViolationList(problem);
        assertThat(everythingOf(response)).doesNotContain("jdbc").doesNotContain("password")
                .doesNotContain("IllegalStateException").doesNotContain("java.")
                .doesNotContain("com.confia").doesNotContain("HarnessController")
                .doesNotContain("\tat ").doesNotContainIgnoringCase("stack");
        assertBaseSecurityHeaders(response);
    }

    @Test
    void theTechnicalDetailStaysInTheServerLogUnderTheIdTheResponseCarries() {
        List<ILoggingEvent> events;
        List<HttpResponse<String>> answers = new ArrayList<>();
        events = loggedWhile(() -> answers.add(process.get("/test/boom")));

        String traceId = HarnessProcess.json(answers.get(0)).get("traceId").asString();
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getMDCPropertyMap()).containsEntry("requestId", traceId);
        assertThat(event.getThrowableProxy().getClassName())
                .isEqualTo("java.lang.IllegalStateException");
        assertThat(event.getThrowableProxy().getMessage()).contains("jdbc:postgresql");
        assertThat(event.getFormattedMessage()).doesNotContain("jdbc");
    }

    @Test
    void aDomainExceptionTheCatalogKnowsKeepsItsCodeAndNeverItsInternalMessage() {
        HttpResponse<String> response = process.get("/test/domain-known");

        JsonNode problem = assertProblem(response, 403, "forbidden");
        assertNoViolationList(problem);
        JsonNode chain = assertProblem(process.get("/x", HarnessProcess.PRINCIPAL_HEADER, "someone"),
                403, "forbidden");
        assertThat(problem.get("detail")).as("the detail is the catalog's, the same as the chain's")
                .isEqualTo(chain.get("detail"));
        assertThat(problem.get("title")).isEqualTo(chain.get("title"));
        assertThat(everythingOf(response)).doesNotContain("0801").doesNotContain("document")
                .doesNotContain("internal").doesNotContain("KnownDomainFailure");
    }

    @Test
    void aDomainExceptionOfAnUnknownCodeIs500InternalErrorWithoutItsCodeAndLoggedInFull() {
        List<HttpResponse<String>> answers = new ArrayList<>();
        List<ILoggingEvent> events = loggedWhile(() -> answers.add(process.get("/test/domain-unknown")));
        HttpResponse<String> response = answers.get(0);

        JsonNode problem = assertProblem(response, 500, "internal-error");
        assertNoViolationList(problem);
        assertThat(everythingOf(response)).doesNotContain("payment-frozen").doesNotContain("ledger")
                .doesNotContain("8841").doesNotContain("UnknownDomainFailure");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getLevel()).isEqualTo(Level.ERROR);
        assertThat(events.get(0).getThrowableProxy().getMessage()).contains("ledger row 8841");
        assertThat(events.get(0).getMDCPropertyMap())
                .containsEntry("requestId", problem.get("traceId").asString());
    }

    // ---- client disconnect -------------------------------------------------------------------

    @Test
    void aClientThatWentAwayGetsNothingWrittenAndLeavesOneDebugEventWithNoData() {
        List<HttpResponse<String>> answers = new ArrayList<>();
        List<ILoggingEvent> events = loggedWhile(() -> answers.add(process.get("/test/disconnected")));
        HttpResponse<String> response = answers.get(0);

        assertThat(response.body()).as("nothing was written to the response").isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).isEmpty();
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(event.getThrowableProxy()).as("no exception, so no stack").isNull();
        assertThat(event.getArgumentArray()).isNull();
        assertThat(event.getFormattedMessage()).doesNotContainIgnoringCase("broken pipe")
                .doesNotContain("ServletOutputStream");
    }

    // ---- the contract ---------------------------------------------------------------------------

    @Test
    void theProblemDetailSchemaOfTheContractStillDeclaresItsSixPropertiesAndNotErrors()
            throws IOException {
        for (String document : List.of("admin", "portal")) {
            JsonNode schema = HarnessProcess.json(Files.readString(
                    Path.of("..", "openapi", document + ".openapi.json")))
                    .get("components").get("schemas").get("ProblemDetail");
            List<String> properties = new ArrayList<>(schema.get("properties").propertyNames());

            assertThat(properties).as("%s snapshot", document).containsExactlyInAnyOrder("type",
                    "title", "status", "detail", "instance", "traceId");
        }
    }
}

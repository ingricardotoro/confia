package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.problem.FieldViolation;
import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specs/web-edge, "Toda respuesta de error usa Problem Details (RFC 9457)" (design.md decision 8):
 * the one writer both the security chain and, later, the MVC translator use. The body is built
 * from the catalog and the request, never from an exception.
 */
class ProblemResponsesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REQUEST_ID = "3f0c9a1e-7b24-4d58-9a3b-1c2d3e4f5a6b";

    private static ProblemResponses writer() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ProblemCode code : ProblemCode.values()) {
            messages.addMessage(code.titleKey(), java.util.Locale.forLanguageTag("es-HN"),
                    "title of " + code.code());
            messages.addMessage(code.detailKey(), java.util.Locale.forLanguageTag("es-HN"),
                    "detail of " + code.code());
        }
        return new ProblemResponses(messages);
    }

    private static MockHttpServletRequest request(String uri, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setQueryString(query);
        request.setAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE, REQUEST_ID);
        return request;
    }

    @Test
    void writesTheFiveRfcFieldsAndTheTraceIdWithTheStatusOfTheCode() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().write(request("/x", null), response, ProblemCode.FORBIDDEN);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.get("type").asString()).isEqualTo("https://confia.hn/problems/forbidden");
        assertThat(body.get("title").asString()).isEqualTo("title of forbidden");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("detail").asString()).isEqualTo("detail of forbidden");
        assertThat(body.get("instance").asString()).isEqualTo("/x");
        assertThat(body.get("traceId").asString()).isEqualTo(REQUEST_ID);
    }

    @Test
    void theStatusFollowsTheCodeAndNotAFixedValue() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().write(request("/y", null), response, ProblemCode.AUTHENTICATION_REQUIRED);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(JSON.readTree(response.getContentAsString()).get("status").asInt())
                .isEqualTo(401);
    }

    @Test
    void theQueryStringNeverReachesTheInstance() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().write(request("/x", "token=secreto"), response, ProblemCode.FORBIDDEN);

        assertThat(response.getContentAsString()).doesNotContain("token").doesNotContain("secreto");
        assertThat(JSON.readTree(response.getContentAsString()).get("instance").asString())
                .isEqualTo("/x");
    }

    @Test
    void anOverlongInstanceIsCutAt1024Characters() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().write(request("/" + "a".repeat(3000), null), response, ProblemCode.FORBIDDEN);

        assertThat(JSON.readTree(response.getContentAsString()).get("instance").asString())
                .hasSize(1024);
    }

    @Test
    void theLanguageIsTheCatalogsWhateverAcceptLanguageSays() throws IOException {
        // An English entry exists on purpose: if the writer resolved the request locale instead of
        // the fixed es-HN one, the English text would win and this test would fail.
        StaticMessageSource catalog = new StaticMessageSource();
        String key = ProblemCode.AUTHENTICATION_REQUIRED.detailKey();
        String expected = "detalle es-HN";
        catalog.addMessage(key, java.util.Locale.forLanguageTag("es-HN"), expected);
        catalog.addMessage(key, java.util.Locale.US, "english detail");
        catalog.addMessage(key, java.util.Locale.ENGLISH, "english detail");
        catalog.addMessage(ProblemCode.AUTHENTICATION_REQUIRED.titleKey(),
                java.util.Locale.forLanguageTag("es-HN"), "título es-HN");
        catalog.addMessage(ProblemCode.AUTHENTICATION_REQUIRED.titleKey(), java.util.Locale.US,
                "english title");
        catalog.addMessage(ProblemCode.AUTHENTICATION_REQUIRED.titleKey(), java.util.Locale.ENGLISH,
                "english title");
        MockHttpServletRequest english = request("/x", null);
        english.addHeader("Accept-Language", "en-US");
        english.addPreferredLocale(java.util.Locale.US);
        MockHttpServletResponse withHeader = new MockHttpServletResponse();
        MockHttpServletResponse without = new MockHttpServletResponse();

        new ProblemResponses(catalog).write(english, withHeader,
                ProblemCode.AUTHENTICATION_REQUIRED);
        new ProblemResponses(catalog).write(request("/x", null), without,
                ProblemCode.AUTHENTICATION_REQUIRED);

        assertThat(expected).isNotBlank();
        assertThat(JSON.readTree(withHeader.getContentAsString()).get("detail").asString())
                .isEqualTo(expected);
        assertThat(JSON.readTree(without.getContentAsString()).get("detail").asString())
                .isEqualTo(expected);
    }

    @Test
    void aRequestWithoutAnIdStillGetsAWellFormedTraceId() throws IOException {
        MockHttpServletRequest bare = new MockHttpServletRequest("GET", "/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().write(bare, response, ProblemCode.INTERNAL_ERROR);

        String traceId = JSON.readTree(response.getContentAsString()).get("traceId").asString();
        assertThat(java.util.UUID.fromString(traceId)).isNotNull();
    }

    @Test
    void errorsIsOmittedWhenThereAreNoViolations() throws IOException {
        MockHttpServletResponse without = new MockHttpServletResponse();
        MockHttpServletResponse empty = new MockHttpServletResponse();

        writer().write(request("/x", null), without, ProblemCode.INTERNAL_ERROR);
        writer().write(request("/x", null), empty, ProblemCode.VALIDATION_FAILED, List.of());

        assertThat(JSON.readTree(without.getContentAsString()).has("errors"))
                .as("a response without a violation list has no errors member").isFalse();
        assertThat(JSON.readTree(empty.getContentAsString()).has("errors"))
                .as("an empty list is the same as none: the member is omitted").isFalse();
        assertThat(empty.getContentAsString()).doesNotContain("errors");
    }

    @Test
    void errorsListsTheFieldAndTheReasonOfEveryViolationAndNothingElse() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer().write(request("/x", null), response, ProblemCode.VALIDATION_FAILED,
                List.of(new FieldViolation("name", "not-blank"),
                        new FieldViolation("items[0].quantity", "min")));

        assertThat(response.getStatus()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.getContentAsString());
        JsonNode errors = body.get("errors");
        assertThat(errors.size()).isEqualTo(2);
        assertThat(errors.get(0).get("field").asString()).isEqualTo("name");
        assertThat(errors.get(0).get("reason").asString()).isEqualTo("not-blank");
        assertThat(errors.get(1).get("field").asString()).isEqualTo("items[0].quantity");
        assertThat(errors.get(1).get("reason").asString()).isEqualTo("min");
        assertThat(errors.get(0).size()).as("only field and reason").isEqualTo(2);
        assertThat(body.get("type").asString())
                .isEqualTo("https://confia.hn/problems/validation-failed");
        assertThat(body.get("traceId").asString()).isEqualTo(REQUEST_ID);
    }

    @ParameterizedTest
    @CsvSource({"NotBlank, not-blank", "Size, size", "Pattern, pattern", "DecimalMin, decimal-min",
            "NotEmpty, not-empty", "AssertTrue, assert-true", "Email, email",
            "PositiveOrZero, positive-or-zero"})
    void theReasonIsTheSimpleNameOfTheConstraintInKebabCase(String constraint, String reason) {
        assertThat(FieldViolation.of("f", constraint).reason()).isEqualTo(reason);
    }
}

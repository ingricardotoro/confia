package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import java.io.IOException;
import org.junit.jupiter.api.Test;
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
        org.springframework.context.support.ResourceBundleMessageSource catalog =
                new org.springframework.context.support.ResourceBundleMessageSource();
        catalog.setBasename("i18n/problems");
        catalog.setDefaultEncoding("UTF-8");
        catalog.setFallbackToSystemLocale(false);
        String expected = ProblemCatalogCoverageTest.loadCatalog()
                .getProperty(ProblemCode.AUTHENTICATION_REQUIRED.detailKey());
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
}

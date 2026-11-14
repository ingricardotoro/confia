package com.confia.shared.web.request;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.problem.ProblemCode;
import com.confia.shared.web.problem.ProblemResponses;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The multipart scope: an upload only where a module declared it, refused with 415 elsewhere without reading it. */
class MultipartScopeFilterTest {

    private static final String IMPORTS = "/api/v1/imports/students/";

    private final MultipartScopeFilter filter = new MultipartScopeFilter(problems(), List.of(IMPORTS));

    private static ProblemResponses problems() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ProblemCode code : ProblemCode.values()) {
            messages.addMessage(code.titleKey(), Locale.forLanguageTag("es-HN"), "t");
            messages.addMessage(code.detailKey(), Locale.forLanguageTag("es-HN"), "d");
        }
        return new ProblemResponses(messages);
    }

    private MockFilterChain run(MockHttpServletRequest request, MockHttpServletResponse response)
            throws ServletException, IOException {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain;
    }

    private static MockHttpServletRequest post(String path, String contentType) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setContentType(contentType);
        return request;
    }

    @Test
    void multipartOnADeclaredPathPassesToTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain chain = run(post(IMPORTS + "simulate", "multipart/form-data; boundary=x"), response);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void multipartOnAnyOtherPathIsA415AndTheChainNeverSeesIt() throws Exception {
        for (String type : List.of("multipart/form-data; boundary=x", "Multipart/Mixed", "MULTIPART/FORM-DATA")) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            MockFilterChain chain = run(post("/api/v1/students", type), response);

            assertThat(chain.getRequest()).as(type).isNull();
            assertThat(response.getStatus()).as(type).isEqualTo(415);
            assertThat(response.getContentType()).startsWith("application/problem+json");
        }
    }

    @Test
    void thePathIsTheNormalizedServletPathSoADotSegmentCannotBorrowAnAcceptedPrefix() throws Exception {
        MockHttpServletRequest request = post("/api/v1/students", "multipart/form-data; boundary=x");
        request.setRequestURI(IMPORTS + "../../students");
        MockHttpServletResponse response = new MockHttpServletResponse();

        run(request, response);

        assertThat(response.getStatus()).isEqualTo(415);
    }

    @Test
    void nonMultipartRequestsAreNotTouched() throws Exception {
        for (String type : new String[] {"application/json", null}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            MockFilterChain chain = run(post("/api/v1/students", type), response);

            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void withoutDeclaredPathsEveryMultipartRequestIsRefused() throws Exception {
        MultipartScopeFilter closed = new MultipartScopeFilter(problems(), List.of());
        MockHttpServletResponse response = new MockHttpServletResponse();

        closed.doFilter(post(IMPORTS + "simulate", "multipart/form-data"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(415);
    }
}

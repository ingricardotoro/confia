package com.confia.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.request.SecurityHeadersFilter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Specs/web-edge, "Cabeceras de seguridad base" (design.md decision 7): the five headers with their
 * exact values on a success and on an error response, already present when the rest of the chain
 * writes, and neither {@code X-Powered-By} nor {@code Server}.
 */
class SecurityHeadersFilterTest {

    private static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    private static void assertTheFiveHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(response.getHeaders("X-Frame-Options")).containsExactly("DENY");
        assertThat(response.getHeaders("Referrer-Policy"))
                .containsExactly("strict-origin-when-cross-origin");
        assertThat(response.getHeaders("Permissions-Policy")).containsExactly(PERMISSIONS_POLICY);
        assertThat(response.getHeaders("Cache-Control")).containsExactly("no-store");
        assertThat(response.containsHeader("X-Powered-By")).isFalse();
        assertThat(response.containsHeader("Server")).isFalse();
    }

    @Test
    void aSuccessResponseCarriesTheFiveHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/ok"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertTheFiveHeaders(response);
    }

    @Test
    void anErrorResponseCarriesTheSameFiveHeaders() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain denying = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                    jakarta.servlet.ServletResponse servletResponse) {
                ((MockHttpServletResponse) servletResponse).setStatus(401);
            }
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/denied"), response, denying);

        assertThat(response.getStatus()).isEqualTo(401);
        assertTheFiveHeaders(response);
    }

    @Test
    void theHeadersAreAlreadySetWhenTheRestOfTheChainRuns() throws Exception {
        List<String> seenByTheChain = new ArrayList<>();
        MockFilterChain inspecting = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request,
                    jakarta.servlet.ServletResponse servletResponse) {
                seenByTheChain.add(((MockHttpServletResponse) servletResponse)
                        .getHeader("Cache-Control"));
            }
        };

        filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
                inspecting);

        assertThat(seenByTheChain).containsExactly("no-store");
    }

    @Test
    void itRunsBeforeEveryOtherFilter() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}

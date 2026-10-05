package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/web-edge, "Cabeceras de seguridad base" on the real processes (web-edge-foundations
 * design.md decisions 1 and 7): the administrative and the portal process, started through the
 * production launcher, answer with the five base headers, which proves the filters are registered
 * in the servlet container and run ahead of everything else. The worker has no web server and
 * imports nothing of the edge ({@code ProcessBeanIsolationTest}).
 */
class WebEdgeFiltersInProcessesTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void theRealWebProcessesAnswerWithTheBaseSecurityHeaders(String appProfile) {
        try (OpenApiProcess process = OpenApiProcess.start(appProfile, "local")) {
            HttpResponse<String> response = process.get(OpenApiProcess.API_DOCS_PATH);

            assertThat(response.statusCode()).as("non-vacuous: a real 200 response").isEqualTo(200);
            assertThat(response.headers().allValues("X-Content-Type-Options"))
                    .containsExactly("nosniff");
            assertThat(response.headers().allValues("X-Frame-Options")).containsExactly("DENY");
            assertThat(response.headers().allValues("Referrer-Policy"))
                    .containsExactly("strict-origin-when-cross-origin");
            assertThat(response.headers().allValues("Permissions-Policy")).hasSize(1);
            assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
            assertThat(response.headers().firstValue("X-Powered-By")).isEmpty();
        }
    }
}

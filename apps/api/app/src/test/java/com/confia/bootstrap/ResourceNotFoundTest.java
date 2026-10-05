package com.confia.bootstrap;

import static com.confia.shared.web.harness.ProblemAssertions.assertBaseSecurityHeaders;
import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge, scenario "Recurso inexistente bajo un prefijo de documentación" (web-edge-
 * foundations design.md, decisions 8 and 9): a file that does not exist under {@code
 * /swagger-ui/} in the administrative process with the {@code local} profile is a {@code 404
 * resource-not-found}, not the last resort's {@code 500}. It needs the real process because only
 * springdoc's own resource handler raises {@code NoResourceFoundException} here; the error page
 * auto-configuration is excluded, so without a translator nothing else would answer it.
 */
class ResourceNotFoundTest {

    private static final String MISSING = "/swagger-ui/does-not-exist.js";

    @Test
    void aMissingFileUnderTheSwaggerUiPrefixIs404ResourceNotFoundAndNotAnInternalError() {
        try (OpenApiProcess admin = OpenApiProcess.start("admin", "local")) {
            assertThat(admin.get(OpenApiProcess.SWAGGER_UI_PATH).statusCode())
                    .as("non-vacuous: the prefix is served in this profile").isEqualTo(200);

            HttpResponse<String> response = admin.get(MISSING);

            JsonNode problem = assertProblem(response, 404, "resource-not-found");
            assertThat(problem.get("instance").asString()).isEqualTo(MISSING);
            assertThat(problem.has("errors")).isFalse();
            assertThat(response.body()).doesNotContain("NoResourceFoundException")
                    .doesNotContain("org.springframework").doesNotContain("No static resource");
            assertBaseSecurityHeaders(response);
        }
    }

    @Test
    void thePortalWithTheLocalProfileServesTheSamePrefixAndAnswersTheSame404() {
        try (OpenApiProcess portal = OpenApiProcess.start("portal", "local")) {
            assertProblem(portal.get(MISSING), 404, "resource-not-found");
        }
    }

    @Test
    void withoutTheLocalProfileTheSamePathIsTheUniformDenialAndNobodyCanTellItDoesNotExist() {
        try (OpenApiProcess admin = OpenApiProcess.start("admin")) {
            assertProblem(admin.get(MISSING), 401, "authentication-required");
        }
    }
}

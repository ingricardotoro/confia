package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Specs/build-integrity, requirement "Swagger UI y el endpoint del OpenAPI solo en local y
 * preproducción" (frontend-monorepo-and-contracts-pipeline, design.md decision 3; task 1.2). Both
 * web processes, through their real entry points: the document and Swagger UI answer with the
 * {@code local} and {@code preprod} profiles and with no other, including {@code prod} and a
 * profile that exists nowhere, which is what a typo in {@code SPRING_PROFILES_ACTIVE} would
 * produce.
 */
class OpenApiExposureByProfileTest {

    @ParameterizedTest
    @CsvSource({"admin, local", "portal, local", "admin, preprod", "portal, preprod"})
    void theDocumentAndSwaggerUiAnswerOnTheLocalAndPreprodProfiles(String appProfile,
            String springProfile) {
        try (OpenApiProcess process = OpenApiProcess.start(appProfile, springProfile)) {
            HttpResponse<String> document = process.get(OpenApiProcess.API_DOCS_PATH);
            assertThat(document.statusCode()).isEqualTo(200);
            assertThat(document.body())
                    .as("an OpenAPI 3.1 document, not merely any 200 response")
                    .contains("\"openapi\":\"3.1");

            assertThat(process.get(OpenApiProcess.SWAGGER_UI_PATH).statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @CsvSource({"admin, prod", "portal, prod", "admin, a-profile-that-exists-nowhere",
            "portal, a-profile-that-exists-nowhere"})
    void neitherTheDocumentNorSwaggerUiAnswersOnAnyOtherProfile(String appProfile,
            String springProfile) {
        try (OpenApiProcess process = OpenApiProcess.start(appProfile, springProfile)) {
            assertDenied(process.get(OpenApiProcess.API_DOCS_PATH),
                    "the OpenAPI document must not be served with the " + springProfile
                            + " profile");
            assertDenied(process.get(OpenApiProcess.SWAGGER_UI_PATH),
                    "Swagger UI must not be served with the " + springProfile + " profile");
        }
    }

    /**
     * The security chain answers before springdoc is ever asked (web-edge-foundations design.md,
     * decision 6): a uniform {@code 401} with Problem Details, which is also what any route that
     * does not exist gets, so neither the document nor the interface is served and nobody can tell
     * whether they exist.
     */
    private static void assertDenied(HttpResponse<String> response, String reason) {
        assertThat(response.statusCode()).as(reason).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).as(reason)
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).as(reason)
                .contains("\"type\":\"https://confia.hn/problems/authentication-required\"")
                .doesNotContain("\"openapi\"")
                .doesNotContain("swagger-ui-bundle");
    }
}

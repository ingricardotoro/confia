package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Specs/build-integrity, requirement "Swagger UI y el endpoint del OpenAPI solo en local y
 * preproducción" (frontend-monorepo-and-contracts-pipeline, design.md decision 3; task 1.2). Both
 * web processes, through their real entry points: the document and Swagger UI answer with the
 * {@code local} profile and with no other, including {@code prod} and a profile that exists
 * nowhere, which is what a typo in {@code SPRING_PROFILES_ACTIVE} would produce.
 */
class OpenApiExposureByProfileTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void theDocumentAndSwaggerUiAnswerOnTheLocalProfile(String appProfile) {
        try (OpenApiProcess process = OpenApiProcess.start(appProfile, "local")) {
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
            assertThat(process.get(OpenApiProcess.API_DOCS_PATH).statusCode())
                    .as("the OpenAPI document must not be served with the %s profile",
                            springProfile)
                    .isEqualTo(404);
            assertThat(process.get(OpenApiProcess.SWAGGER_UI_PATH).statusCode())
                    .as("Swagger UI must not be served with the %s profile", springProfile)
                    .isEqualTo(404);
        }
    }
}

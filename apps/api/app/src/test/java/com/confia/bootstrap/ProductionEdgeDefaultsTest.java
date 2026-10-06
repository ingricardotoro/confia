package com.confia.bootstrap;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import java.net.http.HttpResponse;
import java.util.Map;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.HttpMethod;

/**
 * The defaults of the production configuration that the web edge relies on, and the one coupling
 * that can open it (web-edge-foundations design.md, decisions 5 and 22; the S6 note of the review
 * of task 2.1b). Whether the API documentation exists and whether it is public are decided by the
 * same property, {@code springdoc.api-docs.enabled}: switching it on, for example with the
 * environment variable {@code SPRINGDOC_API_DOCS_ENABLED=true}, makes the documentation answer
 * without credentials, in any profile. That is intended (ADR-0013): the property is the one
 * switch, and the defaults below keep it off in production.
 */
class ProductionEdgeDefaultsTest {

    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void productionKeepsTheDocumentationOffAndNeverLogsRequestDetails(String process) {
        try (OpenApiProcess running = OpenApiProcess.start(process)) {
            var environment = running.context().getEnvironment();

            assertThat(environment.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
            assertThat(environment.getProperty("spring.mvc.log-request-details"))
                    .isEqualTo("false");
            assertThat(PublicEndpoints.forAdmin(environment).endpoints()).isEmpty();
            assertThat(PublicEndpoints.forPortal(environment).endpoints()).isEmpty();
        }
    }

    /**
     * Specs/web-edge, "La espera no retiene hilos de plataforma del servidor" (design.md, decision
     * 18): requests run on virtual threads, and the connection times are bounded so a slow client
     * cannot hold a socket for long. Read from the running server, not only from the property.
     */
    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void productionRunsRequestsOnVirtualThreadsAndBoundsTheConnectionTimes(String process) {
        try (OpenApiProcess running = OpenApiProcess.start(process)) {
            var environment = running.context().getEnvironment();
            assertThat(environment.getProperty("spring.threads.virtual.enabled"))
                    .isEqualTo("true");
            assertThat(environment.getProperty("server.tomcat.connection-timeout"))
                    .isEqualTo("10s");
            assertThat(environment.getProperty("server.tomcat.keep-alive-timeout"))
                    .isEqualTo("20s");
            assertThat(environment.getProperty("server.tomcat.max-keep-alive-requests"))
                    .isEqualTo("100");
            assertThat(environment.getProperty("server.max-http-request-header-size"))
                    .isEqualTo("16KB");

            WebServer server = ((WebServerApplicationContext) running.context()).getWebServer();
            AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) ((TomcatWebServer)
                    server).getTomcat().getConnector().getProtocolHandler();
            assertThat(protocol.getConnectionTimeout()).isEqualTo(10_000);
            assertThat(protocol.getKeepAliveTimeout()).isEqualTo(20_000);
            assertThat(protocol.getMaxKeepAliveRequests()).isEqualTo(100);
            assertThat(protocol.getMaxHttpRequestHeaderSize()).isEqualTo(16 * 1024);
            assertThat(protocol.getExecutor().getClass().getSimpleName())
                    .as("Tomcat hands each request to a virtual thread")
                    .isEqualTo("VirtualThreadExecutor");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void theEnvironmentVariableIsWhatOpensOrKeepsClosedTheDocumentationEntry(String value) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "test-system-environment", Map.of("SPRINGDOC_API_DOCS_ENABLED", value)));

        var endpoints = PublicEndpoints.forAdmin(environment).endpoints();

        if ("true".equals(value)) {
            assertThat(endpoints).as("SPRINGDOC_API_DOCS_ENABLED=true").containsExactly(
                    new PublicEndpoint(HttpMethod.GET, "/v3/api-docs"),
                    new PublicEndpoint(HttpMethod.GET, "/v3/api-docs/swagger-config"));
        } else {
            assertThat(endpoints).as("SPRINGDOC_API_DOCS_ENABLED=false").isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "portal"})
    void withTheSwitchOnTheDocumentationOfAProductionProcessIsPublicAndNothingElseIs(
            String process) {
        try (OpenApiProcess running = OpenApiProcess.startWithArguments(process,
                "--springdoc.api-docs.enabled=true")) {
            HttpResponse<String> docs = running.get(OpenApiProcess.API_DOCS_PATH);

            assertThat(docs.statusCode()).as("the documentation, with no credential")
                    .isEqualTo(200);
            assertThat(docs.body()).contains("\"openapi\":\"3.1");
            assertProblem(running.get("/"), 401, "authentication-required");
            assertProblem(running.get("/api/v1/students"), 401, "authentication-required");
            assertProblem(running.get(OpenApiProcess.SWAGGER_UI_PATH), 401,
                    "authentication-required");
        }
    }
}

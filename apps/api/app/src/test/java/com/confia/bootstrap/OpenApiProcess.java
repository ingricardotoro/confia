package com.confia.bootstrap;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * One real web process started through {@link ConfiaApplication#launch} on a random port, with a
 * given Spring profile, for the OpenAPI tests of frontend-monorepo-and-contracts-pipeline
 * (design.md decisions 1 to 3). Starting the real entry point, rather than a test-only context, is
 * the point: the document a process publishes must be the one that process actually serves.
 *
 * <p>No database: both web entry points exclude {@code DataSourceAutoConfiguration}, so these tests
 * need no container and keep the {@code *Test} suffix ({@code IntegrationTestNamingTest}).
 */
final class OpenApiProcess implements AutoCloseable {

    static final String API_DOCS_PATH = "/v3/api-docs";
    static final String SWAGGER_UI_PATH = "/swagger-ui/index.html";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ConfigurableApplicationContext context;
    private final int port;

    private OpenApiProcess(ConfigurableApplicationContext context) {
        this.context = context;
        this.port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    /** @param appProfile {@code admin} or {@code portal}; @param springProfile e.g. {@code local} */
    static OpenApiProcess start(String appProfile, String springProfile) {
        LaunchOutcome outcome = ConfiaApplication.launch(new String[] {"--server.port=0",
                "--spring.profiles.active=" + springProfile}, appProfile);
        if (outcome.context() == null) {
            throw new IllegalStateException("the " + appProfile + " process did not start");
        }
        return new OpenApiProcess(outcome.context());
    }

    HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + path, e);
        }
    }

    @Override
    public void close() {
        context.close();
    }
}

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
 * <p>No database: the admin process starts with a lazy pool on an unreachable URL, so these tests
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
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess(appProfile,
                "--spring.profiles.active=" + springProfile), appProfile);
        return running(appProfile, outcome);
    }

    /** The process with its default configuration, which is production's: no Spring profile. */
    static OpenApiProcess start(String appProfile) {
        return running(appProfile,
                ConfiaApplication.launch(TestProcessArguments.forProcess(appProfile), appProfile));
    }

    private static OpenApiProcess running(String appProfile, LaunchOutcome outcome) {
        if (outcome.context() == null) {
            throw new IllegalStateException("the " + appProfile + " process did not start");
        }
        return new OpenApiProcess(outcome.context());
    }

    /** The running context, for tests that enumerate its routes. */
    ConfigurableApplicationContext context() {
        return context;
    }

    /** The port the process listens on. */
    int port() {
        return port;
    }

    /** The process started with {@code arguments} on top of its production configuration. */
    static OpenApiProcess startWithArguments(String appProfile, String... arguments) {
        return running(appProfile, ConfiaApplication.launch(
                TestProcessArguments.forProcess(appProfile, arguments), appProfile));
    }

    HttpResponse<String> get(String path) {
        return send("GET", path);
    }

    /** Sends {@code method} to {@code path}, which is used exactly as written. */
    HttpResponse<String> send(String method, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody())
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

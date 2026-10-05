package com.confia.shared.web.harness;

import jakarta.servlet.Filter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One {@link WebEdgeHarness} on a random port, with a plain JDK HTTP client in front of it. Raw
 * requests matter here: the path variants a firewall must reject ({@code /x;a=b}, {@code /x/.},
 * {@code /y/../x}) have to reach the server as written.
 */
public final class HarnessProcess implements AutoCloseable {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The header a test sets to be authenticated as the named principal. */
    public static final String PRINCIPAL_HEADER = TestPrincipalFilter.HEADER;

    private final ConfigurableApplicationContext context;
    private final int port;

    private HarnessProcess(ConfigurableApplicationContext context) {
        this.context = context;
        this.port = ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    /** Starts the harness with the admin allow-list and any extra {@code key=value} properties. */
    public static HarnessProcess start(String... properties) {
        return new HarnessProcess(new SpringApplicationBuilder(WebEdgeHarness.class)
                .web(WebApplicationType.SERVLET)
                .properties("server.port=0")
                .properties(properties)
                .run());
    }

    /** Starts the harness with the portal allow-list. */
    public static HarnessProcess startAsPortal(String... properties) {
        String[] all = new String[properties.length + 1];
        all[0] = WebEdgeHarness.PROCESS_PROPERTY + "=portal";
        System.arraycopy(properties, 0, all, 1, properties.length);
        return start(all);
    }

    /** What the harness controllers observed. */
    public Calls calls() {
        return context.getBean(Calls.class);
    }

    /** The filters of the real security chain, in order. */
    public List<Filter> securityFilters() {
        return context.getBean(SecurityFilterChain.class).getFilters();
    }

    /** How many HTTP sessions the container created. */
    public int sessionsCreated() {
        return context.getBean(SessionCounter.class).created();
    }

    /** Sends {@code method} to {@code path}, which is used exactly as written. */
    public HttpResponse<String> send(String method, String path, String... headerPairs) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + path, e);
        }
    }

    /** Like {@link #send}, with {@code body} as the request body. */
    public HttpResponse<String> sendWithBody(String method, String path, String body,
            String... headerPairs) {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + path, e);
        }
    }

    /** The running context, for tests that enumerate its routes. */
    public ConfigurableApplicationContext context() {
        return context;
    }

    /** The port the harness listens on. */
    public int port() {
        return port;
    }

    /** {@code GET path}. */
    public HttpResponse<String> get(String path, String... headerPairs) {
        return send("GET", path, headerPairs);
    }

    /** The body of {@code response} as a JSON tree. */
    public static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    @Override
    public void close() {
        context.close();
    }
}

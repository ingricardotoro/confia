package com.confia.shared.web.idempotency;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.InstitutionId;
import com.confia.shared.platform.infrastructure.SharedPlatformConfiguration;
import com.confia.shared.security.IdempotencyKey;
import com.confia.shared.security.IdempotencyRecord;
import com.confia.shared.security.IdempotencyRecordStore;
import com.confia.shared.security.IdempotentResponse;
import com.confia.shared.web.edge.IdempotencyEdgeConfiguration;
import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import com.confia.shared.web.edge.SecurityChains;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specs/web-edge, requirements "Cabecera {@code Idempotency-Key} obligatoria en los endpoints que
 * mueven dinero", "La repetición de una clave completada devuelve la respuesta original con
 * {@code Idempotent-Replay}", "Una escritura concurrente con la misma clave responde {@code 409}" and
 * "La misma clave con una carga útil distinta responde {@code 422}" (web-edge-foundations
 * design.md, decision 19), against a real PostgreSQL, the real {@code IdempotentExecutor} and the
 * real web server and chain on a random port. The only endpoint that applies the mechanism is the
 * demonstration controller of this test tree.
 *
 * <p>Concurrency is ordered by latches alone ({@link DemoProbe}): the first request is held inside
 * its use case, after its marker is written and before it commits, and a decorator of the record
 * store tells the test when the second request is about to write its own marker. The only waiting
 * that is not a latch is the bounded lock wait of the executor itself, which is the thing under
 * test in the {@code 409} scenario.
 */
@SpringBootTest(classes = IdempotencyEdgeIT.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyEdgeIT extends CommittingPostgresIntegrationTest {

    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String REPLAY_HEADER = "Idempotent-Replay";
    private static final String BODY = "{\"amount\":\"10.0000\",\"currency\":\"HNL\"}";
    private static final String OTHER_BODY = "{\"amount\":\"99.0000\",\"currency\":\"HNL\"}";
    private static final int MAX_KEY_LENGTH = 128;
    private static final String PROBE = "capture probe";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void masterKey(DynamicPropertyRegistry registry) {
        // A key made for this run: SharedPlatformConfiguration refuses to start without one.
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        registry.add("confia.crypto.column-master-key",
                () -> Base64.getEncoder().encodeToString(key));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DemoProbe probe;

    @BeforeEach
    void freshProbe() {
        probe.reset();
    }

    // --- The header ---

    static Stream<Arguments> keysTheEdgeRejects() {
        return Stream.of(
                Arguments.of("absent", new String[0], 400, "idempotency-key-missing"),
                Arguments.of("empty", new String[] {KEY_HEADER, ""}, 400,
                        "idempotency-key-missing"),
                Arguments.of("only spaces", new String[] {KEY_HEADER, "   "}, 400,
                        "idempotency-key-missing"),
                Arguments.of("repeated", new String[] {KEY_HEADER, "one", KEY_HEADER, "two"}, 400,
                        "validation-failed"),
                Arguments.of("one character too long",
                        new String[] {KEY_HEADER, "k".repeat(MAX_KEY_LENGTH + 1)}, 400,
                        "validation-failed"),
                Arguments.of("interior space", new String[] {KEY_HEADER, "two words"}, 400,
                        "validation-failed"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keysTheEdgeRejects")
    void aKeyThatIsAbsentOrNotAcceptableIsRefusedBeforeTheUseCaseRuns(String shape,
            String[] headers, int status, String code) {
        HttpResponse<String> answer = post(IdempotencyDemoController.PAYMENTS, UUID.randomUUID(),
                BODY, headers);

        assertProblem(answer, status, code);
        assertThat(probe.invocations()).as("the use case never ran").isZero();
    }

    /**
     * Over a raw socket, because the JDK client rewrites a character outside ASCII before it
     * reaches the wire, and the test must prove what the server does with the bytes themselves.
     */
    @Test
    void aKeyWithBytesOutsideAsciiIsRefusedBeforeTheUseCaseRuns() throws IOException {
        String crlf = "\r\n";
        byte[] head = ("POST " + IdempotencyDemoController.PAYMENTS + " HTTP/1.1" + crlf
                + "Host: localhost" + crlf + "Connection: close" + crlf
                + "Content-Type: application/json" + crlf
                + IdempotencyDemoController.INSTITUTION_HEADER + ": " + UUID.randomUUID() + crlf
                + "Content-Length: " + BODY.length() + crlf + KEY_HEADER + ": ")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] tail = (crlf + crlf + BODY).getBytes(StandardCharsets.US_ASCII);
        for (byte[] key : List.of("clavé".getBytes(StandardCharsets.UTF_8),
                "clavé".getBytes(StandardCharsets.ISO_8859_1))) {
            String answer;
            try (Socket socket = new Socket("localhost", port)) {
                OutputStream out = socket.getOutputStream();
                out.write(head);
                out.write(key);
                out.write(tail);
                out.flush();
                answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            assertThat(answer).startsWith("HTTP/1.1 400").contains("/problems/validation-failed");
        }
        assertThat(probe.invocations()).as("the use case never ran").isZero();
    }

    @Test
    void aKeyOfExactlyTheMaximumLengthIsAccepted() {
        HttpResponse<String> answer = post(IdempotencyDemoController.PAYMENTS, UUID.randomUUID(),
                BODY, KEY_HEADER, "k".repeat(MAX_KEY_LENGTH));

        assertThat(answer.statusCode()).isEqualTo(201);
        assertThat(probe.invocations()).isEqualTo(1);
    }

    @Test
    void aReadOnlyEndpointNeedsNoKey() {
        HttpResponse<String> answer = get(IdempotencyDemoController.READ_ONLY);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("ok");
    }

    // --- Replay ---

    @Test
    void theFirstExecutionHasNoReplayHeaderAndARepetitionReturnsTheSameAnswerWithIt() {
        UUID institution = UUID.randomUUID();
        String key = newKey();

        HttpResponse<String> first = post(IdempotencyDemoController.PAYMENTS, institution, BODY,
                KEY_HEADER, key);
        HttpResponse<String> second = post(IdempotencyDemoController.PAYMENTS, institution, BODY,
                KEY_HEADER, key);

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.headers().firstValue(REPLAY_HEADER)).isEmpty();
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.headers().firstValue(REPLAY_HEADER)).hasValue("true");
        assertThat(json(second)).as("the stored answer, not a new receipt").isEqualTo(json(first));
        assertThat(probe.invocations()).isEqualTo(1);
    }

    @Test
    void twoInstitutionsWithTheSameKeyAreIsolatedFromEachOther() {
        String key = newKey();

        HttpResponse<String> first = post(IdempotencyDemoController.PAYMENTS, UUID.randomUUID(),
                BODY, KEY_HEADER, key);
        HttpResponse<String> second = post(IdempotencyDemoController.PAYMENTS, UUID.randomUUID(),
                BODY, KEY_HEADER, key);

        assertThat(first.headers().firstValue(REPLAY_HEADER)).isEmpty();
        assertThat(second.headers().firstValue(REPLAY_HEADER)).isEmpty();
        assertThat(json(second)).isNotEqualTo(json(first));
        assertThat(probe.invocations()).isEqualTo(2);
    }

    // --- The payload ---

    @Test
    void theSameKeyWithAnotherPayloadIsRefusedWithoutRunningTheUseCase() {
        UUID institution = UUID.randomUUID();
        String key = newKey();
        post(IdempotencyDemoController.PAYMENTS, institution, BODY, KEY_HEADER, key);

        HttpResponse<String> answer = post(IdempotencyDemoController.PAYMENTS, institution,
                OTHER_BODY, KEY_HEADER, key);

        assertProblem(answer, 422, "idempotency-payload-mismatch");
        assertThat(probe.invocations()).as("only the first request ran").isEqualTo(1);
    }

    @Test
    void thePayloadWithItsFieldsInAnotherOrderIsTheSamePayload() {
        UUID institution = UUID.randomUUID();
        String key = newKey();
        post(IdempotencyDemoController.PAYMENTS, institution, BODY, KEY_HEADER, key);

        HttpResponse<String> answer = post(IdempotencyDemoController.PAYMENTS, institution,
                "{\"currency\":\"HNL\",\"amount\":\"10.0000\"}", KEY_HEADER, key);

        assertThat(answer.statusCode()).isEqualTo(201);
        assertThat(answer.headers().firstValue(REPLAY_HEADER)).hasValue("true");
        assertThat(probe.invocations()).isEqualTo(1);
    }

    @Test
    void thePathVariablesAreCoveredByThePayloadAndTheEndpointIsTheMethodAndTheTemplate() {
        UUID institution = UUID.randomUUID();
        String key = newKey();
        String onAccountA = "/test/idempotency/accounts/A-1/payments";
        String onAccountB = "/test/idempotency/accounts/B-2/payments";
        post(onAccountA, institution, BODY, KEY_HEADER, key);

        HttpResponse<String> otherResource = post(onAccountB, institution, BODY, KEY_HEADER, key);
        HttpResponse<String> otherEndpoint = post(IdempotencyDemoController.PAYMENTS, institution,
                BODY, KEY_HEADER, key);
        HttpResponse<String> sameResource = post(onAccountA, institution, BODY, KEY_HEADER, key);

        assertProblem(otherResource, 422, "idempotency-payload-mismatch");
        assertThat(otherEndpoint.statusCode()).as("another template is another endpoint")
                .isEqualTo(201);
        assertThat(otherEndpoint.headers().firstValue(REPLAY_HEADER)).isEmpty();
        assertThat(sameResource.headers().firstValue(REPLAY_HEADER)).hasValue("true");
        assertThat(probe.invocations()).isEqualTo(2);
    }

    // --- Concurrency ---

    @Test
    void aSecondRequestThatOutlastsTheBoundedWaitGets409AndARetryAfterTheFirstCommitsIsAReplay()
            throws Exception {
        UUID institution = UUID.randomUUID();
        String key = newKey();
        probe.holdTheUseCase();
        CompletableFuture<HttpResponse<String>> first = postAsync(
                IdempotencyDemoController.PAYMENTS, institution, BODY, KEY_HEADER, key);
        assertThat(probe.awaitInsideTheUseCase()).as("the first request holds its marker").isTrue();

        HttpResponse<String> second = post(IdempotencyDemoController.PAYMENTS, institution, BODY,
                KEY_HEADER, key);

        assertProblem(second, 409, "idempotency-conflict");
        assertThat(probe.invocations()).as("the second never ran its use case").isEqualTo(1);
        probe.release();
        HttpResponse<String> completed = first.get(DemoProbe.SAFETY_SECONDS, TimeUnit.SECONDS);
        assertThat(completed.statusCode()).as("the first is not affected").isEqualTo(201);

        HttpResponse<String> retry = post(IdempotencyDemoController.PAYMENTS, institution, BODY,
                KEY_HEADER, key);

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.headers().firstValue(REPLAY_HEADER)).hasValue("true");
        assertThat(json(retry)).isEqualTo(json(completed));
        assertThat(probe.invocations()).isEqualTo(1);
    }

    @Test
    void aSecondRequestThatCollidesWithAKeyTheFirstConfirmsReplaysItsAnswerAndIsNeverA409()
            throws Exception {
        UUID institution = UUID.randomUUID();
        String key = newKey();
        probe.holdTheUseCase();
        CompletableFuture<HttpResponse<String>> first = postAsync(
                IdempotencyDemoController.PAYMENTS, institution, BODY, KEY_HEADER, key);
        assertThat(probe.awaitInsideTheUseCase()).isTrue();
        CompletableFuture<HttpResponse<String>> second = postAsync(
                IdempotencyDemoController.PAYMENTS, institution, BODY, KEY_HEADER, key);
        assertThat(probe.awaitSecondMarkerInsert())
                .as("the second is about to write its own marker").isTrue();

        probe.release();

        HttpResponse<String> winner = first.get(DemoProbe.SAFETY_SECONDS, TimeUnit.SECONDS);
        HttpResponse<String> loser = second.get(DemoProbe.SAFETY_SECONDS, TimeUnit.SECONDS);
        assertThat(winner.statusCode()).isEqualTo(201);
        assertThat(winner.headers().firstValue(REPLAY_HEADER)).isEmpty();
        assertThat(loser.statusCode()).as("never a 409").isEqualTo(201);
        assertThat(loser.headers().firstValue(REPLAY_HEADER)).hasValue("true");
        assertThat(json(loser)).isEqualTo(json(winner));
        assertThat(probe.invocations()).as("the second never ran its use case").isEqualTo(1);
    }

    // --- What is never logged ---

    @Test
    void noLogLineCarriesTheKeyOrThePayloadWhateverTheOutcome() {
        String key = "SECRET-KEY-" + UUID.randomUUID();
        String marker = "SECRET-PAYLOAD-" + UUID.randomUUID();
        UUID institution = UUID.randomUUID();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        // Debug for our own code and for the web layer, so that the check reads everything either
        // could say about a request, not only what the default level lets through.
        Logger ours = (Logger) LoggerFactory.getLogger("com.confia");
        Logger web = (Logger) LoggerFactory.getLogger("org.springframework.web");
        Level oursBefore = ours.getLevel();
        Level webBefore = web.getLevel();
        ours.setLevel(Level.DEBUG);
        web.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        // Proof that the capture works at the levels in force: without it, an empty list would
        // pass the check below whether the code is silent or the capture is blind.
        LoggerFactory.getLogger("com.confia.probe").warn(PROBE);
        try {
            post(IdempotencyDemoController.PAYMENTS, institution, "{\"note\":\"" + marker + "\"}",
                    KEY_HEADER, key);
            post(IdempotencyDemoController.PAYMENTS, institution, "{\"note\":\"" + marker + "\"}",
                    KEY_HEADER, key);
            post(IdempotencyDemoController.PAYMENTS, institution, "{\"note\":\"other\"}",
                    KEY_HEADER, key);
            post(IdempotencyDemoController.PAYMENTS, institution, BODY, KEY_HEADER, key + " x");
        } finally {
            root.detachAppender(logged);
            ours.setLevel(oursBefore);
            web.setLevel(webBefore);
        }

        assertThat(logged.list).as("non-vacuous: the capture saw the probe line")
                .anyMatch(event -> PROBE.equals(event.getFormattedMessage()));
        for (ILoggingEvent event : new ArrayList<>(logged.list)) {
            String line = event.getFormattedMessage() + " " + event.getKeyValuePairs() + " "
                    + event.getMDCPropertyMap() + " " + event.getThrowableProxy();
            assertThat(line).doesNotContain(key).doesNotContain(marker);
        }
    }

    // --- Helpers ---

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET());
    }

    private HttpResponse<String> post(String path, UUID institution, String body,
            String... headerPairs) {
        return send(postRequest(path, institution, body, headerPairs));
    }

    private CompletableFuture<HttpResponse<String>> postAsync(String path, UUID institution,
            String body, String... headerPairs) {
        return HTTP.sendAsync(postRequest(path, institution, body, headerPairs).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder postRequest(String path, UUID institution, String body,
            String... headerPairs) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .header(IdempotencyDemoController.INSTITUTION_HEADER, institution.toString())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headerPairs.length; i += 2) {
            request.header(headerPairs[i], headerPairs[i + 1]);
        }
        return request;
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return HTTP.send(request.timeout(Duration.ofSeconds(DemoProbe.SAFETY_SECONDS)).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the server", e);
        }
    }

    /**
     * The production wiring and web edge with the idempotency edge, the demonstration controller
     * and a decorator of the record store that tells the test when a marker is about to be written.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class})
    @Import({SharedPlatformConfiguration.class, WebEdgeConfiguration.class,
            IdempotencyEdgeConfiguration.class, IdempotencyDemoController.class})
    static class Harness {

        @Bean
        DemoProbe demoProbe() {
            return new DemoProbe();
        }

        @Bean
        static BeanPostProcessor signallingRecordStore(ObjectProvider<DemoProbe> probe) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof IdempotencyRecordStore store
                            ? new SignallingStore(store, probe) : bean;
                }
            };
        }

        @Bean
        SecurityFilterChain harnessChain(HttpSecurity http, ProblemResponses problems)
                throws Exception {
            PublicEndpoints open = new PublicEndpoints(List.of(
                    new PublicEndpoint(HttpMethod.POST, IdempotencyDemoController.PAYMENTS),
                    new PublicEndpoint(HttpMethod.POST, IdempotencyDemoController.ACCOUNT_PAYMENTS),
                    new PublicEndpoint(HttpMethod.GET, IdempotencyDemoController.READ_ONLY)));
            return SecurityChains.denyByDefault(http, open, problems).build();
        }
    }

    /** The real store, with a signal to the test before every insert of a marker. */
    private record SignallingStore(IdempotencyRecordStore delegate, ObjectProvider<DemoProbe> probe)
            implements IdempotencyRecordStore {

        @Override
        public Optional<IdempotencyRecord> lockExisting(InstitutionId institutionId,
                IdempotencyKey key) {
            return delegate.lockExisting(institutionId, key);
        }

        @Override
        public void insertInProgress(InstitutionId institutionId, IdempotencyKey key,
                String requestHash, Instant createdAt, Instant expiresAt) {
            probe.getObject().markerInsertAttempted();
            delegate.insertInProgress(institutionId, key, requestHash, createdAt, expiresAt);
        }

        @Override
        public void restartExpired(InstitutionId institutionId, IdempotencyKey key,
                String requestHash, Instant expiresAt) {
            delegate.restartExpired(institutionId, key, requestHash, expiresAt);
        }

        @Override
        public void complete(InstitutionId institutionId, IdempotencyKey key,
                IdempotentResponse response, Instant completedAt) {
            delegate.complete(institutionId, key, response, completedAt);
        }
    }
}

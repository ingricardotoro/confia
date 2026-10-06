package com.confia.shared.web.delay;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.platform.infrastructure.SharedPlatformConfiguration;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import com.confia.shared.web.edge.SecurityChains;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Specs/web-edge, requirements "El retardo requerido se materializa después de confirmar la
 * transacción", "La espera no retiene hilos de plataforma del servidor" and "El cierre de la
 * conexión del cliente abandona la espera" (web-edge-foundations design.md, decision 18), against a
 * real PostgreSQL and the real web server on a random port with virtual threads on and only two
 * platform threads configured. The routes run their use case in a real transaction through the real
 * {@link TransactionRunner} and then ask the materializer for the delay. The timer is a {@link
 * GatedTimer}, so the test holds every wait in progress for as long as it needs to look at the pool
 * and the locks, and releases it with a latch: no sleep decides anything.
 */
@SpringBootTest(classes = RequiredDelayMaterializerIT.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.threads.virtual.enabled=true", "server.tomcat.threads.max=2"})
class RequiredDelayMaterializerIT extends CommittingPostgresIntegrationTest {

    private static final int PERMITS = 8;
    private static final long ADVISORY_KEY = 7001;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();
    private static final long SAFETY_SECONDS = 30;

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
    private CurrentTimer currentTimer;

    @Autowired
    private RequiredDelayMaterializer materializer;

    @Autowired
    private Observations observations;

    private GatedTimer timer;

    @BeforeEach
    void freshGate() {
        timer = null;
        observations.reset();
    }

    @Test
    void theWaitBeginsAfterTheCommitAndHoldsNoConnectionAndNoLock() throws Exception {
        UUID institution = UUID.randomUUID();
        UUID marker = UUID.randomUUID();
        useGate(1);

        CompletableFuture<HttpResponse<String>> answer = get("/test/delay/commit?institution="
                + institution + "&marker=" + marker);
        timer.awaitEntered();

        // The first look is at the pool, before this test uses a connection of its own.
        assertThat(activeConnections()).as("no connection is held during the wait").isZero();
        assertThat(advisoryLocksHeld()).as("no lock is held during the wait").isZero();
        assertThat(observations.locksSeenInsideTheTransaction())
                .as("non-vacuous: the use case did hold the lock while it ran").isEqualTo(1);
        assertThat(auditRowsOf(institution)).as("the transaction was committed before the wait")
                .isEqualTo(1);
        assertThat(answer.isDone()).as("the answer waits for the delay").isFalse();

        timer.open();
        assertThat(answer.get(SAFETY_SECONDS, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(timer.requested()).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void theWaitRunsOnAVirtualThread() throws Exception {
        useGate(1);

        CompletableFuture<HttpResponse<String>> answer = get("/test/delay/wait");
        timer.awaitEntered();
        timer.open();

        assertThat(answer.get(SAFETY_SECONDS, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        assertThat(timer.virtualThreads()).containsExactly(true);
    }

    @Test
    void moreWaitsThanPlatformThreadsLeaveARequestWithoutDelayAnsweredAndTheNextWaitRefused()
            throws Exception {
        useGate(PERMITS);
        List<CompletableFuture<HttpResponse<String>>> waits = new ArrayList<>();
        for (int i = 0; i < PERMITS; i++) {
            waits.add(get("/test/delay/wait"));
        }
        timer.awaitEntered();
        assertThat(materializer.availablePermits()).as("every permit is in use").isZero();

        HttpResponse<String> free = get("/test/delay/free").get(SAFETY_SECONDS, TimeUnit.SECONDS);
        HttpResponse<String> refused = get("/test/delay/wait").get(SAFETY_SECONDS,
                TimeUnit.SECONDS);

        assertThat(free.statusCode()).as("the request with no delay is served during the waits")
                .isEqualTo(200);
        assertThat(free.body()).isEqualTo("free");
        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.body()).contains("capacity-exceeded");
        assertThat(waits).as("the waits are still in progress").noneMatch(CompletableFuture::isDone);
        timer.open();
        for (CompletableFuture<HttpResponse<String>> wait : waits) {
            assertThat(wait.get(SAFETY_SECONDS, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(materializer.availablePermits()).isEqualTo(PERMITS);
    }

    @Test
    void aClientThatClosesDuringTheWaitLeavesNoErrorAndTheServerFreesItsPermit()
            throws Exception {
        useGate(1);
        observations.expectCompletions(1);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logged.start();
        root.addAppender(logged);
        try {
            try (Socket socket = new Socket("localhost", port)) {
                // Closing with a zero linger resets the connection, so the write that follows the
                // wait fails at once instead of landing in a buffer.
                socket.setSoLinger(true, 0);
                socket.getOutputStream().write(("GET /test/delay/big HTTP/1.1\r\n"
                        + "Host: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                timer.awaitEntered();
                assertThat(materializer.availablePermits())
                        .as("the server cannot tell: the permit is held until the delay expires")
                        .isEqualTo(PERMITS - 1);
            }

            timer.open();
            assertThat(observations.awaitCompletions()).as("the request ran to its end").isTrue();

            assertThat(materializer.availablePermits()).as("the permit came back")
                    .isEqualTo(PERMITS);
            assertThat(logged.list).as("the abandoned answer is not an error of the server")
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
        } finally {
            root.detachAppender(logged);
        }
    }

    // --- Helpers ---

    private void useGate(int expectedWaiters) {
        timer = GatedTimer.gated(expectedWaiters);
        currentTimer.use(timer);
    }

    private CompletableFuture<HttpResponse<String>> get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(SAFETY_SECONDS)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private int activeConnections() {
        DataSource dataSource = dataSource();
        try {
            HikariPoolMXBean pool = dataSource.unwrap(HikariDataSource.class)
                    .getHikariPoolMXBean();
            assertThat(pool).as("the pool has started, so its counters are real").isNotNull();
            return pool.getActiveConnections();
        } catch (java.sql.SQLException notHikari) {
            throw new IllegalStateException("the data source is not the Hikari pool", notHikari);
        }
    }

    private long advisoryLocksHeld() {
        return countOf("select count(*) from pg_locks where locktype = 'advisory' "
                + "and objid = ?", ADVISORY_KEY);
    }

    private long auditRowsOf(UUID institution) {
        return transactionRunner().execute(contextOf(institution), () -> countOf(
                "select count(*) from shared_audit_log where institution_id = ?", institution));
    }

    private long countOf(String sql, Object bound) {
        return ((Number) dsl.fetchValue(sql, bound)).longValue();
    }

    private static SecurityContext contextOf(UUID institution) {
        return new SecurityContext("", "system", institution.toString(),
                UUID.randomUUID().toString());
    }

    private static AuditEntry entry(UUID institution, UUID marker) {
        return new AuditEntry(institution, null, "system", "delay test", null, null, marker, null,
                "test.action", "test_entity", "entity-1", "success", null, null, null, null);
    }

    /** The timer of the harness: it forwards to the gate the running test installed. */
    static final class CurrentTimer implements DelayTimer {

        private volatile GatedTimer current;

        void use(GatedTimer timer) {
            current = timer;
        }

        @Override
        public void await(Duration delay) throws InterruptedException {
            current.await(delay);
        }
    }

    /** What the routes and the interceptor saw, for the assertions of the tests. */
    static final class Observations {

        private final AtomicInteger locksSeen = new AtomicInteger(-1);
        private volatile CountDownLatch completions = new CountDownLatch(0);

        void reset() {
            locksSeen.set(-1);
            completions = new CountDownLatch(0);
        }

        int locksSeenInsideTheTransaction() {
            return locksSeen.get();
        }

        void expectCompletions(int requests) {
            completions = new CountDownLatch(requests);
        }

        boolean awaitCompletions() throws InterruptedException {
            return completions.await(SAFETY_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** The production wiring and web edge, with the routes that ask the materializer. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class})
    @Import({SharedPlatformConfiguration.class, WebEdgeConfiguration.class,
            DelayingController.class})
    static class Harness {

        @Bean
        CurrentTimer currentTimer() {
            return new CurrentTimer();
        }

        @Bean
        Observations observations() {
            return new Observations();
        }

        @Bean
        RequiredDelayMaterializer requiredDelayMaterializer(CurrentTimer timer) {
            return new RequiredDelayMaterializer(PERMITS, timer);
        }

        @Bean
        WebMvcConfigurer completionCounter(Observations observations) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response, Object handler, Exception failure) {
                            observations.completions.countDown();
                        }
                    });
                }
            };
        }

        @Bean
        SecurityFilterChain harnessChain(HttpSecurity http, ProblemResponses problems)
                throws Exception {
            PublicEndpoints open = new PublicEndpoints(List.of(
                    new PublicEndpoint(HttpMethod.GET, "/test/delay/commit"),
                    new PublicEndpoint(HttpMethod.GET, "/test/delay/wait"),
                    new PublicEndpoint(HttpMethod.GET, "/test/delay/free"),
                    new PublicEndpoint(HttpMethod.GET, "/test/delay/big")));
            return SecurityChains.denyByDefault(http, open, problems).build();
        }
    }

    @RestController
    static class DelayingController {

        private static final Duration ONE_SECOND = Duration.ofSeconds(1);

        private final RequiredDelayMaterializer materializer;
        private final TransactionRunner runner;
        private final AuditLogWriter writer;
        private final DSLContext dsl;
        private final Observations observations;

        DelayingController(RequiredDelayMaterializer materializer, TransactionRunner runner,
                AuditLogWriter writer, DSLContext dsl, Observations observations) {
            this.materializer = materializer;
            this.runner = runner;
            this.writer = writer;
            this.dsl = dsl;
            this.observations = observations;
        }

        /** A real transaction that takes a lock and commits a row, then a delay of one second. */
        @GetMapping("/test/delay/commit")
        String commit(@RequestParam(name = "institution") UUID institution,
                @RequestParam(name = "marker") UUID marker) {
            return materializer.execute(() -> {
                runner.execute(contextOf(institution), () -> {
                    dsl.execute("select pg_advisory_xact_lock(?)", ADVISORY_KEY);
                    observations.locksSeen.set(((Number) dsl.fetchValue("select count(*) from "
                            + "pg_locks where locktype = 'advisory' and objid = ?",
                            ADVISORY_KEY)).intValue());
                    writer.append(entry(institution, marker));
                    return null;
                });
                return new Delayed<>("done", ONE_SECOND);
            });
        }

        @GetMapping("/test/delay/wait")
        String waiting() {
            return materializer.execute(() -> new Delayed<>("done", ONE_SECOND));
        }

        @GetMapping("/test/delay/free")
        String free() {
            return "free";
        }

        /** An answer too large for a socket buffer, so that writing it to a closed peer fails. */
        @GetMapping(path = "/test/delay/big", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
        byte[] big() {
            return materializer.execute(() -> new Delayed<>(new byte[64 * 1024 * 1024],
                    ONE_SECOND));
        }
    }
}

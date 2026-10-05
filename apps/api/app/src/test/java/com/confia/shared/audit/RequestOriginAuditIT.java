package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.platform.infrastructure.SharedPlatformConfiguration;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.web.edge.PublicEndpoint;
import com.confia.shared.web.edge.PublicEndpoints;
import com.confia.shared.web.edge.SecurityChains;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Specs/web-edge, "El origen de la petición llega a la bitácora de auditoría" (web-edge-foundations
 * design.md, decision 13), against a real PostgreSQL and the real wiring: the production {@link
 * SharedPlatformConfiguration} and the production web edge, a web server on a random port whose
 * only route writes one audit entry through the {@link AuditLogWriter} bean and a real {@link
 * TransactionRunner}. The loopback addresses, the peer of these tests, are the trusted proxies, so
 * a request chooses its client address with {@code X-Forwarded-For}.
 *
 * <p>The entry is committed before the response is sent (the route runs the {@link
 * TransactionRunner} to completion before it returns), so reading it afterwards needs no wait.
 */
@SpringBootTest(classes = RequestOriginAuditIT.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "confia.web.trusted-proxies=127.0.0.1,::1")
class RequestOriginAuditIT extends CommittingPostgresIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

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
    private AuditLogWriter auditLogWriter;

    @Test
    void anEntryWrittenDuringARequestCarriesItsClientAddressAndAgent() throws Exception {
        UUID institution = UUID.randomUUID();
        UUID marker = UUID.randomUUID();

        HttpResponse<String> response = post(institution, marker, "203.0.113.9", "agente-prueba");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<UUID, Record> rows = rowsOf(institution);
        assertThat(rows).containsOnlyKeys(marker);
        assertThat(rows.get(marker).get("ip", String.class)).isEqualTo("203.0.113.9");
        assertThat(rows.get(marker).get("user_agent", String.class)).isEqualTo("agente-prueba");
        assertChainIsIntact(institution, 1);
    }

    @Test
    void anEntryWrittenByAPlainThreadHandedWorkDuringARequestKeepsBothFieldsNull()
            throws Exception {
        UUID institution = UUID.randomUUID();
        UUID marker = UUID.randomUUID();

        // The request carries an address and an agent, but the route writes from a plain
        // executor thread, which does not inherit the origin: the writer must neither fail nor
        // invent a value.
        HttpResponse<String> response = post("/test/audit-from-plain-thread", institution,
                marker, "203.0.113.9", "agente-prueba");

        assertThat(response.statusCode()).isEqualTo(200);
        Map<UUID, Record> rows = rowsOf(institution);
        assertThat(rows).containsOnlyKeys(marker);
        assertThat(rows.get(marker).get("ip")).isNull();
        assertThat(rows.get(marker).get("user_agent")).isNull();
        assertChainIsIntact(institution, 1);
    }

    @Test
    void anEntryWrittenOutsideARequestKeepsBothFieldsNull() {
        UUID institution = UUID.randomUUID();
        UUID marker = UUID.randomUUID();

        transactionRunner().execute(contextOf(institution), () -> {
            auditLogWriter.append(entry(institution, marker));
            return null;
        });

        Map<UUID, Record> rows = rowsOf(institution);
        assertThat(rows).containsOnlyKeys(marker);
        assertThat(rows.get(marker).get("ip")).isNull();
        assertThat(rows.get(marker).get("user_agent")).isNull();
        assertChainIsIntact(institution, 1);
    }

    @Test
    void fiftyConcurrentRequestsEachWriteTheirOwnAddressAndAgentWithoutCrossTalk()
            throws Exception {
        int requests = 50;
        UUID institution = UUID.randomUUID();
        List<UUID> markers = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            markers.add(UUID.randomUUID());
        }
        CyclicBarrier allReady = new CyclicBarrier(requests);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            for (int i = 0; i < requests; i++) {
                int n = i;
                statuses.add(pool.submit(() -> {
                    allReady.await(30, TimeUnit.SECONDS);
                    return post(institution, markers.get(n), "198.51.100." + (n + 1),
                            "agent-" + n).statusCode();
                }));
            }
            for (Future<Integer> status : statuses) {
                assertThat(status.get(120, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }

        // Every response came back, and the route commits its entry before it answers, so the
        // rows are already there: no wait. An exact count would expose a missing row.
        Map<UUID, Record> rows = rowsOf(institution);
        assertThat(rows).hasSize(requests);
        assertThat(rows.keySet()).containsExactlyInAnyOrderElementsOf(markers);
        for (int i = 0; i < requests; i++) {
            Record row = rows.get(markers.get(i));
            assertThat(row.get("ip", String.class)).isEqualTo("198.51.100." + (i + 1));
            assertThat(row.get("user_agent", String.class)).isEqualTo("agent-" + i);
        }
        assertThat(rows.values().stream().map(row -> row.get("ip", String.class)).distinct())
                .hasSize(requests);
        assertChainIsIntact(institution, requests);
    }

    /**
     * The origin is written before the chaining trigger computes the hash, and {@code source_ip}
     * is part of the signed row: the verifier recalculates every row and must find the chain
     * whole, with exactly the rows this test wrote.
     */
    private void assertChainIsIntact(UUID institution, long rows) {
        AuditChainVerifier verifier = new DefaultAuditChainVerifier(transactionRunner(),
                new JooqAuditLogReader(dsl), new CanonicalAuditRowSerializer());

        AuditChainVerification result = verifier.verifyChainOf(new InstitutionId(institution));

        assertThat(result).asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(rows));
    }

    private HttpResponse<String> post(UUID institution, UUID marker, String forwardedFor,
            String userAgent) throws Exception {
        return post("/test/audit", institution, marker, forwardedFor, userAgent);
    }

    private HttpResponse<String> post(String path, UUID institution, UUID marker,
            String forwardedFor, String userAgent) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + path + "?institution=" + institution + "&marker=" + marker))
                .header("X-Forwarded-For", forwardedFor)
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** The audit rows of {@code institution} by the request id the writer was given. */
    private Map<UUID, Record> rowsOf(UUID institution) {
        return transactionRunner().execute(contextOf(institution), () -> {
            Map<UUID, Record> rows = new HashMap<>();
            dsl.fetch("""
                    select request_id, host(source_ip) as ip, user_agent
                      from shared_audit_log where institution_id = ?
                    """, institution).forEach(row -> rows.put(row.get("request_id", UUID.class), row));
            return rows;
        });
    }

    private static AuditEntry entry(UUID institution, UUID marker) {
        return new AuditEntry(institution, null, "system", "origin test", null, null, marker, null,
                "test.action", "test_entity", "entity-1", "success", null, null, null, null);
    }

    private static SecurityContext contextOf(UUID institution) {
        return new SecurityContext("", "system", institution.toString(),
                UUID.randomUUID().toString());
    }

    /** The production wiring and web edge, with one public route that writes an audit entry. */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class})
    @Import({SharedPlatformConfiguration.class, WebEdgeConfiguration.class,
            AuditingController.class})
    static class Harness {

        @Bean
        SecurityFilterChain harnessChain(HttpSecurity http, ProblemResponses problems)
                throws Exception {
            PublicEndpoints open = new PublicEndpoints(List.of(
                    new PublicEndpoint(HttpMethod.POST, "/test/audit"),
                    new PublicEndpoint(HttpMethod.POST, "/test/audit-from-plain-thread")));
            return SecurityChains.denyByDefault(http, open, problems).build();
        }
    }

    @RestController
    static class AuditingController {

        private final AuditLogWriter writer;
        private final TransactionRunner runner;

        AuditingController(AuditLogWriter writer, TransactionRunner runner) {
            this.writer = writer;
            this.runner = runner;
        }

        @PostMapping("/test/audit")
        String audit(@RequestParam("institution") UUID institution,
                @RequestParam("marker") UUID marker) {
            runner.execute(contextOf(institution), () -> {
                writer.append(entry(institution, marker));
                return null;
            });
            return "written";
        }

        /** Hands the write to a plain executor thread, which does not inherit the origin. */
        @PostMapping("/test/audit-from-plain-thread")
        String auditFromAPlainThread(@RequestParam("institution") UUID institution,
                @RequestParam("marker") UUID marker) throws Exception {
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> runner.execute(contextOf(institution), () -> {
                    writer.append(entry(institution, marker));
                    return null;
                })).get(30, TimeUnit.SECONDS);
            }
            return "written";
        }
    }
}

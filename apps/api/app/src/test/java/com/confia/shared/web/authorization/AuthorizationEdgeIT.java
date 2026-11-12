package com.confia.shared.web.authorization;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffRole;
import org.slf4j.LoggerFactory;
import org.assertj.core.api.InstanceOfAssertFactories;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.sql.ResultSet;
import com.confia.shared.web.harness.RecordingAuthorizationMetrics;
import com.confia.shared.web.harness.MutableClock;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.PermissionResolver;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.audit.DefaultAuditChainVerifier;
import com.confia.shared.audit.CanonicalAuditRowSerializer;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditChainVerifier;
import com.confia.shared.audit.AuditChainVerification;
import com.confia.kernel.InstitutionId;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.confia.identity.application.ListRolePermissions;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.web.RolesReportController;
import com.confia.identity.web.RolesReportResponse;
import com.confia.identity.infrastructure.wiring.IdentityConfiguration;
import com.confia.identity.web.wiring.IdentityWebConfiguration;
import com.confia.kernel.Permission;
import com.confia.organization.infrastructure.wiring.AcademicStructureConfiguration;
import com.confia.organization.infrastructure.wiring.OrganizationConfiguration;
import com.confia.organization.web.wiring.OrganizationWebConfiguration;
import com.confia.students.infrastructure.wiring.EnrollmentsConfiguration;
import com.confia.students.infrastructure.wiring.StudentsConfiguration;
import com.confia.students.web.wiring.EnrollmentsWebConfiguration;
import com.confia.students.web.wiring.StudentsWebConfiguration;
import com.confia.shared.jobs.infrastructure.wiring.JobSchedulingConfiguration;
import com.confia.shared.observability.metrics.ObservabilityMetricsConfiguration;
import com.confia.shared.platform.infrastructure.SharedPlatformConfiguration;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.RateLimitDecision;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.security.RateLimiterConfiguration;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.csrf.CsrfConfiguration;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SessionTokenConfiguration;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.shared.web.authentication.AccessTokenAuthenticationFilter;
import com.confia.shared.web.edge.AdminSecurityConfiguration;
import com.confia.shared.web.edge.AuthorizationConfiguration;
import com.confia.shared.web.edge.IdempotencyEdgeConfiguration;
import com.confia.shared.web.edge.RequiredDelayConfiguration;
import com.confia.shared.web.edge.SessionCookieConfiguration;
import com.confia.shared.web.edge.ThrottlingConfiguration;
import com.confia.shared.web.edge.WebEdgeConfiguration;
import com.confia.shared.web.openapi.ContractSchemas;
import com.confia.shared.web.openapi.ProcessApiInfo;
import com.confia.shared.web.ratelimit.RateLimiterRegistry;
import com.confia.support.CommittingPostgresIntegrationTest;
import com.confia.support.SharedPostgresContainer;
import com.confia.support.StaffRoleFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Specs/web-edge and specs/authorization of rbac-permission-matrix-and-audit-integration, first part
 * (design.md D6, D19; scenarios WE94 to WE97 and AZ78): the decision of the administrative chain over
 * a real PostgreSQL, the real resolver reading real role assignments, the real session check and the
 * real interceptors. The harness imports exactly what {@code AdminApplication} imports plus two test
 * routes, and {@link #harnessImportsTheAdministrativeConfiguration} fails on any difference, so what
 * is proved here is the wiring of the process and not a copy of it. A write that moves money demands an
 * {@code Idempotency-Key} and spends a quota of the limiter, and a denied request must reach neither.
 */
@SpringBootTest(classes = AuthorizationEdgeIT.Harness.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "confia.authorization.denials.max-full-rows=3")
@TestMethodOrder(MethodOrderer.Random.class)
class AuthorizationEdgeIT extends CommittingPostgresIntegrationTest {

    private static final String KID = "authorization-edge-key";
    private static final KeyPair KEYS = newKeyPair();
    private static final CompactJws JWS = new CompactJws(
            SigningKeyRing.of(SigningKey.signing(KID, KEYS.getPublic(), KEYS.getPrivate())));
    private static final Duration WINDOW = Duration.ofMinutes(5);

    /** The clock of the process under test: it only moves when a test moves it. */
    static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));

    /** The signals of the authorization, counted; every test reads its own difference. */
    static final RecordingAuthorizationMetrics METRICS = new RecordingAuthorizationMetrics();

    /** While set, the write of a denial fails the way a broken database would. */
    static final AtomicBoolean FAIL_AUDIT_WRITES = new AtomicBoolean();

    /** While set, the resolution of the permissions fails. */
    static final AtomicBoolean FAIL_RESOLUTION = new AtomicBoolean();

    /** How many times the controller of the roles report ran: a denied request must leave it where it was. */
    static final AtomicInteger ROLES_REPORT_INVOCATIONS = new AtomicInteger();

    private static final String ROLES_ROUTE = "/api/v1/identity/roles";
    private static final String TEST_READ_ROUTE = "/test/authorization/accounts/1";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final List<PermissionedEndpoint> TEST_ENTRIES = List.of(
            new PermissionedEndpoint(HttpMethod.POST, AuthorizationTestController.PAYMENTS,
                    Permission.PAYMENTS_CREATE),
            new PermissionedEndpoint(HttpMethod.GET, AuthorizationTestController.ACCOUNTS,
                    Permission.IDENTITY_READ));

    /** The routes of the production list (the roles report, whose controller the harness loads) and the ones of the harness. */
    private static final List<PermissionedEndpoint> HARNESS_ENTRIES = java.util.stream.Stream.concat(
            PermissionedEndpoints.forAdmin().endpoints().stream(), TEST_ENTRIES.stream()).toList();

    /** A session: a staff account with a role and a live family, and the full-session token of both. */
    private record Session(UUID institution, UUID account, UUID family, String token) {
    }

    @DynamicPropertySource
    static void secretsOfTheAdministrativeProcess(DynamicPropertyRegistry registry) {
        registry.add("confia.crypto.column-master-key", () -> randomBase64());
        registry.add("confia.crypto.blind-index.current-version", () -> "v1");
        registry.add("confia.crypto.blind-index.keys.v1", () -> randomBase64());
        registry.add("confia.identity.argon2-pepper", () -> randomBase64());
        registry.add("confia.identity.login-institution-id", () -> UUID.randomUUID().toString());
        registry.add("confia.security.csrf.key", () -> randomBase64());
        registry.add("confia.web.allowed-origins", () -> "https://admin.colegio.edu.hn");
        registry.add("confia.security.admin-signing.current.kid", () -> KID);
        registry.add("confia.security.admin-signing.current.private-key", () -> Base64.getEncoder()
                .encodeToString(KEYS.getPrivate().getEncoded()));
        registry.add("confia.security.admin-signing.current.public-key", () -> Base64.getEncoder()
                .encodeToString(KEYS.getPublic().getEncoded()));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private AuthorizationTestController controller;

    @Autowired
    private LimiterCalls limiterCalls;

    @Autowired
    private SecurityFilterChain chain;

    @Autowired
    private PermissionedEndpoints endpoints;

    @Autowired
    private PermissionDenialAudit audit;

    @Autowired
    private TransactionRunner runner;

    private int controllerBefore;
    private int limiterBefore;

    @BeforeEach
    void remember() {
        controllerBefore = controller.invocations();
        limiterBefore = limiterCalls.count();
    }

    // --- WE94: where the decision stands ---

    @Test
    void theDecisionStandsAfterTheBearerFilterOfTheChainAndTheListIsTheProductionOnePlusTheOneOfTheHarness() {
        List<Class<?>> filters = chain.getFilters().stream().<Class<?>>map(Object::getClass).toList();

        assertThat(filters).contains(AccessTokenAuthenticationFilter.class, AuthorizationFilter.class);
        assertThat(filters.indexOf(AccessTokenAuthenticationFilter.class))
                .isLessThan(filters.indexOf(AuthorizationFilter.class));
        assertThat(endpoints.endpoints()).as("the production list and the routes of the harness")
                .containsExactlyElementsOf(HARNESS_ENTRIES);
    }

    // --- WE95 to WE97: the decision comes before idempotency, the limiter and the controller ---

    @Test
    void aDeniedRequestReachesNeitherTheIdempotencyEdgeNorTheLimiterNorTheController()
            throws SQLException {
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);

        HttpResponse<String> response = pay(administrator, "a-key-for-the-denied");

        assertProblem(response, 403, "forbidden");
        assertThat(controller.invocations()).isEqualTo(controllerBefore);
        assertThat(limiterCalls.count()).isEqualTo(limiterBefore);
        assertThat(markersOf(administrator.institution())).isZero();
    }

    @Test
    void theRefusalIsTheSameWithAndWithoutAnIdempotencyKey() throws SQLException {
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);

        HttpResponse<String> without = pay(administrator, null);
        HttpResponse<String> with = pay(administrator, "a-valid-key-0001");

        JsonNode withoutBody = assertProblem(without, 403, "forbidden");
        JsonNode withBody = assertProblem(with, 403, "forbidden");
        for (String field : List.of("type", "title", "detail", "status")) {
            assertThat(withBody.get(field)).as(field).isEqualTo(withoutBody.get(field));
        }
        assertThat(without.body()).doesNotContain("idempotency");
    }

    @Test
    void withThePermissionAndNoKeyTheAnswerIsTheMissingKeyOfTheEdgeAfterTheDecision()
            throws SQLException {
        Session cashier = sessionOf(StaffRole.CASHIER);

        HttpResponse<String> response = pay(cashier, null);

        assertProblem(response, 400, "idempotency-key-missing");
        assertThat(controller.invocations()).isEqualTo(controllerBefore);
    }

    // --- AZ78: a session that was closed opens no route, entry by entry ---

    @Test
    void everyPermissionedEntryRejectsARevokedSession() throws SQLException {
        assertThat(endpoints.endpoints()).as("the walk is non-vacuous").hasSizeGreaterThanOrEqualTo(2)
                .anyMatch(entry -> entry.pattern().contains("{"));
        // The routes of the academic structure, of the student registry and of the enrollments take a JSON body or a typed
        // identifier, which this walk does not send; their revoked-session case has its own test in
        // AcademicStructureEndpointsIT and StudentEndpointsIT.
        for (PermissionedEndpoint entry : endpoints.endpoints().stream()
                .filter(candidate -> !candidate.pattern().startsWith("/api/v1/organization/")
                        && !candidate.pattern().startsWith("/api/v1/students")
                        && !candidate.pattern().startsWith("/api/v1/enrollments"))
                .toList()) {
            Session holder = sessionOf(StaffRole.SUPER_ADMIN);
            assertThat(StaffRole.SUPER_ADMIN.permissions()).contains(entry.permission());
            HttpResponse<String> live = send(entry, holder, "a-valid-key-0002");
            assertThat(live.statusCode()).as("%s %s with a live session", entry.method(),
                    entry.pattern()).isEqualTo(200);

            revoke(holder);
            HttpResponse<String> revoked = send(entry, holder, "a-valid-key-0003");

            assertThat(revoked.statusCode()).as("%s %s with a revoked session", entry.method(),
                    entry.pattern()).isEqualTo(401);
            assertThat(revoked.body()).contains("token-invalid");
        }
    }

    // --- D19: the harness is the process ---

    @Test
    void harnessImportsTheAdministrativeConfiguration() throws ClassNotFoundException {
        Class<?> process = Class.forName("com.confia.bootstrap.admin.AdminApplication");
        Set<Class<?>> imported = new HashSet<>(Arrays.asList(
                process.getAnnotation(Import.class).value()));
        Set<Class<?>> harness = new HashSet<>(Arrays.asList(
                Harness.class.getAnnotation(Import.class).value()));

        assertThat(imported).as("non-vacuous").hasSizeGreaterThan(10)
                .contains(AuthorizationConfiguration.class);
        assertThat(harness).contains(AuthorizationTestController.class);
        harness.remove(AuthorizationTestController.class);
        assertThat(harness).isEqualTo(imported);
        EnableAutoConfiguration processExcludes = process.getAnnotation(EnableAutoConfiguration.class);
        EnableAutoConfiguration harnessExcludes = Harness.class
                .getAnnotation(EnableAutoConfiguration.class);
        assertThat(harnessExcludes.exclude()).containsExactlyInAnyOrder(processExcludes.exclude());
        assertThat(harnessExcludes.excludeName())
                .containsExactlyInAnyOrder(processExcludes.excludeName());
    }

    // =========================================================================================
    // Second part (task 4): the audit of the denials (design.md D8 to D10 and D19). Every test has
    // an institution of its own, so the counters of the keys are its own; the queue, the gate and
    // the clock are shared by the context, so a test that waits for a summary first empties the
    // queue (design.md, "Independencia del orden de las pruebas") and the class runs in random order.
    // =========================================================================================

    // --- AZ37: one row per request, with the template and no value of the request ---

    @Test
    void aPathVariableGivesOneRowPerRequestWithTheTemplateAndNeitherValue() throws SQLException {
        Session cashier = sessionOf(StaffRole.CASHIER);

        // Values that cannot occur by chance in the random UUIDs and instants of an audit row.
        for (String id : List.of("zz-path-1", "zz-path-2")) {
            assertProblem(get(cashier, "/test/authorization/accounts/" + id), 403, "forbidden");
        }

        List<Map<String, String>> rows = rowsOf(cashier.institution());
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("action")).isEqualTo("authorization.access.denied");
            assertThat(row.get("entity_id")).isEqualTo("identity:read");
            assertThat(row.get("after_value"))
                    .isEqualTo("{\"routeTemplate\": \"/test/authorization/accounts/{id}\"}");
        });
        assertThat(rows.toString()).doesNotContain("zz-path-1", "zz-path-2");
    }

    // --- AZ52, AZ53: the cap and the summary ---

    @Test
    void threeDenialsWriteThreeFullRowsAndNoSummary() throws SQLException {
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);
        int omittedBefore = METRICS.denialsOmitted().size();

        for (int i = 0; i < 3; i++) {
            assertProblem(pay(administrator, null), 403, "forbidden");
        }

        assertThat(rowsOf(administrator.institution())).hasSize(3)
                .allSatisfy(row -> assertThat(row.get("action"))
                        .isEqualTo("authorization.access.denied"));
        assertThat(METRICS.denialsOmitted().size()).isEqualTo(omittedBefore);
    }

    @Test
    void fiveDenialsWriteThreeRowsAndAfterTheWindowOneSummaryOfTwo() throws SQLException {
        emptyTheSharedQueue();
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);
        int omittedBefore = METRICS.denialsOmitted().size();

        for (int i = 0; i < 5; i++) {
            assertProblem(pay(administrator, null), 403, "forbidden");
        }
        assertThat(rowsOf(administrator.institution())).hasSize(3);
        closeTheWindowAndFireTheEmissionOf(administrator.institution());

        List<Map<String, String>> rows = rowsOf(administrator.institution());
        assertThat(rows).hasSize(4);
        Map<String, String> summary = rows.get(3);
        assertThat(summary.get("action")).isEqualTo("authorization.access.denied_summary");
        assertThat(summary.get("after_value")).contains("\"omittedCount\": 2",
                "\"windowSeconds\": 300");
        assertThat(summary.get("actor_id")).isEqualTo(administrator.account().toString());
        assertThat(summary.get("entity_id")).isEqualTo("payments:create");
        assertThat(summary.get("source_ip")).as("no origin on a summary").isNull();
        assertThat(summary.toString()).doesNotContain("/test/authorization");
        assertThat(METRICS.denialsOmitted().subList(omittedBefore, METRICS.denialsOmitted().size()))
                .containsExactly(Permission.PAYMENTS_CREATE, Permission.PAYMENTS_CREATE);
        assertChainIntact(administrator.institution(), 4);
    }

    // --- AZ79: the cap under concurrency, over HTTP ---

    @Test
    void concurrentDenialsOfOneKeyWriteExactlyTFullRows() throws Exception {
        emptyTheSharedQueue();
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                responses.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return pay(administrator, null);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<HttpResponse<String>> response : responses) {
                assertProblem(response.get(60, TimeUnit.SECONDS), 403, "forbidden");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(rowsOf(administrator.institution())).hasSize(3);

        closeTheWindowAndFireTheEmissionOf(administrator.institution());

        List<Map<String, String>> rows = rowsOf(administrator.institution());
        assertThat(rows).hasSize(4);
        assertThat(rows.get(3).get("after_value")).contains("\"omittedCount\": 5");
        assertChainIntact(administrator.institution(), 4);
    }

    // --- AZ46 to AZ50, AT38: what is not a denial by permission leaves no row ---

    @Test
    void aGrantedRequestLeavesNoRow() throws SQLException {
        Session cashier = sessionOf(StaffRole.CASHIER);

        HttpResponse<String> response = pay(cashier, "a-granted-key-001");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(rowsOf(cashier.institution())).isEmpty();
    }

    @Test
    void anUnmappedRouteGivesForbiddenAndNoRow() throws SQLException {
        Session administrator = sessionOf(StaffRole.SUPER_ADMIN);
        long before = deniedRowsOfAllInstitutions();

        HttpResponse<String> response = get(administrator, "/test/authorization/not-mapped");

        assertProblem(response, 403, "forbidden");
        assertThat(deniedRowsOfAllInstitutions()).isEqualTo(before);
        assertThat(rowsOf(administrator.institution())).isEmpty();
    }

    @Test
    void aRejectedOriginOnACookieRouteIsCsrfInvalidAndLeavesNoRow() throws Exception {
        long before = deniedRowsOfAllInstitutions();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/api/v1/auth/sessions/refresh"))
                .header("Origin", "https://evil.example")
                .POST(HttpRequest.BodyPublishers.noBody()).build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        assertProblem(response, 403, "csrf-invalid");
        assertThat(deniedRowsOfAllInstitutions()).isEqualTo(before);
    }

    @Test
    void noCredentialAnExpiredTokenAndATamperedOneAreUnauthorizedAndLeaveNoRow()
            throws SQLException {
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);
        long before = deniedRowsOfAllInstitutions();
        String expired = new AccessTokenIssuer(JWS,
                Clock.fixed(CLOCK.instant().minus(Duration.ofHours(3)), ZoneOffset.UTC))
                .issueAccess(administrator.account(), administrator.institution(),
                        administrator.family(), Set.of(AuthenticationMethod.PASSWORD,
                                AuthenticationMethod.ONE_TIME_PASSWORD)).compact();

        HttpResponse<String> none = rawPay(null);
        HttpResponse<String> old = rawPay(expired);
        HttpResponse<String> tampered = rawPay(administrator.token() + "x");

        assertProblem(none, 401, "authentication-required");
        assertThat(old.statusCode()).isEqualTo(401);
        assertThat(tampered.statusCode()).isEqualTo(401);
        assertThat(deniedRowsOfAllInstitutions()).isEqualTo(before);
    }

    // --- AZ60, AZ62: a resolution that fails writes nothing and spends nothing of the cap ---

    @Test
    void failedResolutionsWriteNoRowAndDoNotConsumeTheCap() throws SQLException {
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);
        int resolutionBefore = METRICS.resolutionFailedCount();
        FAIL_RESOLUTION.set(true);
        try {
            for (int i = 0; i < 10; i++) {
                assertProblem(pay(administrator, null), 500, "internal-error");
            }
        } finally {
            FAIL_RESOLUTION.set(false);
        }
        assertThat(rowsOf(administrator.institution())).isEmpty();
        assertThat(METRICS.resolutionFailedCount() - resolutionBefore).isEqualTo(10);

        for (int i = 0; i < 3; i++) {
            assertProblem(pay(administrator, null), 403, "forbidden");
        }

        assertThat(rowsOf(administrator.institution())).as("the cap was not spent").hasSize(3);
    }

    // --- WE98 to WE100, AZ59: the answer is the same whether the trace is written or not ---

    @Test
    void theAnswerIsTheSameForAMappedAndAnUnmappedRouteAndWhenTheWriterIsBroken()
            throws SQLException {
        Session administrator = sessionOf(StaffRole.ADMINISTRATOR);

        HttpResponse<String> mapped = pay(administrator, null);
        HttpResponse<String> unmapped = get(administrator, "/test/authorization/not-mapped");
        assertThat(rowsOf(administrator.institution())).as("one row for the mapped route, none "
                + "for the unmapped one").hasSize(1);
        int controllerNow = controller.invocations();
        int failedBefore = METRICS.denialAuditFailedCount();
        FAIL_AUDIT_WRITES.set(true);
        HttpResponse<String> broken;
        try {
            broken = pay(administrator, null);
        } finally {
            FAIL_AUDIT_WRITES.set(false);
        }

        JsonNode mappedBody = assertProblem(mapped, 403, "forbidden");
        JsonNode unmappedBody = assertProblem(unmapped, 403, "forbidden");
        JsonNode brokenBody = assertProblem(broken, 403, "forbidden");
        for (String field : List.of("type", "title", "detail", "status")) {
            assertThat(unmappedBody.get(field)).as(field).isEqualTo(mappedBody.get(field));
            assertThat(brokenBody.get(field)).as(field).isEqualTo(mappedBody.get(field));
        }
        assertThat(controller.invocations()).isEqualTo(controllerNow);
        assertThat(METRICS.denialAuditFailedCount() - failedBefore).isEqualTo(1);
        assertThat(broken.body()).doesNotContain("SENTINEL-WRITER", "IllegalState");
        assertThat(rowsOf(administrator.institution())).as("the broken write left nothing")
                .hasSize(1);
    }

    // --- AZ51, AT33: no value of the request reaches the audit, the logs or the signals ---

    @Test
    void noValueOfTheRequestReachesTheRowsTheLogsOrTheSignals() throws Exception {
        Session cashier = sessionOf(StaffRole.CASHIER);
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level level = root.getLevel();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(ch.qos.logback.classic.Level.DEBUG);
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + "/test/authorization/accounts/SENTINEL-PATH-1?q=SENTINEL-QUERY-2"))
                    .header("Authorization", "Bearer " + cashier.token())
                    .header("Origin", "https://admin.colegio.edu.hn")
                    .header("X-Sentinel", "SENTINEL-HEADER-4")
                    .method("GET", HttpRequest.BodyPublishers.ofString("SENTINEL-BODY-3")).build();
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            root.setLevel(level);
            root.detachAppender(logged);
        }

        assertProblem(response, 403, "forbidden");
        String rows = rowsOf(cashier.institution()).toString();
        assertThat(rows).contains("authorization.access.denied");
        String[] sentinels = {"SENTINEL-PATH-1", "SENTINEL-QUERY-2", "SENTINEL-BODY-3",
                "SENTINEL-HEADER-4", cashier.token()};
        assertThat(rows).doesNotContain(sentinels);
        String ours = logged.list.stream()
                .filter(event -> event.getLoggerName().startsWith("com.confia"))
                .map(event -> event.getFormattedMessage() + event.getKeyValuePairs()
                        + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy()
                                .getMessage()))
                .collect(Collectors.joining("\n"));
        assertThat(ours).doesNotContain(sentinels);
        // The problem repeats the path as its instance, by design (WE99); nothing else of the request.
        assertThat(response.body()).doesNotContain("SENTINEL-QUERY-2", "SENTINEL-BODY-3",
                "SENTINEL-HEADER-4", cashier.token());
    }

    // =========================================================================================
    // Third part (task 7): exit criterion 2 over the harness, with its controls (design.md D19;
    // scenarios AZ65 to AZ70 and AZ22). Two routes need identity:read: the one of the harness and the
    // real roles report, whose controller counts its runs here. The harness cap is 3, so the cap
    // control of AZ69 uses the fourth denial where the scenario says the third at T=2 (the cap is a
    // property of the context shared by the class). AZ69 is proven only here: the real process has
    // no manual clock and no broken writer without a test bean (D19).
    // =========================================================================================

    private record Probe(String name, String path, IntSupplier invocations) {
    }

    private List<Probe> probes() {
        return List.of(new Probe("test route", TEST_READ_ROUTE, controller::invocations),
                new Probe("roles report", ROLES_ROUTE, ROLES_REPORT_INVOCATIONS::get));
    }

    // --- AZ65: no permission, 403, no invocation and one row whose request id is the trace id ---

    @Test
    void aCashierIsDeniedAndLeavesExactlyOneRowWhoseRequestIdIsTheTraceIdOfTheProblem()
            throws SQLException {
        for (Probe probe : probes()) {
            Session cashier = sessionOf(StaffRole.CASHIER);
            int before = probe.invocations().getAsInt();

            HttpResponse<String> response = get(cashier, probe.path());

            JsonNode problem = assertProblem(response, 403, "forbidden");
            assertThat(probe.invocations().getAsInt()).as("invocations of the %s", probe.name())
                    .isEqualTo(before);
            List<Map<String, String>> rows = rowsOf(cashier.institution());
            assertThat(rows).as("rows for the %s", probe.name()).hasSize(1);
            Map<String, String> row = rows.get(0);
            assertThat(row.get("action")).isEqualTo("authorization.access.denied");
            assertThat(row.get("entity_id")).isEqualTo("identity:read");
            assertThat(row.get("outcome")).isEqualTo("denied");
            assertThat(row.get("actor_id")).isEqualTo(cashier.account().toString());
            assertThat(row.get("request_id")).isEqualTo(problem.get("traceId").asString());
        }
    }

    // --- AZ66: with the permission, 200 and no denial row ---

    @Test
    void anAuditorIsAnsweredByTheControllerAndLeavesNoDenialRow() throws SQLException {
        for (Probe probe : probes()) {
            Session auditor = sessionOf(StaffRole.AUDITOR);
            int before = probe.invocations().getAsInt();

            HttpResponse<String> response = get(auditor, probe.path());

            assertThat(response.statusCode()).as("status of the %s", probe.name()).isEqualTo(200);
            assertThat(probe.invocations().getAsInt()).as("invocations of the %s", probe.name())
                    .isEqualTo(before + 1);
            assertThat(rowsOf(auditor.institution())).as("rows for the %s", probe.name()).isEmpty();
        }
    }

    // --- AZ67: the chain still verifies after both ---

    @Test
    void theChainOfTheInstitutionStaysIntactAfterADenialAndAGrantedRead() throws SQLException {
        UUID institution = UUID.randomUUID();
        Session cashier = sessionIn(institution, UUID.randomUUID(), StaffRole.CASHIER);
        Session auditor = sessionIn(institution, UUID.randomUUID(), StaffRole.AUDITOR);

        assertProblem(get(cashier, ROLES_ROUTE), 403, "forbidden");
        assertThat(get(auditor, ROLES_ROUTE).statusCode()).isEqualTo(200);

        assertThat(rowsOf(institution)).hasSize(1);
        assertChainIntact(institution, 1);
    }

    // --- AZ68: sentinels of the query and of foreign headers reach neither the trail nor the logs ---

    @Test
    void sentinelsOnTheRealRouteReachNeitherTheRowsNorTheLogs() throws Exception {
        Session cashier = sessionOf(StaffRole.CASHIER);
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.classic.Level level = root.getLevel();
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        root.addAppender(logged);
        root.setLevel(ch.qos.logback.classic.Level.DEBUG);
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                            + ROLES_ROUTE + "?q=SENTINEL-QUERY-6&role=SENTINEL-ROLE-7"))
                    .header("Authorization", "Bearer " + cashier.token())
                    .header("Origin", "https://admin.colegio.edu.hn")
                    .header("X-Sentinel", "SENTINEL-HEADER-8")
                    .header("X-Permission", "SENTINEL-PERMISSION-9").GET().build();
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } finally {
            root.setLevel(level);
            root.detachAppender(logged);
        }

        assertProblem(response, 403, "forbidden");
        String rows = rowsOf(cashier.institution()).toString();
        assertThat(rows).contains("authorization.access.denied");
        String[] sentinels = {"SENTINEL-QUERY-6", "SENTINEL-ROLE-7", "SENTINEL-HEADER-8",
                "SENTINEL-PERMISSION-9", cashier.token()};
        assertThat(rows).doesNotContain(sentinels);
        String ours = logged.list.stream()
                .filter(event -> event.getLoggerName().startsWith("com.confia"))
                .map(event -> event.getFormattedMessage() + event.getKeyValuePairs()
                        + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy()
                                .getMessage()))
                .collect(Collectors.joining("\n"));
        assertThat(ours).doesNotContain(sentinels);
        assertThat(response.body()).doesNotContain(sentinels);
    }

    // --- AZ69: a broken writer changes nothing of the answer; the cap and its summary ---

    @Test
    void aBrokenWriterStillAnswers403WithNoInvocationOnBothRoutes() throws SQLException {
        for (Probe probe : probes()) {
            Session cashier = sessionOf(StaffRole.CASHIER);
            int invocationsBefore = probe.invocations().getAsInt();
            int failedBefore = METRICS.denialAuditFailedCount();
            FAIL_AUDIT_WRITES.set(true);
            HttpResponse<String> response;
            try {
                response = get(cashier, probe.path());
            } finally {
                FAIL_AUDIT_WRITES.set(false);
            }

            assertProblem(response, 403, "forbidden");
            assertThat(probe.invocations().getAsInt()).as("invocations of the %s", probe.name())
                    .isEqualTo(invocationsBefore);
            assertThat(METRICS.denialAuditFailedCount() - failedBefore).isEqualTo(1);
            assertThat(rowsOf(cashier.institution())).as("the broken write left nothing").isEmpty();
        }
    }

    @Test
    void theDenialAfterTheCapWritesNoFullRowCountsOneOmissionAndTheNextWindowCarriesTheSummary()
            throws SQLException {
        emptyTheSharedQueue();
        Session cashier = sessionOf(StaffRole.CASHIER);
        int omittedBefore = METRICS.denialsOmitted().size();
        int invocationsBefore = ROLES_REPORT_INVOCATIONS.get();

        for (int i = 0; i < 3; i++) {
            assertProblem(get(cashier, ROLES_ROUTE), 403, "forbidden");
        }
        assertThat(METRICS.denialsOmitted().size()).as("within the cap nothing is omitted")
                .isEqualTo(omittedBefore);
        HttpResponse<String> beyond = get(cashier, ROLES_ROUTE);

        assertProblem(beyond, 403, "forbidden");
        assertThat(rowsOf(cashier.institution())).as("no fourth full row").hasSize(3);
        assertThat(METRICS.denialsOmitted().subList(omittedBefore, METRICS.denialsOmitted().size()))
                .containsExactly(Permission.IDENTITY_READ);
        closeTheWindowAndFireTheEmissionOf(cashier.institution());

        List<Map<String, String>> rows = rowsOf(cashier.institution());
        assertThat(rows).hasSize(4);
        assertThat(rows.get(3).get("action")).isEqualTo("authorization.access.denied_summary");
        assertThat(rows.get(3).get("after_value")).contains("\"omittedCount\": 1");
        assertThat(rows.get(3).get("entity_id")).isEqualTo("identity:read");
        assertThat(ROLES_REPORT_INVOCATIONS.get()).isEqualTo(invocationsBefore);
        assertChainIntact(cashier.institution(), 4);
    }

    // --- AZ70: the result does not depend on what another test did to the same account ---

    @Test
    void theSameAccountInAnotherInstitutionIsNotAffectedByACapSpentElsewhere() throws SQLException {
        UUID account = UUID.randomUUID();
        UUID spentInstitution = UUID.randomUUID();
        Session spent = sessionIn(spentInstitution, account, StaffRole.CASHIER);
        for (int i = 0; i < 5; i++) {
            assertProblem(get(spent, ROLES_ROUTE), 403, "forbidden");
        }
        assertThat(rowsOf(spentInstitution)).as("the cap of the first one is spent").hasSize(3);

        UUID freshInstitution = UUID.randomUUID();
        Session fresh = sessionIn(freshInstitution, account, StaffRole.CASHIER);
        int invocationsBefore = ROLES_REPORT_INVOCATIONS.get();
        HttpResponse<String> response = get(fresh, ROLES_ROUTE);

        JsonNode problem = assertProblem(response, 403, "forbidden");
        List<Map<String, String>> rows = rowsOf(freshInstitution);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("action")).isEqualTo("authorization.access.denied");
        assertThat(rows.get(0).get("request_id")).isEqualTo(problem.get("traceId").asString());
        assertThat(ROLES_REPORT_INVOCATIONS.get()).isEqualTo(invocationsBefore);
    }

    // --- AZ22: a change of role takes effect on the next request with the same token ---

    @Test
    void aChangeFromAuditorToCashierDeniesTheNextRequestWithTheSameToken() throws SQLException {
        for (Probe probe : probes()) {
            Session session = sessionOf(StaffRole.AUDITOR);
            assertThat(get(session, probe.path()).statusCode()).as("%s before the change",
                    probe.name()).isEqualTo(200);
            int invocationsBefore = probe.invocations().getAsInt();

            changeRole(session, StaffRole.CASHIER);
            HttpResponse<String> after = get(session, probe.path());

            assertProblem(after, 403, "forbidden");
            assertThat(probe.invocations().getAsInt()).as("invocations of the %s", probe.name())
                    .isEqualTo(invocationsBefore);
            assertThat(rowsOf(session.institution())).as("rows for the %s", probe.name())
                    .hasSize(1).allSatisfy(row -> assertThat(row.get("entity_id"))
                            .isEqualTo("identity:read"));
        }
    }

    private static void changeRole(Session session, StaffRole role) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(session.institution());
                PreparedStatement statement = connection.prepareStatement("""
                        update identity_staff_account_role set role_code = ?
                         where institution_id = ? and account_id = ?
                        """)) {
            statement.setString(1, role.code());
            statement.setObject(2, session.institution());
            statement.setObject(3, session.account());
            assertThat(statement.executeUpdate()).isEqualTo(1);
            connection.commit();
        }
    }

    // --- Helpers of the second part ---

    private void emptyTheSharedQueue() {
        CLOCK.advance(WINDOW.plusSeconds(1));
        audit.flushClosedWindows(CLOCK.instant());
        assertThat(audit.pendingSummaryCount()).as("the shared queue is empty").isZero();
    }

    /**
     * Closes the window of {@code institution} and fires the emission with denials of another key (a
     * cashier of another institution asking for what a cashier lacks), as many as summaries were
     * pending, and no more: the result never depends on which test ran before.
     */
    private void closeTheWindowAndFireTheEmissionOf(UUID institution) throws SQLException {
        CLOCK.advance(WINDOW.plusSeconds(1));
        Session trigger = sessionOf(StaffRole.CASHIER);
        assertProblem(get(trigger, "/test/authorization/accounts/1"), 403, "forbidden");
        int budget = audit.pendingSummaryCount();
        for (int i = 0; i < budget && !hasSummary(institution); i++) {
            assertProblem(get(trigger, "/test/authorization/accounts/1"), 403, "forbidden");
        }
        assertThat(hasSummary(institution)).as("the summary of the window was written").isTrue();
    }

    private boolean hasSummary(UUID institution) throws SQLException {
        return rowsOf(institution).stream().anyMatch(row ->
                "authorization.access.denied_summary".equals(row.get("action")));
    }

    private HttpResponse<String> get(Session session, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + session.token())
                .header("Origin", "https://admin.colegio.edu.hn").GET().build();
        return call(request);
    }

    private HttpResponse<String> rawPay(String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + AuthorizationTestController.PAYMENTS))
                .timeout(Duration.ofSeconds(30))
                .header("Origin", "https://admin.colegio.edu.hn")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return call(request.build());
    }

    private static HttpResponse<String> call(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the server", e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** The audit rows of {@code institution} in chain order, read as the superuser of the container. */
    private static List<Map<String, String>> rowsOf(UUID institution) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select action, entity_id, entity_type, outcome, actor_id::text as actor_id,
                               after_value::text as after_value, host(source_ip) as source_ip,
                               request_id::text as request_id
                          from shared_audit_log where institution_id = ? order by id
                        """)) {
            statement.setObject(1, institution);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (String column : List.of("action", "entity_id", "entity_type", "outcome",
                            "actor_id", "after_value", "source_ip", "request_id")) {
                        row.put(column, result.getString(column));
                    }
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static long deniedRowsOfAllInstitutions() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                PreparedStatement statement = connection.prepareStatement("""
                        select count(*) from shared_audit_log
                         where action like 'authorization.access.denied%'
                        """);
                ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private void assertChainIntact(UUID institution, long rows) {
        AuditChainVerifier verifier = new DefaultAuditChainVerifier(runner,
                new JooqAuditLogReader(dsl), new CanonicalAuditRowSerializer());
        assertThat(verifier.verifyChainOf(new InstitutionId(institution)))
                .asInstanceOf(InstanceOfAssertFactories.type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(rows));
    }

    // --- Helpers ---

    private HttpResponse<String> pay(Session session, String idempotencyKey) {
        return send(TEST_ENTRIES.get(0), session, idempotencyKey);
    }

    private HttpResponse<String> send(PermissionedEndpoint entry, Session session, String key) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + entry.pattern().replace("{id}", "123")))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + session.token())
                .header("Origin", "https://admin.colegio.edu.hn")
                .method(entry.method().name(), HttpRequest.BodyPublishers.noBody());
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling the server", e);
        }
    }

    private Session sessionOf(StaffRole role) throws SQLException {
        return sessionIn(UUID.randomUUID(), UUID.randomUUID(), role);
    }

    /** A session of the account {@code account} of {@code institution}, which holds the role {@code role}. */
    private Session sessionIn(UUID institution, UUID account, StaffRole role) throws SQLException {
        StaffRoleFixtures.committed(institution, account, role.code());
        UUID family = UUID.randomUUID();
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement statement = connection.prepareStatement("""
                        insert into identity_refresh_token_family
                            (institution_id, id, account_id, second_factor_verified,
                             user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                        values (?, ?, ?, true, ?, ?,
                                ?, ?)
                        """)) {
            statement.setObject(1, institution);
            statement.setObject(2, family);
            statement.setObject(3, account);
            statement.setString(4, UserAgentFingerprint.of("agente-uno").value());
            statement.setObject(5, at(CLOCK.instant().minus(Duration.ofMinutes(5))));
            statement.setObject(6, at(CLOCK.instant().minus(Duration.ofMinutes(1))));
            statement.setObject(7, at(CLOCK.instant().plus(Duration.ofHours(12))));
            statement.executeUpdate();
            connection.commit();
        }
        String token = new AccessTokenIssuer(JWS, CLOCK).issueAccess(account,
                institution, family, Set.of(AuthenticationMethod.PASSWORD,
                        AuthenticationMethod.ONE_TIME_PASSWORD)).compact();
        return new Session(institution, account, family, token);
    }

    private static void revoke(Session session) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(session.institution());
                PreparedStatement statement = connection.prepareStatement("""
                        update identity_refresh_token_family
                           set revoked_at = now(), revoked_reason = 'password_change'
                         where institution_id = ? and id = ?
                        """)) {
            statement.setObject(1, session.institution());
            statement.setObject(2, session.family());
            assertThat(statement.executeUpdate()).isEqualTo(1);
            connection.commit();
        }
    }

    private static long markersOf(UUID institution) throws SQLException {
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement statement = connection.prepareStatement(
                        "select count(*) from shared_idempotency_key")) {
            var rows = statement.executeQuery();
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String randomBase64() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How many times the limiters of the process were asked, whichever the policy. */
    static final class LimiterCalls {

        private final AtomicInteger count = new AtomicInteger();

        int count() {
            return count.get();
        }
    }

    /**
     * What {@code AdminApplication} imports, plus the two routes of this test; the list of the process
     * is replaced by the two entries of the test, and the limiters count their questions.
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {UserDetailsServiceAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class}, excludeName = {
            "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerAutoConfiguration",
            "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerMetricsAutoConfiguration",
            "com.github.kagkarlsson.scheduler.boot.autoconfigure.DbSchedulerActuatorAutoConfiguration"})
    @Import({ContractSchemas.class, ProcessApiInfo.class, SharedPlatformConfiguration.class,
            IdentityConfiguration.class, IdentityWebConfiguration.class,
            OrganizationConfiguration.class, AcademicStructureConfiguration.class,
            OrganizationWebConfiguration.class, StudentsConfiguration.class,
            StudentsWebConfiguration.class, EnrollmentsConfiguration.class,
            EnrollmentsWebConfiguration.class, WebEdgeConfiguration.class,
            AdminSecurityConfiguration.class, ObservabilityMetricsConfiguration.class,
            RateLimiterConfiguration.class, ThrottlingConfiguration.class,
            RequiredDelayConfiguration.class, IdempotencyEdgeConfiguration.class,
            SessionTokenConfiguration.class, CsrfConfiguration.class,
            SessionCookieConfiguration.class, JobSchedulingConfiguration.class,
            AuthorizationConfiguration.class, AuthorizationTestController.class})
    static class Harness {

        @Bean
        LimiterCalls limiterCalls() {
            return new LimiterCalls();
        }

        @Bean
        static BeanPostProcessor testEntriesAndCountingLimiters(
                org.springframework.beans.factory.ObjectProvider<LimiterCalls> calls,
                org.springframework.beans.factory.ObjectProvider<PermissionResolver> resolvers) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof Clock) {
                        return CLOCK;
                    }
                    if (bean instanceof AuthorizationMetrics) {
                        return METRICS;
                    }
                    if (bean instanceof AuditLogWriter writer) {
                        return (AuditLogWriter) entry -> {
                            if (FAIL_AUDIT_WRITES.get()
                                    && entry.action().startsWith("authorization.access.denied")) {
                                throw new IllegalStateException("boom SENTINEL-WRITER");
                            }
                            writer.append(entry);
                        };
                    }
                    if (bean instanceof PermissionDecider) {
                        return new PermissionDecider(actor -> {
                            if (FAIL_RESOLUTION.get()) {
                                throw new IllegalStateException("resolution failed SENTINEL-RESOLVER");
                            }
                            return resolvers.getObject().resolve(actor);
                        }, METRICS);
                    }
                    if (bean instanceof PermissionedEndpoints) {
                        return new PermissionedEndpoints(HARNESS_ENTRIES);
                    }
                    if (bean instanceof RolesReportController) {
                        return new CountingRolesReportController();
                    }
                    if (bean instanceof RateLimiterRegistry registry) {
                        Map<String, RateLimiter> counting = new ConcurrentHashMap<>();
                        registry.policies().forEach(policy -> counting.put(policy,
                                new CountingLimiter(registry.find(policy).orElseThrow(), calls)));
                        return new RateLimiterRegistry(counting);
                    }
                    return bean;
                }
            };
        }
    }

    /** The real controller of the roles report, which counts the times it ran. */
    private static final class CountingRolesReportController extends RolesReportController {

        CountingRolesReportController() {
            super(new ListRolePermissions());
        }

        @Override
        public RolesReportResponse roles() {
            ROLES_REPORT_INVOCATIONS.incrementAndGet();
            return super.roles();
        }
    }

    /** A limiter that tells the test it was asked, and decides as the real one. */
    private record CountingLimiter(RateLimiter delegate,
            org.springframework.beans.factory.ObjectProvider<LimiterCalls> calls)
            implements RateLimiter {

        @Override
        public RateLimitDecision tryAcquire(ClientAddress client) {
            calls.getObject().count.incrementAndGet();
            return delegate.tryAcquire(client);
        }

        @Override
        public void recordFailure(ClientAddress client) {
            delegate.recordFailure(client);
        }
    }
}

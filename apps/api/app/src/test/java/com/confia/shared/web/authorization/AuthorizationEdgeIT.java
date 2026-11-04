package com.confia.shared.web.authorization;

import static com.confia.shared.web.harness.ProblemAssertions.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.StaffRole;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.wiring.IdentityConfiguration;
import com.confia.identity.web.wiring.IdentityWebConfiguration;
import com.confia.kernel.Permission;
import com.confia.organization.infrastructure.wiring.OrganizationConfiguration;
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
import org.junit.jupiter.api.BeforeEach;
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
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationEdgeIT extends CommittingPostgresIntegrationTest {

    private static final String KID = "authorization-edge-key";
    private static final KeyPair KEYS = newKeyPair();
    private static final CompactJws JWS = new CompactJws(
            SigningKeyRing.of(SigningKey.signing(KID, KEYS.getPublic(), KEYS.getPrivate())));
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final List<PermissionedEndpoint> TEST_ENTRIES = List.of(
            new PermissionedEndpoint(HttpMethod.POST, AuthorizationTestController.PAYMENTS,
                    Permission.PAYMENTS_CREATE),
            new PermissionedEndpoint(HttpMethod.GET, AuthorizationTestController.ACCOUNTS,
                    Permission.IDENTITY_READ));

    /** A session: a staff account with a role and a live family, and the full-session token of both. */
    private record Session(UUID institution, UUID account, UUID family, String token) {
    }

    @DynamicPropertySource
    static void secretsOfTheAdministrativeProcess(DynamicPropertyRegistry registry) {
        registry.add("confia.crypto.column-master-key", () -> randomBase64());
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

    private int controllerBefore;
    private int limiterBefore;

    @BeforeEach
    void remember() {
        controllerBefore = controller.invocations();
        limiterBefore = limiterCalls.count();
    }

    // --- WE94: where the decision stands ---

    @Test
    void theDecisionStandsAfterTheBearerFilterOfTheChainAndTheListIsTheOneOfTheHarness() {
        List<Class<?>> filters = chain.getFilters().stream().<Class<?>>map(Object::getClass).toList();

        assertThat(filters).contains(AccessTokenAuthenticationFilter.class, AuthorizationFilter.class);
        assertThat(filters.indexOf(AccessTokenAuthenticationFilter.class))
                .isLessThan(filters.indexOf(AuthorizationFilter.class));
        assertThat(endpoints.endpoints()).containsExactlyElementsOf(TEST_ENTRIES);
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
        for (PermissionedEndpoint entry : endpoints.endpoints()) {
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
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        StaffRoleFixtures.committed(institution, account, role.code());
        UUID family = UUID.randomUUID();
        try (Connection connection = StaffRoleFixtures.ownerTransaction(institution);
                PreparedStatement statement = connection.prepareStatement("""
                        insert into identity_refresh_token_family
                            (institution_id, id, account_id, second_factor_verified,
                             user_agent_fingerprint, created_at, last_used_at, absolute_expires_at)
                        values (?, ?, ?, true, ?, now() - interval '5 minutes',
                                now() - interval '1 minute', now() + interval '12 hours')
                        """)) {
            statement.setObject(1, institution);
            statement.setObject(2, family);
            statement.setObject(3, account);
            statement.setString(4, UserAgentFingerprint.of("agente-uno").value());
            statement.executeUpdate();
            connection.commit();
        }
        String token = new AccessTokenIssuer(JWS, Clock.systemUTC()).issueAccess(account,
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
            OrganizationConfiguration.class, WebEdgeConfiguration.class,
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
                org.springframework.beans.factory.ObjectProvider<LimiterCalls> calls) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof PermissionedEndpoints) {
                        return new PermissionedEndpoints(TEST_ENTRIES);
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

package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The health probes of the administrative and portal processes against real databases
 * (dockerfiles-and-local-compose scenarios CR07, CR09 to CR13, WE101). "PostgreSQL down" is a process whose
 * JDBC URL nothing answers on (its driver error names the host and the port, which is what CR11 must not
 * leak), and "not migrated" is an empty database owned by {@code confia_owner}.
 */
class HealthProbesIT {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private record Answer(int status, String contentType, String body) {
    }

    private static ConfigurableApplicationContext start(String process, String profile, String url, String user,
            String password) {
        String[] arguments = "admin".equals(process)
                ? TestProcessArguments.adminWith(Map.of("spring.datasource.url", url,
                        "spring.datasource.username", user, "spring.datasource.password", password))
                : TestProcessArguments.portalAgainst(url, user, password);
        if (profile != null) {
            arguments = concat(arguments, new String[] {"--spring.profiles.active=" + profile});
        }
        LaunchOutcome outcome = ConfiaApplication.launch(arguments, process);
        assertThat(outcome.context()).as("the %s process must start", process).isNotNull();
        return outcome.context();
    }

    private static String[] concat(String[] first, String[] second) {
        String[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private static Answer get(ConfigurableApplicationContext context, String path) throws Exception {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return new Answer(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body());
    }

    private static void migrate(ScratchDatabase database) {
        Flyway.configure().dataSource(database.jdbcUrl(), database.ownerRole(), database.ownerPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"admin, default", "portal, default", "admin, local", "portal, local"})
    void bothProbesAnswer200AndNothingButTheStatusWithAMigratedDatabase(String process, String profile)
            throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner()) {
            migrate(database);
            try (ConfigurableApplicationContext context = start(process, "default".equals(profile) ? null : profile,
                    database.jdbcUrl(), database.ownerRole(), database.ownerPassword())) {
                for (String path : new String[] {"/health/live", "/health/ready"}) {
                    Answer answer = get(context, path);
                    assertThat(answer.status()).as("%s %s", process, path).isEqualTo(200);
                    assertThat(answer.body()).as("%s %s", process, path).isEqualTo("{\"status\":\"UP\"}");
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"admin", "portal"})
    void anUnmigratedDatabaseIsNotReadyButTheProcessIsAlive(String process) throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner();
                ConfigurableApplicationContext context = start(process, null, database.jdbcUrl(),
                        database.ownerRole(), database.ownerPassword())) {
            assertThat(get(context, "/health/live").status()).isEqualTo(200);
            Answer ready = get(context, "/health/ready");
            assertThat(ready.status()).as("CR07: a database without flyway_schema_history is not ready")
                    .isEqualTo(503);
            assertThat(ready.body()).isEqualTo("{\"status\":\"DOWN\"}");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"admin", "portal"})
    void withPostgresqlDownLivenessStaysUpAndReadinessFallsWithoutLeakingInfrastructure(String process)
            throws Exception {
        try (ConfigurableApplicationContext context = start(process, null, TestProcessArguments.UNREACHABLE_JDBC_URL,
                ScratchDatabase.OWNER_ROLE, ScratchDatabase.OWNER_PASSWORD)) {
            assertThat(get(context, "/health/live")).extracting(Answer::status, Answer::body).containsExactly(200,
                    "{\"status\":\"UP\"}");

            long start = System.nanoTime();
            Answer ready = get(context, "/health/ready");
            long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();

            assertThat(seconds).as("CR10: the 503 arrives inside the probe window").isLessThan(8);
            assertThat(ready.status()).isEqualTo(503);
            assertThat(ready.body()).isEqualTo("{\"status\":\"DOWN\"}");
            assertThat(ready.body()).doesNotContain("components", "details", "127.0.0.1", "confia-unreachable",
                    "Connection", "refused", "postgresql");
            // WE101: the 503 is the plain status body, not Problem Details...
            assertThat(ready.contentType()).doesNotContain("problem+json");
            // ...and any other error of the same process still is.
            Answer other = get(context, "/actuator/env");
            assertThat(other.status()).isEqualTo(401);
            assertThat(other.contentType()).contains("application/problem+json");
        }
    }

    @Test
    void theReadinessGroupHoldsOnlyTheStateOfTheApplicationAndTheDatabase() {
        try (ConfigurableApplicationContext context = start("admin", null,
                TestProcessArguments.UNREACHABLE_JDBC_URL, ScratchDatabase.OWNER_ROLE,
                ScratchDatabase.OWNER_PASSWORD)) {
            HealthEndpointGroup readiness = context.getBean(HealthEndpointGroups.class).get("readiness");
            assertThat(readiness.isMember("readinessState")).isTrue();
            assertThat(readiness.isMember("db")).isTrue();
            for (String other : new String[] {"redis", "objectStorage", "backgroundJobs", "migrations", "diskSpace",
                    "ping", "ssl"}) {
                assertThat(readiness.isMember(other)).as(other).isFalse();
            }
            assertThat(context.getBeansOfType(org.springframework.boot.health.contributor.HealthContributor.class)
                    .keySet()).as("the contributors that exist").allMatch(name -> Map.of("dbHealthContributor", 1,
                            "livenessStateHealthIndicator", 1, "readinessStateHealthIndicator", 1)
                            .containsKey(name));
        }
    }
}

package com.confia.shared.jobs;

import com.confia.bootstrap.worker.WorkerApplication;
import com.confia.shared.logging.SensitiveLogGuardInitializer;
import com.confia.support.SharedPostgresContainer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Starts the real worker (its entry point and wiring) against the shared PostgreSQL, with the
 * executor running and a one-second poll and heartbeat, plus the fixture configuration that registers
 * the test job types. Only the worker harness holds them: the production configuration registers no
 * job, and the fixture types never exist in any other process (scenarios BJ06 and BJ07).
 */
public final class WorkerHarness {

    private WorkerHarness() {
    }

    /** The properties that make the executor fast enough for a test; the rest is the production file. */
    static String[] arguments(UUID institution, String... extra) {
        return argumentsFor(institution.toString(), extra);
    }

    private static String[] argumentsFor(String institutions, String... extra) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.datasource.url=" + SharedPostgresContainer.jdbcUrl(),
                "--spring.datasource.username=confia_admin_app",
                "--spring.datasource.password=test-only-not-a-secret",
                "--confia.jobs.institutions=" + institutions,
                "--db-scheduler.polling-interval=1s",
                "--db-scheduler.heartbeat-interval=1s",
                "--db-scheduler.shutdown-max-wait=5s"));
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    /** Several institutions: the recurring jobs run once for each, in this order. */
    static ConfigurableApplicationContext startWith(Class<?> fixtures, java.util.List<UUID> institutions) {
        String joined = institutions.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
        return new SpringApplicationBuilder(WorkerApplication.class, fixtures)
                .web(WebApplicationType.NONE)
                .initializers(new SensitiveLogGuardInitializer())
                .run(argumentsFor(joined));
    }

    public static ConfigurableApplicationContext start(Class<?> fixtures, UUID institution, String... extra) {
        return new SpringApplicationBuilder(WorkerApplication.class, fixtures)
                .web(WebApplicationType.NONE)
                .initializers(new SensitiveLogGuardInitializer())
                .run(arguments(institution, extra));
    }
}

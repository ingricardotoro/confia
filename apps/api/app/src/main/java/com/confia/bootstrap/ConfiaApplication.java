package com.confia.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Single {@code main} for the three {@code confia-api} process kinds, selected by the {@code
 * APP_PROFILE} environment variable (ADR-0003, ADR-0013, design.md decision 4):
 *
 * <pre>
 * APP_PROFILE ──&gt; ConfiaApplication#main
 *      admin   ──&gt; AdminApplication    (web)
 *      portal  ──&gt; PortalApplication   (web)
 *      worker  ──&gt; WorkerApplication   (no HTTP)
 *      migrate ──&gt; recognized, no behavior yet (change 5)
 *      other   ──&gt; aborts, non-zero exit code
 * </pre>
 */
public final class ConfiaApplication {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiaApplication.class);

    private ConfiaApplication() {
    }

    public static void main(String[] args) {
        LaunchOutcome outcome = launch(args, System.getenv(AppProfile.ENV_VARIABLE));
        if (outcome.context() == null) {
            System.exit(outcome.exitCode());
        }
        // A started admin/portal/worker context keeps the JVM alive through its own non-daemon
        // threads (the embedded web server, for admin/portal). Calling System.exit(0) here would
        // shut the process down right after starting it, which is never the desired behavior.
    }

    /**
     * Testable core of {@link #main(String[])}. Resolves {@code rawProfile} and either starts the
     * matching process (returning its context, running) or returns a terminal exit code with no
     * context (invalid profile, or {@code migrate} which is recognized but has no behavior yet).
     *
     * <p>Never calls {@link System#exit(int)} itself, so callers (tests included) fully control
     * process termination and can close a started context explicitly.
     */
    static LaunchOutcome launch(String[] args, String rawProfile) {
        AppProfile profile;
        try {
            profile = AppProfile.resolve(rawProfile);
        } catch (UnknownAppProfileException ex) {
            LOG.error("{}", ex.getMessage());
            return LaunchOutcome.exit(1);
        }

        return switch (profile) {
            case ADMIN -> LaunchOutcome.running(
                    new SpringApplicationBuilder(AdminApplication.class).run(args));
            case PORTAL -> LaunchOutcome.running(
                    new SpringApplicationBuilder(PortalApplication.class).run(args));
            case WORKER -> LaunchOutcome.running(
                    new SpringApplicationBuilder(WorkerApplication.class)
                            .web(WebApplicationType.NONE)
                            .run(args));
            // Recognized only: applying Flyway migrations and exiting arrives with change 5.
            case MIGRATE -> LaunchOutcome.exit(0);
        };
    }

    /**
     * Outcome of {@link #launch(String[], String)}: either a running {@code context} (exit code
     * is meaningless and ignored by {@link #main(String[])}), or a terminal {@code exitCode} with
     * no context.
     */
    record LaunchOutcome(int exitCode, ConfigurableApplicationContext context) {

        static LaunchOutcome running(ConfigurableApplicationContext context) {
            return new LaunchOutcome(0, context);
        }

        static LaunchOutcome exit(int exitCode) {
            return new LaunchOutcome(exitCode, null);
        }
    }
}

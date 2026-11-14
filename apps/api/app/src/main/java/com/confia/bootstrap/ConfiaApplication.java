package com.confia.bootstrap;

import com.confia.bootstrap.admin.AdminApplication;
import com.confia.bootstrap.migrate.MigrateApplication;
import com.confia.bootstrap.portal.PortalApplication;
import com.confia.bootstrap.worker.WorkerApplication;
import com.confia.shared.logging.SensitiveLogGuardInitializer;
import com.confia.shared.security.token.SigningKeyPlacementGuard;
import com.confia.shared.web.openapi.ProcessApiInfo;
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
 *      admin   ──&gt; bootstrap.admin.AdminApplication     (web)
 *      portal  ──&gt; bootstrap.portal.PortalApplication   (web)
 *      worker  ──&gt; bootstrap.worker.WorkerApplication   (no HTTP)
 *      migrate ──&gt; bootstrap.migrate.MigrateApplication (closed context: applies Flyway, exits)
 *      other   ──&gt; aborts, non-zero exit code
 * </pre>
 *
 * <p>Each process also gets, before its context exists, a {@link SigningKeyPlacementGuard}: the
 * portal and the worker refuse to start with the administrative signing private key in their
 * environment, and the administrative process refuses the name reserved for the portal's
 * (ADR-0005, check 14; session-tokens-and-web-layer design.md, decision 3).
 *
 * <p>All three also get a {@link SensitiveLogGuardInitializer}: the sensitive log guard is installed
 * when the context is prepared, before its first bean exists, so no bean (the worker's future
 * {@code DataSource} included) can log a bound value before the guard does
 * (background-jobs-with-db-scheduler design.md decision 13; condition 4).
 */
public final class ConfiaApplication {

    private static final Logger LOG = LoggerFactory.getLogger(ConfiaApplication.class);

    /** Titles of the two OpenAPI documents, one per web process (frontend-monorepo-and-
     * contracts-pipeline, design.md decision 1). */
    static final String ADMIN_API_TITLE = "CONFIA admin API";
    static final String PORTAL_API_TITLE = "CONFIA portal API";

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
     * context (invalid profile, or {@code migrate}, which runs to completion and never
     * returns a context).
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
                    new SpringApplicationBuilder(AdminApplication.class)
                            .properties(ProcessApiInfo.TITLE_PROPERTY + "=" + ADMIN_API_TITLE)
                            .initializers(new SensitiveLogGuardInitializer())
                            .listeners(SigningKeyPlacementGuard.forAdministrativeProcess())
                            .run(args));
            case PORTAL -> LaunchOutcome.running(
                    new SpringApplicationBuilder(PortalApplication.class)
                            .properties(ProcessApiInfo.TITLE_PROPERTY + "=" + PORTAL_API_TITLE)
                            .initializers(new SensitiveLogGuardInitializer())
                            .listeners(SigningKeyPlacementGuard.forNonAdministrativeProcess())
                            .run(args));
            case WORKER -> LaunchOutcome.running(
                    new SpringApplicationBuilder(WorkerApplication.class)
                            .web(WebApplicationType.NONE)
                            .initializers(new SensitiveLogGuardInitializer())
                            .listeners(SigningKeyPlacementGuard.forNonAdministrativeProcess())
                            .run(args));
            case MIGRATE -> migrate(args);
        };
    }

    /**
     * The builder of the {@code migrate} process: the closed {@link MigrateApplication} context, no
     * web server, the Spring profile {@code migrate} (which turns Flyway on and the keep-alive off)
     * and the same two guards as every other process. Package-private so the isolation test can
     * build and inspect the very context the launcher runs.
     */
    static SpringApplicationBuilder migrateBuilder() {
        return new SpringApplicationBuilder(MigrateApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("migrate")
                .initializers(new SensitiveLogGuardInitializer())
                .listeners(SigningKeyPlacementGuard.forNonAdministrativeProcess());
    }

    /**
     * Applies the pending Flyway migrations and returns the exit code: the context is closed here
     * and never handed back, so the process ends and holds no port or thread. Any failure (database
     * unreachable, credentials rejected, a migration that fails, a diverging checksum) is exit code
     * 1. Only the exception class and, for Flyway, its own message are logged: that message names
     * the migration and never carries the connection's URL or password (CR06).
     */
    private static LaunchOutcome migrate(String[] args) {
        try (ConfigurableApplicationContext ignored = migrateBuilder().run(args)) {
            LOG.info("Migrations applied; closing the migrate context.");
            return LaunchOutcome.exit(0);
        } catch (Exception ex) {
            LOG.error("Migration failed: {}", describe(ex));
            return LaunchOutcome.exit(1);
        }
    }

    /** The failure as a log line that is safe to print: classes, plus Flyway messages only. */
    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null && text.length() < 2000; t = t.getCause()) {
            text.append(t.getClass().getName());
            if (t.getClass().getName().startsWith("org.flywaydb.")) {
                text.append(": ").append(t.getMessage());
            }
            if (t.getCause() != null && t.getCause() != t) {
                text.append(" <- ");
            } else {
                break;
            }
        }
        return text.toString();
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

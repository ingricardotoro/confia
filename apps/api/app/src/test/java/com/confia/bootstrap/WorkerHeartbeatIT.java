package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The heartbeat of the worker against a real database (dockerfiles-and-local-compose scenarios CR16, CR17,
 * WE100; probe P-4). The executor is on with a one-second polling interval and no task is due, so the date of
 * the file can only move through the event that ends a polling cycle. Waits are polls of the date with a
 * deadline, never fixed sleeps; the one fixed window is the observation that the date does NOT move once
 * the database is gone.
 */
class WorkerHeartbeatIT {

    @TempDir
    Path directory;

    private static void awaitTrue(String what, Duration deadline, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + deadline.toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static FileTime modified(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file) : FileTime.fromMillis(0);
        } catch (java.io.IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    @Test
    void theDateAdvancesWhileThePollingWorksAndStopsWhenTheDatabaseIsGone() throws Exception {
        Path heartbeat = directory.resolve("worker.heartbeat");
        ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner();
        try {
            Flyway.configure().dataSource(database.jdbcUrl(), database.ownerRole(), database.ownerPassword())
                    .locations("classpath:db/migration").load().migrate();
            LaunchOutcome outcome = ConfiaApplication.launch(new String[] {
                    "--spring.datasource.url=" + database.jdbcUrl(),
                    "--spring.datasource.username=" + database.ownerRole(),
                    "--spring.datasource.password=" + database.ownerPassword(),
                    "--confia.jobs.institutions=" + UUID.randomUUID(),
                    "--db-scheduler.polling-interval=1s",
                    "--confia.health.heartbeat-file=" + heartbeat}, "worker");
            ConfigurableApplicationContext context = outcome.context();
            assertThat(context).as("the worker must start").isNotNull();
            try {
                assertThat(context).as("WE100: the worker opens no web server and so no port and no route")
                        .isNotInstanceOf(WebServerApplicationContext.class);

                awaitTrue("the first heartbeat", Duration.ofSeconds(40), () -> Files.exists(heartbeat));
                FileTime first = modified(heartbeat);
                awaitTrue("the date to advance", Duration.ofSeconds(20),
                        () -> modified(heartbeat).compareTo(first) > 0);
                assertThat(Files.size(heartbeat)).as("the file holds only a date").isZero();

                database.close();
                // Let any cycle already in flight finish, then watch the date hold still.
                Thread.sleep(3000);
                FileTime frozen = modified(heartbeat);
                Thread.sleep(5000);
                assertThat(modified(heartbeat)).as("CR16: no polling against PostgreSQL, no heartbeat")
                        .isEqualTo(frozen);
            } finally {
                context.close();
            }
        } finally {
            database.close();
        }
    }

    @Test
    void withoutThePropertyTheWorkerWritesNoFile() throws Exception {
        try (ScratchDatabase database = ScratchDatabase.ownedByTheRealOwner()) {
            LaunchOutcome outcome = ConfiaApplication.launch(
                    TestProcessArguments.workerAgainst(database.jdbcUrl(), database.ownerRole(),
                            database.ownerPassword()), "worker");
            assertThat(outcome.context()).isNotNull();
            outcome.context().close();
            try (var files = Files.list(directory)) {
                assertThat(files.count()).isZero();
            }
        }
    }
}

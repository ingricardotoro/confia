package com.confia.schema;

import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/**
 * A throw-away database of the shared PostgreSQL container, migrated by the real Flyway migrations
 * as {@code confia_owner} one step at a time, so a test can look at the schema after {@code V8},
 * apply {@code V9} and look again (session-endpoints-cookie-and-csrf specs/build-integrity/spec.md,
 * scenarios BI41 and BI42). The roles are those of the container, which are cluster-wide, so every
 * grant of the migrations resolves. Dropped on close.
 */
final class StepwiseDatabase implements AutoCloseable {

    private static final String PASSWORD = "test-only-not-a-secret";

    private final String name;
    private final String url;

    private StepwiseDatabase(String name, String url) {
        this.name = name;
        this.url = url;
    }

    /** A new database with the migrations applied up to and including {@code version}. */
    static StepwiseDatabase migratedTo(String version) throws SQLException {
        String name = "step_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " OWNER confia_owner");
        }
        StepwiseDatabase database = new StepwiseDatabase(name,
                SharedPostgresContainer.jdbcUrl().replace("/confia_test", "/" + name));
        database.migrate(version);
        return database;
    }

    /** Applies the pending migrations up to and including {@code version}. */
    void migrate(String version) {
        flyway(version).migrate();
    }

    /** Applies every pending migration. */
    void migrateToLatest() {
        flyway(null).migrate();
    }

    /** Checks the history against the files on the classpath, as a deployment does. */
    void validate() {
        flyway(null).validate();
    }

    Connection connectionAs(String role) throws SQLException {
        return DriverManager.getConnection(url, role, PASSWORD);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(url, "confia_owner", PASSWORD)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    @Override
    public void close() throws SQLException {
        try (Connection admin = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        }
    }
}

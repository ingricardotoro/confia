package com.confia.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Lazy holder for the single PostgreSQL container shared by every {@code *IT.java} class in this
 * JVM (design.md decision 14; sonda S11). The container used to start in a static initializer block
 * of {@link PostgresIntegrationTest}, which meant merely <em>loading</em> that class started it —
 * exactly the trap a permanent {@code *IT} naming-rule fixture falls into: it has to extend {@link
 * PostgresIntegrationTest} (or a variant) to violate the rule, Surefire selects it by its {@code
 * *Test} name, and the JUnit platform loads the class to look for test methods, even though it
 * declares none (design.md, decision 13, "El fixture negativo permanente y su trampa").
 *
 * <p>Moving the container here, behind a holder that starts it lazily on the first call to {@link
 * #instance()}, and invoking that holder only from {@link PostgresIntegrationTest}'s {@code
 * @DynamicPropertySource} — which only runs when Spring actually builds a real test's application
 * context — means loading this class, or even loading {@link PostgresIntegrationTest} itself, costs
 * nothing until a real test needs a database.
 *
 * <p>No behavior change from the container the previous change shipped: still one container per
 * JVM, never {@code withReuse(true)}, {@code fsync=off}, {@code synchronous_commit=off}, data on
 * {@code tmpfs} over {@code /var/lib/postgresql} (this change's design.md decision 14 explicitly
 * preserves the earlier change's decision on all of this).
 */
public final class SharedPostgresContainer {

    private static final Object LOCK = new Object();

    private static volatile PostgreSQLContainer<?> instance;

    private SharedPostgresContainer() {
    }

    /** Starts the container on the first call; returns the already-running one afterward. */
    public static PostgreSQLContainer<?> instance() {
        PostgreSQLContainer<?> started = instance;
        if (started == null) {
            synchronized (LOCK) {
                started = instance;
                if (started == null) {
                    started = newContainer();
                    started.start();
                    instance = started;
                }
            }
        }
        return started;
    }

    public static String jdbcUrl() {
        return instance().getJdbcUrl();
    }

    /**
     * Opens a new, caller-owned JDBC connection authenticated as {@code role} (design.md decision
     * 10: {@code "postgres"} for the container's own bootstrap superuser, {@code "confia_owner"}
     * for the schema owner, or any of the five real, non-superuser roles from {@code
     * db/testing/create-test-roles.sql}). The caller is responsible for closing it.
     */
    public static Connection connectionAs(String role) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), role, PostgresIntegrationTest.TEST_PASSWORD);
    }

    /**
     * A single-connection {@link DataSource} over the shared container, authenticated as {@code
     * role}, for the tests that need a {@link DataSource} rather than a bare {@link Connection} —
     * for example a jqwik {@code @Property} class, which cannot extend {@code @SpringBootTest}
     * (design.md, decision 6; sonda S8).
     */
    public static DataSource dataSourceFor(String role) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(jdbcUrl());
        dataSource.setUsername(role);
        dataSource.setPassword(PostgresIntegrationTest.TEST_PASSWORD);
        return dataSource;
    }

    private static PostgreSQLContainer<?> newContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse(PostgresIntegrationTest.postgresImage()))
                .withDatabaseName("confia_test")
                .withUsername("postgres")
                .withPassword(PostgresIntegrationTest.TEST_PASSWORD)
                .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off",
                        "-c", "max_connections=200")
                .withTmpFs(Map.of("/var/lib/postgresql", "rw,size=1024m"))
                .withClasspathResourceMapping("db/testing/create-test-roles.sql",
                        "/docker-entrypoint-initdb.d/01-create-test-roles.sql", BindMode.READ_ONLY);
    }
}

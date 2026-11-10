package com.confia.bootstrap;

import com.confia.support.SharedPostgresContainer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A database of its own inside the shared PostgreSQL container, owned by one role, for the tests
 * that start the real {@code migrate} process (or another real process) and need to know exactly
 * what it did to a schema nobody else touched. Created empty on purpose: the template is the
 * server's {@code template1}, so no migration has ever run in it.
 *
 * <p>The owner is either the existing {@code confia_owner} (the role the deployed migration runs
 * as) or a throw-away role whose password is generated for the run, so a test can look for that
 * value in the output of a process and be sure it is not a fixed string that appears by chance.
 */
final class ScratchDatabase implements AutoCloseable {

    static final String OWNER_ROLE = "confia_owner";
    static final String OWNER_PASSWORD = "test-only-not-a-secret";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final String name;
    private final String ownerRole;
    private final String ownerPassword;
    private final boolean ownerIsThrowAway;

    private ScratchDatabase(String name, String ownerRole, String ownerPassword,
            boolean ownerIsThrowAway) {
        this.name = name;
        this.ownerRole = ownerRole;
        this.ownerPassword = ownerPassword;
        this.ownerIsThrowAway = ownerIsThrowAway;
    }

    /** An empty database owned by {@code confia_owner}, with the password of the test roles. */
    static ScratchDatabase ownedByTheRealOwner() throws SQLException {
        ScratchDatabase database = new ScratchDatabase(nextName(), OWNER_ROLE, OWNER_PASSWORD, false);
        database.create();
        return database;
    }

    /** An empty database owned by a new role with a password generated for this run. */
    static ScratchDatabase ownedByAThrowAwayRole() throws SQLException {
        String name = nextName();
        String role = "probe_" + name;
        ScratchDatabase database = new ScratchDatabase(name, role, "pw-" + UUID.randomUUID(), true);
        database.create();
        return database;
    }

    String ownerRole() {
        return ownerRole;
    }

    String ownerPassword() {
        return ownerPassword;
    }

    String jdbcUrl() {
        return "jdbc:postgresql://" + SharedPostgresContainer.instance().getHost() + ":"
                + SharedPostgresContainer.instance().getMappedPort(5432) + "/" + name;
    }

    /** The process arguments that point a real process at this database as its owner. */
    String[] ownerArguments(String... extra) {
        String[] base = {"--spring.datasource.url=" + jdbcUrl(),
                "--spring.datasource.username=" + ownerRole,
                "--spring.datasource.password=" + ownerPassword};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return all;
    }

    /** A connection to this database as its owner; the caller closes it. */
    Connection connectAsOwner() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), ownerRole, ownerPassword);
    }

    private void create() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = connection.createStatement()) {
            if (ownerIsThrowAway) {
                statement.execute("CREATE ROLE " + ownerRole + " LOGIN PASSWORD '" + ownerPassword
                        + "' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE");
            }
            statement.execute("CREATE DATABASE " + name + " OWNER " + ownerRole);
        }
    }

    @Override
    public void close() throws SQLException {
        try (Connection connection = SharedPostgresContainer.connectionAs("postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
            if (ownerIsThrowAway) {
                statement.execute("DROP ROLE IF EXISTS " + ownerRole);
            }
        }
    }

    private static String nextName() {
        return "scratch_" + SEQUENCE.incrementAndGet() + "_" + Long.toHexString(System.nanoTime());
    }
}

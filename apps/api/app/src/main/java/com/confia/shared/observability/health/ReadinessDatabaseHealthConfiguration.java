package com.confia.shared.observability.health;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code db} member of the readiness group (dockerfiles-and-local-compose design.md decision 4.3;
 * scenarios CR07, CR10, CR12). The bean is named {@code dbHealthContributor}, which displaces the
 * auto-configured one (that one backs off when this name exists), and the contributor name {@code db} that
 * the readiness group lists comes from this bean name.
 *
 * <p>It runs {@value #PROBE_SQL} on the process's own {@code DataSource}, not {@code Connection.isValid()}:
 * a process pointed at a database nobody migrated must not report ready (CR07), and only a query of the
 * migration history shows that. The statement is trivial, tests the connection and the role, and uses the
 * {@code SELECT} that ADR-0017 already grants {@code confia_admin_app} and {@code confia_portal_app} on that
 * table; it compares no version (that is the {@code migrations} indicator of change 10). It goes through
 * plain JDBC with no bound value, so it is not jOOQ plain SQL (ADR-0015 rule 9).
 *
 * <p>The result carries no detail whatever the failure: the exception is never put in the {@link Health},
 * and the log line names only the exception class, never its message, which can hold the host and the port.
 * Imported explicitly by the administrative and the portal entry points (ADR-0024).
 */
@Configuration(proxyBeanMethods = false)
public class ReadinessDatabaseHealthConfiguration {

    /** The statement of the probe. */
    static final String PROBE_SQL = "SELECT 1 FROM flyway_schema_history LIMIT 1";

    private static final Logger LOG = LoggerFactory.getLogger(ReadinessDatabaseHealthConfiguration.class);

    @Bean
    HealthIndicator dbHealthContributor(DataSource dataSource) {
        return () -> check(dataSource);
    }

    static Health check(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet ignored = statement.executeQuery(PROBE_SQL)) {
            return Health.up().build();
        } catch (SQLException | RuntimeException e) {
            LOG.warn("readiness: the database probe failed ({})", e.getClass().getSimpleName());
            return Health.down().build();
        }
    }
}

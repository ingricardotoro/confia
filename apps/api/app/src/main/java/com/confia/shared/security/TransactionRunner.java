package com.confia.shared.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single transactional component of {@code shared/security} (ADR-0015 rule 7; design.md,
 * decision 1 and decision 2). {@code final}, with an explicit constructor over {@link
 * PlatformTransactionManager} and {@link DataSource} and no Spring annotation — the same pattern
 * {@code JooqInstitutionRepository} already established: no bootstrap process registers this as a
 * bean yet, and registering it is the job of the first change that consumes it (change 6).
 *
 * <p>Deliberately never touches jOOQ ({@code R1 JooqConfinedToInfrastructureTest} confines it to
 * {@code infrastructure}, and this component lives outside every layer): it opens its transaction
 * with {@link TransactionTemplate} and issues the session-context statement directly over the JDBC
 * {@link Connection} bound to that transaction ({@link DataSourceUtils#getConnection(DataSource)}),
 * before the caller's use case runs. jOOQ participates in the same transaction without knowing it,
 * because the {@code DSLContext} production code eventually builds sits on a {@code
 * TransactionAwareDataSourceProxy} over this same {@link DataSource} (design.md, decision 2).
 *
 * <p><b>The bounded retry (ADR-0010, lines 203-205) is not implemented yet in this class</b>: it
 * lands in the same {@link #execute} methods in the next step of this change's TDD sequence
 * (design.md §11, steps 6-7; {@code TransactionRunnerRetryIT}), which is why {@link #maxRetries} and
 * {@link #backoffBase} are accepted and stored, but not yet consulted.
 */
public final class TransactionRunner {

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final Duration DEFAULT_BACKOFF_BASE = Duration.ofMillis(20);

    private static final String SET_SECURITY_CONTEXT_SQL = """
            select set_config('app.actor_id',       ?, true),
                   set_config('app.actor_kind',     ?, true),
                   set_config('app.institution_id', ?, true),
                   set_config('app.request_id',     ?, true)
            """;

    private final DataSource dataSource;
    private final TransactionTemplate readCommittedTemplate;
    private final TransactionTemplate serializableTemplate;
    private final int maxRetries;
    private final Duration backoffBase;

    public TransactionRunner(PlatformTransactionManager transactionManager, DataSource dataSource) {
        this(transactionManager, dataSource, DEFAULT_MAX_RETRIES, DEFAULT_BACKOFF_BASE);
    }

    public TransactionRunner(PlatformTransactionManager transactionManager, DataSource dataSource,
            int maxRetries, Duration backoffBase) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.maxRetries = maxRetries;
        this.backoffBase = Objects.requireNonNull(backoffBase, "backoffBase");
        this.readCommittedTemplate =
                newTemplate(transactionManager, TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.serializableTemplate =
                newTemplate(transactionManager, TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    /** {@code READ COMMITTED}, the default for every use case that does not require otherwise. */
    public <T> T execute(SecurityContext context, Supplier<T> useCase) {
        return execute(context, IsolationLevel.READ_COMMITTED, useCase);
    }

    /** Explicit isolation level, for the use cases ADR-0010 requires {@code SERIALIZABLE} for. */
    public <T> T execute(SecurityContext context, IsolationLevel isolation, Supplier<T> useCase) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(isolation, "isolation");
        Objects.requireNonNull(useCase, "useCase");
        TransactionTemplate template = templateFor(isolation);
        return template.execute(status -> {
            applySecurityContext(context);
            return useCase.get();
        });
    }

    private TransactionTemplate templateFor(IsolationLevel isolation) {
        return isolation == IsolationLevel.SERIALIZABLE ? serializableTemplate
                : readCommittedTemplate;
    }

    /**
     * The first statement of the transaction ({@code docs/03} section 6.2), with bound parameters,
     * never {@code SET SESSION}: {@code set_config(..., true)} is local to the current transaction,
     * so it never survives onto a connection the pool later hands to a different transaction
     * (design.md, decision 2, "Valores ausentes"; the scenario {@code
     * TransactionRunnerContextIT#noContextSurvivesOnAReusedPoolConnection} depends on this).
     */
    private void applySecurityContext(SecurityContext context) {
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            try (PreparedStatement statement = connection.prepareStatement(SET_SECURITY_CONTEXT_SQL)) {
                statement.setString(1, context.actorId());
                statement.setString(2, context.actorKind());
                statement.setString(3, context.institutionId());
                statement.setString(4, context.requestId());
                statement.execute();
            }
        } catch (SQLException e) {
            throw new UncategorizedSQLException("apply the security context", SET_SECURITY_CONTEXT_SQL,
                    e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private static TransactionTemplate newTemplate(PlatformTransactionManager transactionManager,
            int isolationLevel) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(isolationLevel);
        return template;
    }
}

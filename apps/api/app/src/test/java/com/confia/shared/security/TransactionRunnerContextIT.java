package com.confia.shared.security;

import static confia.generated.jooq.tables.OrganizationInstitution.ORGANIZATION_INSTITUTION;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.PostgresIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Contract of the session context, isolation level and connection hygiene of {@link
 * TransactionRunner} (design.md, decision 1 and decision 2; specs/build-integrity/spec.md,
 * requirement "Contexto de sesión, nivel de aislamiento y reintento acotado del componente
 * transaccional único" — the first four scenarios; the retry scenarios live in {@link
 * TransactionRunnerRetryIT}).
 *
 * <p>Extends the bare {@link PostgresIntegrationTest}, never {@link
 * com.confia.support.TransactionalPostgresIntegrationTest}: the whole point of this class is that
 * {@link TransactionRunner} opens its own, genuinely separate transactions, one per {@code
 * execute(...)} call. Wrapping the test method itself in an outer Spring-managed transaction would
 * make {@link TransactionRunner}'s {@code TransactionTemplate} (default propagation {@code
 * REQUIRED}) simply join that outer transaction instead of starting a new one, which would defeat
 * the two-separate-transactions scenario below.
 *
 * <p>Institutions are seeded through a raw JDBC {@link Connection} obtained directly from the pooled
 * {@link DataSource}, never through {@link PostgresIntegrationTest#withInstitutionContext}: that
 * helper's Javadoc (design.md, decision 9, point 1) explicitly documents that it only works inside a
 * test transaction — without one, {@code set_config(..., true)} is local to a single autocommit
 * statement and disappears before the next one runs, which is exactly the false-green trap this test
 * must not fall into. The raw connection here opens one explicit transaction (autocommit off),
 * issues {@code set_config} and the insert as two statements of that same transaction, then commits
 * — deliberately not using {@link TransactionRunner} itself for seeding, so the fixture never
 * depends on the very component it verifies.
 */
class TransactionRunnerContextIT extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Pins the pool to a single physical connection so {@link
     * #noContextSurvivesOnAReusedPoolConnection()} genuinely exercises connection reuse instead of
     * merely hoping for it (design.md, scenario "Ningún contexto sobrevive a la transacción sobre
     * una conexión reutilizada del pool").
     */
    @DynamicPropertySource
    static void pinToASingleConnection(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    /**
     * Structurally order-sensitive: {@code organization_institution}'s row-level-security policy
     * denies by default (V1 migration). If the four {@code set_config} calls ran after the query
     * instead of before it — or not at all — this query returns zero rows, not institution A's row,
     * so the assertion below fails rather than passing for the wrong reason.
     */
    @Test
    void contextIsSetBeforeTheFirstQueryOfTheUseCase() throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        seedCommittedInstitution(institutionA);
        seedCommittedInstitution(institutionB);
        TransactionRunner runner = new TransactionRunner(transactionManager, dataSource);

        List<UUID> visibleIds = runner.execute(contextOf(institutionA),
                () -> dsl.selectFrom(ORGANIZATION_INSTITUTION).fetch(ORGANIZATION_INSTITUTION.ID));

        assertThat(visibleIds)
                .as("the four set_config calls must already be visible to this query, or the "
                        + "row-level-security policy denies by default and this returns zero rows "
                        + "instead of exactly institution A's row")
                .containsExactly(institutionA);
    }

    @Test
    void noContextSurvivesOnAReusedPoolConnection() throws SQLException {
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        seedCommittedInstitution(institutionA);
        seedCommittedInstitution(institutionB);
        TransactionRunner runner = new TransactionRunner(transactionManager, dataSource);

        BackendObservation first = runner.execute(contextOf(institutionA), () -> new BackendObservation(
                dsl.selectFrom(ORGANIZATION_INSTITUTION).fetch(ORGANIZATION_INSTITUTION.ID),
                backendPid()));
        BackendObservation second = runner.execute(contextOf(institutionB), () -> new BackendObservation(
                dsl.selectFrom(ORGANIZATION_INSTITUTION).fetch(ORGANIZATION_INSTITUTION.ID),
                backendPid()));

        assertThat(second.backendPid())
                .as("the pool is pinned to a single connection (maximum-pool-size=1): both "
                        + "transactions must run on the very same PostgreSQL backend process, or "
                        + "this scenario is not actually exercising connection reuse")
                .isEqualTo(first.backendPid());
        assertThat(second.visibleIds())
                .as("the second transaction, on the reused connection, must see only its own "
                        + "institution and nothing left over from the first transaction's context; "
                        + "this is the property that would fail if the component used SET SESSION "
                        + "instead of set_config(..., true), because SET SESSION survives a "
                        + "transaction's commit and would contaminate the reused connection")
                .containsExactly(institutionB);
    }

    @Test
    void defaultIsolationIsReadCommittedInTheRealTransaction() {
        TransactionRunner runner = new TransactionRunner(transactionManager, dataSource);

        String isolation = runner.execute(contextOf(UUID.randomUUID()), this::currentIsolation);

        assertThat(isolation).isEqualToIgnoringCase("read committed");
    }

    @Test
    void explicitSerializableIsAppliedInTheRealTransaction() {
        TransactionRunner runner = new TransactionRunner(transactionManager, dataSource);

        String isolation = runner.execute(contextOf(UUID.randomUUID()), IsolationLevel.SERIALIZABLE,
                this::currentIsolation);

        assertThat(isolation).isEqualToIgnoringCase("serializable");
    }

    private String currentIsolation() {
        return dsl.fetchOne("select current_setting('transaction_isolation')").get(0, String.class);
    }

    private Integer backendPid() {
        return dsl.fetchOne("select pg_backend_pid()").get(0, Integer.class);
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }

    /**
     * Seeds one committed institution row on its own connection and its own explicit transaction,
     * entirely independent of {@link TransactionRunner} — the class under test — so the fixture
     * never depends on the thing it verifies (design.md, decision 9, point 1's same reasoning,
     * applied one PR early because {@link com.confia.support.CommittingPostgresIntegrationTest}
     * does not exist yet at this point in the sequence, task 1.5).
     */
    private void seedCommittedInstitution(UUID institutionId) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var setContext = connection
                    .prepareStatement("select set_config('app.institution_id', ?, true)")) {
                setContext.setString(1, institutionId.toString());
                setContext.execute();
            }
            try (var insert = connection.prepareStatement("""
                    insert into organization_institution
                        (id, legal_name, rtn, address, default_currency, locale, timezone, is_active)
                    values (?, ?, ?, ?, ?, ?, ?, true)
                    """)) {
                insert.setObject(1, institutionId);
                insert.setString(2, "Instituto de prueba " + institutionId);
                insert.setString(3, "12345678");
                insert.setString(4, "Dirección de prueba");
                insert.setString(5, "HNL");
                insert.setString(6, "es-HN");
                insert.setString(7, "America/Tegucigalpa");
                insert.executeUpdate();
            }
            connection.commit();
        }
    }

    private record BackendObservation(List<UUID> visibleIds, Integer backendPid) {
    }
}

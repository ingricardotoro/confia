package com.confia.support;

import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PostgresIntegrationTest} variant that wraps every test method in a Spring-managed
 * database transaction and rolls it back once the method ends (design.md decision 7). Every row a
 * test seeds by direct SQL — the only way {@code *IT.java} classes are allowed to seed rows in
 * this part of the change, since {@link com.confia.organization.infrastructure.JooqInstitutionRepository}
 * is read-only — disappears automatically, so tests never have to truncate tables themselves or
 * depend on execution order.
 *
 * <p>Created in PR A2 rather than PR A1 (apply-progress.md, "Discrepancia reportada... variantes de
 * {@code PostgresIntegrationTest}"): PR A1's own classes, {@code DatabasePipelineIT} and {@code
 * PostgresImageSingleSourceTest}, never seed or touch a single row of business data, so neither
 * needed automatic rollback. {@link com.confia.organization.infrastructure.JooqInstitutionRepositoryIT}
 * (task 2.1) is the first consumer: its round-trip, absence, duplicate-key and isolation scenarios
 * all seed rows by direct SQL and must not leak them into one another or into a later {@code
 * *IT.java} class sharing the same per-JVM container (design.md decision 7).
 *
 * <p>{@code @Transactional} here relies on Spring Boot's {@code DataSourceTransactionManagerAutoConfiguration}
 * (confirmed present in the separate {@code spring-boot-jdbc} artifact that {@code
 * spring-boot-starter-jdbc} pulls in transitively — Spring Boot 4.1 split its autoconfiguration
 * jar per feature, apply-progress.md task 1.1/1.5, S1.6's follow-up), which registers a {@code
 * PlatformTransactionManager} bean the moment a {@code DataSource} bean exists. No transaction
 * manager is declared by hand.
 */
@Transactional
public abstract class TransactionalPostgresIntegrationTest extends PostgresIntegrationTest {

    // Class-level @Transactional: every @Test method in a subclass runs in its own transaction,
    // started before the method and rolled back after it, exactly like production's
    // shared/security transactional component will do from the part B change on — except this one
    // is test-only scaffolding, never the ADR-0015 rule 7 component itself (design.md, "Contratos
    // e interfaces", PostgresIntegrationTest.withInstitutionContext Javadoc).
}

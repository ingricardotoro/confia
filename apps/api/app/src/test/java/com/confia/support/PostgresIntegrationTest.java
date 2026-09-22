package com.confia.support;

import com.confia.kernel.InstitutionId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of every {@code *IT.java} class that needs a real PostgreSQL (docs/06-estrategia-de-testing.md
 * section 14.2; design.md decision 7). Delegates the container itself to {@link
 * SharedPostgresContainer}'s lazy holder (design.md decision 14): one container per test JVM,
 * started on the first real test's {@code @DynamicPropertySource} call rather than in a static
 * block, and shared by every subclass so the suite stays inside its 8-minute budget (design.md,
 * "Detalles que sostienen el presupuesto de 8 minutos"). Never {@code withReuse(true)}: reuse
 * across builds needs a machine-wide opt-in file and leaves containers running, which is not
 * deterministic in CI (design.md decision 7).
 *
 * <p><b>Bootstraps as {@code postgres}, never {@code confia_owner}</b> (design.md decision 4): the
 * official image makes whatever {@code POSTGRES_USER} it is given a real superuser with {@code
 * BYPASSRLS}, and a superuser silently bypasses {@code FORCE ROW LEVEL SECURITY}. Naming that user
 * {@code confia_owner}, as {@code docs/06} section 14.2's example literally does, would make every
 * row-level-security test in this codebase pass for the wrong reason. The five real,
 * non-superuser roles come from {@code db/testing/create-test-roles.sql}, mounted at {@code
 * /docker-entrypoint-initdb.d/01-create-test-roles.sql} — never {@code withInitScript(...)}, which
 * runs a script through Testcontainers' own statement splitter instead of the image's real {@code
 * psql}.
 *
 * <p><b>{@code withTmpFs} targets {@code /var/lib/postgresql}, not {@code .../data}</b>: {@code
 * postgres:18-alpine} refuses to start with a mount at the old path (PostgreSQL 18 changed to a
 * {@code pg_ctlcluster}-compatible, major-version-specific data directory layout,
 * docker-library/postgres#1259). This is a real difference from the {@code docs/06} section 14.2
 * example, confirmed with a real container start in both configurations (apply-progress.md task
 * 1.1, S1.5).
 */
@SpringBootTest(classes = IntegrationTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
public abstract class PostgresIntegrationTest {

    protected static final String TEST_PASSWORD = "test-only-not-a-secret";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // Flyway migrates as the schema owner. In deployed processes only APP_PROFILE=migrate
        // runs Flyway (docs/05-infraestructura-y-despliegue.md; application-migrate.yml, task
        // 1.5); application.yml disables it by default, so these tests turn it back on and point
        // it at confia_owner explicitly.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.url", SharedPostgresContainer::jdbcUrl);
        registry.add("spring.flyway.user", () -> "confia_owner");
        registry.add("spring.flyway.password", () -> TEST_PASSWORD);

        // The application connects with its least-privilege role, never as the owner and never as
        // the container's own bootstrap superuser.
        registry.add("spring.datasource.url", SharedPostgresContainer::jdbcUrl);
        registry.add("spring.datasource.username", () -> "confia_admin_app");
        registry.add("spring.datasource.password", () -> TEST_PASSWORD);
    }

    @Autowired
    protected DSLContext dsl;

    /**
     * Single source of truth for the PostgreSQL image (ADR-0015 rule 1; design.md decision 3;
     * task 1.6): reads {@code confia-build.properties}, filtered by maven-resources-plugin from
     * {@code apps/api/pom.xml}'s {@code confia.postgres.image} property — the same property the
     * jOOQ code-generation plugin reads (task 1.7). {@link PostgresImageSingleSourceTest} is the
     * executable proof that this value is real and not the unfiltered {@code "${...}"} literal.
     */
    static String postgresImage() {
        Properties properties = new Properties();
        try (InputStream in = PostgresIntegrationTest.class.getClassLoader()
                .getResourceAsStream("confia-build.properties")) {
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("postgres.image");
    }

    /**
     * Sets the session-scoped institution context (docs/03-seguridad.md section 6.2) the same way
     * production code eventually will: a bound parameter, local to the current transaction ({@code
     * set_config(..., true)}), never interpolated and never {@code SET SESSION}. This is
     * deliberately not the transactional component of ADR-0015 rule 7, which the part B change
     * delivers: it only ever sets {@code app.institution_id}, the one parameter the root table's
     * policy reads (design.md, "Contratos e interfaces").
     *
     * <p><b>Only works inside a test transaction.</b> {@code set_config(..., true)} is local to the
     * current transaction, not to the connection or the statement. A subclass with no {@code
     * @Transactional} at the class level (the bare {@code PostgresIntegrationTest}, never {@link
     * TransactionalPostgresIntegrationTest} or {@link CommittingPostgresIntegrationTest} — the
     * latter opens its own transactions exclusively through the real {@link
     * com.confia.shared.security.TransactionRunner}) runs every statement in its own implicit
     * autocommit transaction: the setting made here would disappear before {@code body} ran its own,
     * separate statement, and the row-level-security policy would silently deny by default instead
     * of allowing the row this call meant to make visible — a false green of exactly the kind this
     * change's design.md (decision 9) exists to name and avoid. Use a real, committed connection
     * through {@link com.confia.shared.security.TransactionRunner} instead for a subclass that needs
     * confirmed, visible-outside-its-own-transaction rows.
     */
    protected void withInstitutionContext(InstitutionId institutionId, Runnable body) {
        dsl.execute("select set_config('app.institution_id', ?, true)",
                institutionId.value().toString());
        body.run();
    }
}

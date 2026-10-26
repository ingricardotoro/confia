package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.bootstrap.ProcessBeanInspector.InspectedBean;
import com.confia.bootstrap.admin.AdminApplication;
import com.confia.bootstrap.portal.PortalApplication;
import com.confia.bootstrap.worker.WorkerApplication;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.logging.SensitiveLogGuard;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.TransactionalDataSource;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * Specs/build-integrity of background-jobs-with-db-scheduler (BI91, BI92, BI111, BI115) and
 * background-jobs (BJ46): the worker process registers its own narrow data access (decision 12),
 * starts with a database nothing listens on and without the column-encryption master key or the
 * Argon2id pepper, and the rules that keep that true are shown to fail on a fixture. Every context
 * is started through the production launcher with {@link TestProcessArguments}, so no container is
 * needed and this stays a {@code *Test}.
 */
class WorkerProductionWiringTest {

    // --- BI92: the narrow wiring ---

    @Test
    void theWorkerHoldsItsNarrowDataAccessAndNothingAdministrative() {
        ConfigurableApplicationContext context = start("worker");
        try {
            assertThat(context.getBeansOfType(DataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(DSLContext.class)).hasSize(1);
            assertThat(context.getBeansOfType(TransactionRunner.class)).hasSize(1);
            assertThat(context.getBeansOfType(TransactionalDataSource.class)).hasSize(1);
            assertThat(context.getBeansOfType(SensitiveLogGuard.class)).hasSize(1);
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBeansOfType(AuditLogWriter.class)).hasSize(1).values()
                    .allSatisfy(writer -> assertThat(writer)
                            .as("the plain writer, without the request-origin decorator")
                            .isExactlyInstanceOf(JooqAuditLogWriter.class));
        } finally {
            context.close();
        }
    }

    // --- BI111: no master key, no pepper, nothing of crypto or identity ---

    @Test
    void theWorkerStartsWithoutTheMasterKeyOrThePepperAndHoldsNoCryptoOrIdentityBean() {
        String[] arguments = TestProcessArguments.forProcess("worker");
        assertThat(Arrays.toString(arguments))
                .doesNotContain(TestProcessArguments.MASTER_KEY_PROPERTY)
                .doesNotContain(TestProcessArguments.PEPPER_PROPERTY);

        ConfigurableApplicationContext context = start("worker", arguments);
        try {
            List<InspectedBean> confiaBeans = ProcessBeanInspector.confiaBeans(context);
            assertThat(confiaBeans).as("non-vacuous: the worker holds beans of its own").isNotEmpty();
            assertThat(confiaBeans).as("no bean of crypto or identity in the worker")
                    .noneMatch(bean -> bean.originPackages().stream()
                            .anyMatch(pkg -> ProcessBeanPolicy.matches(pkg, "com.confia.shared.crypto")
                                    || ProcessBeanPolicy.matches(pkg, "com.confia.identity")));
        } finally {
            context.close();
        }
    }

    @Test
    void aCryptoBeanInTheWorkerBreaksTheIsolationRuleNamingProcessBeanAndPackage() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try (AnnotationConfigApplicationContext fixture = new AnnotationConfigApplicationContext()) {
            fixture.registerBean("columnEncryptionMasterKey", ColumnEncryptionMasterKey.class,
                    () -> ColumnEncryptionMasterKey.fromBase64(
                            Base64.getEncoder().encodeToString(key)));
            fixture.refresh();

            assertThat(ProcessBeanInspector.violations(fixture, ProcessBeanPolicy.WORKER))
                    .anyMatch(line -> line.contains("process 'worker'")
                            && line.contains("columnEncryptionMasterKey")
                            && line.contains("com.confia.shared.crypto")
                            && line.contains("the worker holds no column-encryption key"));
        }
    }

    // --- BI91: a data source needs the guard ---

    @Test
    void theRealWorkerHasADataSourceAndTheGuardIsActiveInItsContext() {
        ConfigurableApplicationContext context = start("worker");
        try {
            assertThat(context.getBeansOfType(DataSource.class)).as("non-vacuous").hasSize(1);
            assertThat(unguardedDataSources(context)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void aContextWithADataSourceAndNoGuardIsReported() {
        try (AnnotationConfigApplicationContext fixture = new AnnotationConfigApplicationContext()) {
            fixture.registerBean("dataSource", DataSource.class,
                    () -> new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never"));
            fixture.refresh();

            assertThat(unguardedDataSources(fixture)).singleElement().asString()
                    .contains("DataSource").contains("sensitive log guard");
        }
    }

    /** One line when the context has a {@link DataSource} and the guard is not in it and in the chain. */
    static List<String> unguardedDataSources(ConfigurableApplicationContext context) {
        if (context.getBeansOfType(DataSource.class).isEmpty()) {
            return List.of();
        }
        List<SensitiveLogGuard> guards =
                new ArrayList<>(context.getBeansOfType(SensitiveLogGuard.class).values());
        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        boolean active = guards.size() == 1 && logback.getTurboFilterList().contains(guards.get(0));
        return active ? List.of()
                : List.of("the context has a DataSource but the sensitive log guard is not active in it");
    }

    // --- BI115: no entry point excludes the DataSource ---

    /**
     * The portal still excludes {@code DataSourceAutoConfiguration} until it receives its own
     * {@code DataSource} (decision D-N1, task 2.6); that task removes this exemption and the
     * assertion that it still holds.
     */
    private static final Set<Class<?>> STILL_EXCLUDING = Set.of(PortalApplication.class);

    @Test
    void theWorkerAndTheAdministrationEntryPointsDoNotExcludeTheDataSource() {
        assertThat(entryPointsExcludingTheDataSource(List.of(AdminApplication.class,
                WorkerApplication.class))).isEmpty();
    }

    @Test
    void thePortalIsTheOnlyEntryPointStillExcludingItAndTheExemptionIsExplicit() {
        assertThat(entryPointsExcludingTheDataSource(List.of(PortalApplication.class)))
                .as("remove PortalApplication from STILL_EXCLUDING when task 2.6 gives it a DataSource")
                .containsExactly(PortalApplication.class.getName());
        assertThat(STILL_EXCLUDING).containsExactly(PortalApplication.class);
    }

    @Test
    void anEntryPointThatExcludesTheDataSourceIsReportedByName() {
        assertThat(entryPointsExcludingTheDataSource(List.of(ExcludingFixture.class)))
                .containsExactly(ExcludingFixture.class.getName());
    }

    /** Fixture: a worker-like entry point that drops the {@code DataSource}. */
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class ExcludingFixture {
    }

    static List<String> entryPointsExcludingTheDataSource(List<Class<?>> entryPoints) {
        List<String> names = new ArrayList<>();
        for (Class<?> entryPoint : entryPoints) {
            EnableAutoConfiguration annotation =
                    entryPoint.getAnnotation(EnableAutoConfiguration.class);
            Set<String> excluded = new HashSet<>(List.of(annotation.excludeName()));
            for (Class<?> type : annotation.exclude()) {
                excluded.add(type.getName());
            }
            if (excluded.contains(DataSourceAutoConfiguration.class.getName())) {
                names.add(entryPoint.getName());
            }
        }
        return names;
    }

    // --- BJ46: one transaction-aware proxy per process ---

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "worker"})
    void theDslContextAndTheTransactionalDataSourceShareTheOneProxy(String process) {
        ConfigurableApplicationContext context = start(process);
        try {
            assertThat(context.getBeansOfType(DSLContext.class)).as("non-vacuous").hasSize(1);
            assertThat(proxyViolations(context)).isEmpty();
        } finally {
            context.close();
        }
    }

    @Test
    void aSecondProxyBuiltOverTheSameDataSourceIsReported() {
        try (AnnotationConfigApplicationContext fixture = new AnnotationConfigApplicationContext()) {
            DataSource raw = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/never");
            fixture.registerBean("transactionalDataSource", TransactionalDataSource.class,
                    () -> new TransactionalDataSource(raw));
            fixture.registerBean("dslContext", DSLContext.class, () -> DSL.using(
                    new DefaultConfiguration().set(new DataSourceConnectionProvider(
                            new TransactionAwareDataSourceProxy(raw)))
                            .set(SQLDialect.POSTGRES)));
            fixture.refresh();

            assertThat(proxyViolations(fixture)).singleElement().asString()
                    .contains("DSLContext").contains("not the one");
        }
    }

    /** One line per {@link DSLContext} that does not sit on the process's single proxy. */
    static List<String> proxyViolations(ConfigurableApplicationContext context) {
        List<TransactionalDataSource> holders =
                new ArrayList<>(context.getBeansOfType(TransactionalDataSource.class).values());
        if (holders.size() != 1) {
            return List.of("expected exactly one TransactionalDataSource but found " + holders.size());
        }
        List<String> violations = new ArrayList<>();
        context.getBeansOfType(DSLContext.class).forEach((name, dsl) -> {
            boolean shared = dsl.configuration().connectionProvider()
                    instanceof DataSourceConnectionProvider provider
                    && provider.dataSource() == holders.get(0).proxy();
            if (!shared) {
                violations.add("the DSLContext '" + name
                        + "' does not use the transaction-aware proxy; its proxy is not the one of "
                        + "the process");
            }
        });
        return violations;
    }

    private static ConfigurableApplicationContext start(String process) {
        return start(process, TestProcessArguments.forProcess(process));
    }

    private static ConfigurableApplicationContext start(String process, String[] arguments) {
        LaunchOutcome outcome = ConfiaApplication.launch(arguments, process);
        assertThat(outcome.context()).as("the %s process must start", process).isNotNull();
        return outcome.context();
    }
}

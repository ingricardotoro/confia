package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.logging.SensitiveLogGuard;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Specs BI87 and BI109 (background-jobs-with-db-scheduler design.md decision 13; condition 4 of the
 * foundations plan): the sensitive log guard is installed before any bean of the context is created,
 * in the three real processes, and is withdrawn when the context closes. A guard installed by a bean
 * would exist only after other beans, among them a future {@code DataSource}, were already
 * created and could already be logging bound values.
 *
 * <p>The observer is an initializer of the test, registered in the test {@code spring.factories}
 * and switched on for the real process by the {@code confia.test.observe-log-guard} property. It
 * registers a {@link BeanFactoryPostProcessor}, which Spring runs before it instantiates any
 * regular bean, and records whether the guard was already in the Logback filter chain at that
 * moment.
 */
public class SensitiveLogGuardInstallationTest {

    private static final AtomicReference<Boolean> INSTALLED_BEFORE_ANY_BEAN =
            new AtomicReference<>();

    /**
     * Public, with a public no-argument constructor: Spring instantiates it by name from {@code
     * META-INF/spring.factories} of the test resources, and it acts only where the property is set.
     */
    public static final class Observer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        static final String PROPERTY = "confia.test.observe-log-guard";

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            if (!"true".equals(context.getEnvironment().getProperty(PROPERTY))) {
                return;
            }
            context.addBeanFactoryPostProcessor((BeanFactoryPostProcessor) beanFactory ->
                    INSTALLED_BEFORE_ANY_BEAN.set(guardsInChain().size() == 1));
        }
    }

    private static List<TurboFilter> guardsInChain() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getTurboFilterList().stream()
                .filter(filter -> filter instanceof SensitiveLogGuard).toList();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "portal", "worker"})
    void theGuardIsInTheChainBeforeTheFirstBeanAndIsWithdrawnOnClose(String process) {
        INSTALLED_BEFORE_ANY_BEAN.set(null);
        LaunchOutcome outcome = ConfiaApplication.launch(TestProcessArguments.forProcess(process,
                "--" + Observer.PROPERTY + "=true"), process);
        ConfigurableApplicationContext context = outcome.context();
        assertThat(context).as("the %s process must start", process).isNotNull();
        try {
            assertThat(INSTALLED_BEFORE_ANY_BEAN.get())
                    .as("non-vacuous: the observer ran in the %s process", process).isNotNull();
            assertThat(INSTALLED_BEFORE_ANY_BEAN.get())
                    .as("exactly one guard in the chain when bean creation begins in %s", process)
                    .isTrue();
            assertThat(guardsInChain()).as("one guard while the %s context is open", process)
                    .hasSize(1);
            assertThat(context.getBeansOfType(SensitiveLogGuard.class))
                    .as("the one guard of the %s process is a bean of its context", process)
                    .hasSize(1)
                    .containsValue((SensitiveLogGuard) guardsInChain().getFirst());
        } finally {
            context.close();
        }

        assertThat(guardsInChain()).as("the guard is withdrawn when the %s context closes", process)
                .isEmpty();
    }

    /** BI87: one list of prefixes in the code base, so the three processes cannot diverge. */
    @Test
    void thereIsExactlyOneListOfProtectedPrefixesInProduction() {
        List<String> declarations = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.confia").stream()
                .flatMap(javaClass -> javaClass.getFields().stream())
                .filter(field -> field.getName().equals("PROTECTED_PREFIXES"))
                .map(JavaField::getOwner).map(JavaClass::getFullName).toList();

        assertThat(declarations).containsExactly(SensitiveLogGuard.class.getName());
    }
}

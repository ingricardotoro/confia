package com.confia.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.bootstrap.ConfiaApplication.LaunchOutcome;
import com.confia.shared.observability.metrics.LogRateLimitMetrics;
import com.confia.shared.security.RateLimiter;
import com.confia.shared.security.token.SessionTokenConfiguration;
import com.confia.shared.security.token.SigningKeyRing;
import com.confia.shared.web.delay.DelayProperties;
import com.confia.shared.web.delay.RequiredDelayMaterializer;
import com.confia.shared.web.ratelimit.RateLimitInterceptor;
import com.confia.shared.web.ratelimit.RateLimitMetrics;
import com.confia.shared.web.ratelimit.RateLimitProperties;
import com.confia.shared.web.ratelimit.RateLimiterRegistry;
import jakarta.servlet.Filter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Specs/build-integrity of process-entry-point-isolation: each of the three processes, started
 * through the production launcher and without a database, contains only the {@code com.confia}
 * beans its {@link ProcessBeanPolicy} allows (ADR-0003, ADR-0024). The allow-list fails closed;
 * the forbidden list is nominal and additional.
 */
class ProcessBeanIsolationTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("com.confia.bootstrap.ProcessBeanPolicy#all")
    void registersOnlyItsAllowedBeans(ProcessBeanPolicy policy) {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess(policy.process()), policy.process());
        ConfigurableApplicationContext context = outcome.context();
        try {
            assertThat(context).as("the %s process must start", policy.process()).isNotNull();
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(ProcessBeanInspector.violations(context, policy))
                    .as("beans outside the allow-list or in a forbidden package")
                    .isEmpty();
            softly.assertThat(ProcessBeanInspector.entryPointConfigurations(context))
                    .as("exactly one entry point configuration in the %s context",
                            policy.process())
                    .hasSize(1);
            softly.assertThat(ProcessBeanInspector.confiaBeans(context))
                    .as("non-vacuous: the inspector must see real beans of the entry package")
                    .anyMatch(bean -> bean.originPackages().contains(policy.entryPackage()));
            // Non-vacuity of the imports: every other allowed package must contribute a bean, so
            // a lost @Import (for example the OpenAPI surface of a web process) is caught.
            for (String imported : importedPackages(policy)) {
                softly.assertThat(ProcessBeanInspector.confiaBeans(context))
                        .as("non-vacuous: %s must contribute a bean to the %s context",
                                imported, policy.process())
                        .anyMatch(bean -> bean.originPackages().contains(imported));
            }
            // The inspector only sees com.confia beans, so a web server leaking into the worker
            // (for example through springdoc) would otherwise go unnoticed.
            if ("worker".equals(policy.process())) {
                softly.assertThat(context)
                        .as("the worker must start without a web server")
                        .isNotInstanceOf(WebServerApplicationContext.class);
                // Spring Security's autoconfiguration leaves three inert beans in a context
                // without a web server unless the entry point excludes it (probe P1, design.md
                // decision 4): an inert bean today is a surface someone wires up tomorrow.
                softly.assertThat(context.getBeanNamesForType(SecurityFilterChain.class, true,
                                false))
                        .as("the worker has no security filter chain").isEmpty();
                softly.assertThat(context.getBeanNamesForType(Filter.class, true, false))
                        .as("the worker has no request filter").isEmpty();
                softly.assertThat(beanNamesOfTypesIn(context, "org.springframework.security",
                                "org.springframework.boot.security"))
                        .as("the worker has no bean of a security package").isEmpty();
            }
            softly.assertAll();
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * The rate limiter and the adapter of its capacity signal belong to the administrative process
     * alone (web-edge-foundations design.md, decision 17): it is the only one with a login to
     * protect. The other two start without a bean of either package or of the limiter port, and the
     * administrative one holds the interceptor, the registry, the properties, one limiter and the
     * adapter.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.confia.bootstrap.ProcessBeanPolicy#all")
    void onlyTheAdministrativeProcessHoldsTheRateLimiterAndItsMetrics(ProcessBeanPolicy policy) {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess(policy.process()), policy.process());
        ConfigurableApplicationContext context = outcome.context();
        try {
            assertThat(context).as("the %s process must start", policy.process()).isNotNull();
            List<String> held = beanNamesOfTypesIn(context, "com.confia.shared.web.ratelimit",
                    "com.confia.shared.observability");
            if ("admin".equals(policy.process())) {
                assertThat(held).anyMatch(bean -> bean.endsWith(RateLimitInterceptor.class.getName()))
                        .anyMatch(bean -> bean.endsWith(RateLimiterRegistry.class.getName()))
                        .anyMatch(bean -> bean.endsWith(RateLimitProperties.class.getName()))
                        .anyMatch(bean -> bean.endsWith(LogRateLimitMetrics.class.getName()));
                assertThat(context.getBeansOfType(RateLimitMetrics.class)).hasSize(1);
                assertThat(context.getBeansOfType(RateLimiter.class)).as("one limiter per policy")
                        .containsOnlyKeys("admin-login");
            } else {
                assertThat(held).as("no rate limiter and no metrics adapter in the %s process",
                        policy.process()).isEmpty();
                assertThat(context.getBeansOfType(RateLimiter.class))
                        .as("no limiter in the %s process", policy.process()).isEmpty();
            }
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * The delay materializer belongs to the administrative process alone (web-edge-foundations
     * design.md, decision 18): it is the only one with a login whose answer must be delayed. The
     * other two start without a bean of its package; the administrative one holds the
     * materializer, with the permits its properties say, and its properties.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.confia.bootstrap.ProcessBeanPolicy#all")
    void onlyTheAdministrativeProcessHoldsTheDelayMaterializer(ProcessBeanPolicy policy) {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess(policy.process()), policy.process());
        ConfigurableApplicationContext context = outcome.context();
        try {
            assertThat(context).as("the %s process must start", policy.process()).isNotNull();
            List<String> held = beanNamesOfTypesIn(context, "com.confia.shared.web.delay");
            if ("admin".equals(policy.process())) {
                assertThat(held).anyMatch(bean -> bean.endsWith(
                                RequiredDelayMaterializer.class.getName()))
                        .anyMatch(bean -> bean.endsWith(DelayProperties.class.getName()));
                assertThat(context.getBean(RequiredDelayMaterializer.class).availablePermits())
                        .as("the permits are the 200 of the default").isEqualTo(200);
            } else {
                assertThat(held).as("no delay materializer in the %s process", policy.process())
                        .isEmpty();
            }
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    /**
     * The key ring belongs to the administrative process alone (session-tokens-and-web-layer
     * design.md, decision 3; ADR-0005, check 14; specs/build-integrity, BI21 and BI22): the other
     * two start without a bean of the token package, and the administrative one holds the
     * configuration and one ring that signs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("com.confia.bootstrap.ProcessBeanPolicy#all")
    void onlyTheAdministrativeProcessHoldsTheSigningKeyRing(ProcessBeanPolicy policy) {
        LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.forProcess(policy.process()), policy.process());
        ConfigurableApplicationContext context = outcome.context();
        try {
            assertThat(context).as("the %s process must start", policy.process()).isNotNull();
            List<String> held = beanNamesOfTypesIn(context, "com.confia.shared.security.token");
            if ("admin".equals(policy.process())) {
                assertThat(held).anyMatch(bean -> bean.endsWith(SessionTokenConfiguration.class
                                .getName()))
                        .anyMatch(bean -> bean.endsWith(SigningKeyRing.class.getName()));
                assertThat(context.getBeansOfType(SigningKeyRing.class)).hasSize(1);
                assertThat(context.getBean(SigningKeyRing.class).signingKey().hasPrivateKey())
                        .isTrue();
            } else {
                assertThat(held).as("no signing key bean in the %s process", policy.process())
                        .isEmpty();
            }
        } finally {
            if (context != null) {
                context.close();
            }
        }
    }

    private static List<String> beanNamesOfTypesIn(ConfigurableApplicationContext context,
            String... packages) {
        ConfigurableListableBeanFactory factory = context.getBeanFactory();
        List<String> names = new ArrayList<>();
        factory.getBeanNamesIterator().forEachRemaining(name -> {
            Class<?> type = factory.getType(name, false);
            if (type != null && Arrays.stream(packages).anyMatch(
                    candidate -> ProcessBeanPolicy.matches(type.getPackageName(), candidate))) {
                names.add(name + " : " + type.getName());
            }
        });
        return names;
    }

    private static Set<String> importedPackages(ProcessBeanPolicy policy) {
        Set<String> imported = new TreeSet<>(policy.allowedPackages());
        imported.remove(policy.entryPackage());
        return imported;
    }

    /**
     * The db-scheduler prohibition for the admin and portal processes passes vacuously while the
     * library is not on the classpath (ADR-0018). This test says so out loud and fails the day
     * change 9 (background-jobs-with-db-scheduler) adds it: that change replaces it with the
     * positive worker assertion. The prohibition itself needs no rewrite, because the inspector
     * already evaluates it on every bean.
     */
    @Test
    void dbSchedulerAbsenceIsVacuousUntilChange9() {
        assertThat(isOnClasspath("com.github.kagkarlsson.scheduler.Scheduler"))
                .as("db-scheduler is now on the classpath: the forbidden com.github.kagkarlsson "
                        + "package is no longer vacuous in admin and portal (ADR-0018). Replace "
                        + "this test with the positive worker assertion (change 9)")
                .isFalse();
    }

    private static boolean isOnClasspath(String className) {
        try {
            Class.forName(className, false, ProcessBeanIsolationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}

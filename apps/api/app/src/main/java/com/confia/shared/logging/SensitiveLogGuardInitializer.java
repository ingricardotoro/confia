package com.confia.shared.logging;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/**
 * Installs the {@link SensitiveLogGuard} before the application context creates its first bean
 * (background-jobs-with-db-scheduler design.md decision 13; condition 4 of the foundations plan).
 * An {@code ApplicationContextInitializer} runs when the context is prepared, which is after Spring
 * Boot's {@code LoggingApplicationListener} has initialized Logback (that step resets the turbo
 * filters, so installing earlier would be undone) and before the context is refreshed, so no bean
 * exists yet. A guard installed by a bean, as it was before, would exist only after other beans, and
 * the first {@code DataSource} of the worker process could already be logging bound values.
 *
 * <p>The guard is also registered as a singleton of the context, so a test can obtain it, and it is
 * withdrawn when that context closes. The three process entry points add this initializer in {@code
 * ConfiaApplication}; the web test harness adds the same one, so the harness exercises the real
 * installation path.
 */
public final class SensitiveLogGuardInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** The name of the guard in the context, for the tests that take it away and put it back. */
    static final String BEAN_NAME = "sensitiveLogGuard";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        SensitiveLogGuard guard = new SensitiveLogGuard();
        guard.install();
        context.getBeanFactory().registerSingleton(BEAN_NAME, guard);
        context.addApplicationListener(new Withdrawal(context, guard));
    }

    /** Withdraws the guard when the context that installed it closes. */
    private record Withdrawal(ConfigurableApplicationContext context, SensitiveLogGuard guard)
            implements ApplicationListener<ContextClosedEvent> {

        @Override
        public void onApplicationEvent(ContextClosedEvent event) {
            if (event.getApplicationContext() == context) {
                guard.uninstall();
            }
        }
    }
}

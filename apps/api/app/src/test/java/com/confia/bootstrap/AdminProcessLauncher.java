package com.confia.bootstrap;

import java.security.KeyPair;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The real administrative process, started through the production launcher, for the tests outside
 * this package that need it with a database and a signing key of their own
 * (session-tokens-and-web-layer, task 5.2). {@code ConfiaApplication.launch} and the route
 * enumerator are package-private on purpose (nothing in production may reach an entry point), so
 * this class is the one public door of the test tree: it starts the process and lists its routes,
 * and nothing else. Every secret of the process is generated for the run by {@link
 * TestProcessArguments}, except what the caller passes: the signing key pair, so the test can mint
 * tokens the process accepts, and the datasource.
 */
public final class AdminProcessLauncher {

    /** The one origin the process started by {@link #launch} allows. */
    public static final String ALLOWED_ORIGIN = "https://admin.colegio.edu.hn";

    private AdminProcessLauncher() {
    }

    /**
     * Starts the administrative process with the given JDBC settings and signing key pair. The kid
     * is the one of {@link TestProcessArguments#SIGNING_KID}.
     */
    public static ConfigurableApplicationContext launch(String jdbcUrl, String username,
            String password, KeyPair signingKeys) {
        return launch(jdbcUrl, username, password, signingKeys, Map.of());
    }

    /**
     * The same, with properties of its own: the login institution, the trusted proxies, a rate-limit
     * policy, the Spring profile. They are applied after the ones above, so a caller can also replace
     * one of them.
     */
    public static ConfigurableApplicationContext launch(String jdbcUrl, String username,
            String password, KeyPair signingKeys, Map<String, String> extra) {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("confia.web.allowed-origins", ALLOWED_ORIGIN);
        overrides.put("spring.datasource.url", jdbcUrl);
        overrides.put("spring.datasource.username", username);
        overrides.put("spring.datasource.password", password);
        overrides.put(TestProcessArguments.CURRENT_PRIVATE_KEY_PROPERTY, Base64.getEncoder()
                .encodeToString(signingKeys.getPrivate().getEncoded()));
        overrides.put(TestProcessArguments.CURRENT_PUBLIC_KEY_PROPERTY, Base64.getEncoder()
                .encodeToString(signingKeys.getPublic().getEncoded()));
        overrides.putAll(extra);
        ConfiaApplication.LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.adminWith(overrides), "admin");
        if (outcome.context() == null) {
            throw new IllegalStateException("the administrative process did not start");
        }
        return outcome.context();
    }

    /**
     * Starts the administrative process with a JDBC URL nothing listens on, which is enough
     * because the pool connects lazily, for the tests that inspect the real security chain
     * without a database. Every secret, including the CSRF key, is generated for the run.
     */
    public static ConfigurableApplicationContext launchWithoutDatabase() {
        ConfiaApplication.LaunchOutcome outcome = ConfiaApplication.launch(
                TestProcessArguments.adminWith(Map.of()), "admin");
        if (outcome.context() == null) {
            throw new IllegalStateException("the administrative process did not start");
        }
        return outcome.context();
    }

    /** The kid the process verifies tokens under. */
    public static String signingKid() {
        return TestProcessArguments.SIGNING_KID;
    }

    /** Every route the context serves, as {@code METHOD pattern}, sorted. */
    public static List<String> routesOf(ApplicationContext context) {
        return RegisteredRoutes.of(context).stream().map(RegisteredRoutes.Route::toString)
                .toList();
    }
}

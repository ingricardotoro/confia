package com.confia.shared.security.token;

import java.util.List;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * The startup check of ADR-0005, check 14, and DA-17 (session-tokens-and-web-layer design.md,
 * decision 3 and D-N2): a process refuses to start when the private key of the other domain is in
 * its environment.
 *
 * <ul>
 *   <li>the portal and the worker refuse {@code confia.security.admin-signing.current.private-key}
 *       and {@code ...previous.private-key};
 *   <li>the administrative process refuses {@code confia.security.portal-signing.current.private-key}
 *       and {@code ...previous.private-key}, a name reserved for the day the portal has its own key
 *       (nothing is ever loaded from it).
 * </ul>
 *
 * <p>It is an {@link ApplicationListener} that {@code ConfiaApplication.launch} adds to the builder of
 * each process, and not a bean: it runs when the environment is prepared, before the context is
 * created, and it adds no line to {@code ProcessBeanPolicy}. It asks {@link
 * Environment#containsProperty(String)}, which sees operating-system variables, system properties
 * and arguments, with Spring Boot's relaxed names. The message names the property and never
 * reads its value.
 */
public final class SigningKeyPlacementGuard
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String ADMIN_PREFIX = "confia.security.admin-signing.";
    private static final String PORTAL_PREFIX = "confia.security.portal-signing.";
    private static final String CURRENT_PRIVATE = "current.private-key";
    private static final String PREVIOUS_PRIVATE = "previous.private-key";

    private final List<String> refusedProperties;
    private final String reason;

    private SigningKeyPlacementGuard(List<String> refusedProperties, String reason) {
        this.refusedProperties = refusedProperties;
        this.reason = reason;
    }

    /** The guard of the administrative process: the portal's reserved private key names. */
    public static SigningKeyPlacementGuard forAdministrativeProcess() {
        return new SigningKeyPlacementGuard(
                List.of(PORTAL_PREFIX + CURRENT_PRIVATE, PORTAL_PREFIX + PREVIOUS_PRIVATE),
                "is reserved for the private key of the portal and must not be set in the "
                        + "administrative process (ADR-0005, check 14)");
    }

    /** The guard of the portal and of the worker: the administrative private key names. */
    public static SigningKeyPlacementGuard forNonAdministrativeProcess() {
        return new SigningKeyPlacementGuard(
                List.of(ADMIN_PREFIX + CURRENT_PRIVATE, ADMIN_PREFIX + PREVIOUS_PRIVATE),
                "holds an administrative signing private key and must not be set in the portal "
                        + "or the worker process (ADR-0005, check 14)");
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment environment = event.getEnvironment();
        for (String property : refusedProperties) {
            if (environment.containsProperty(property)) {
                throw new IllegalStateException("the property " + property + " " + reason);
            }
        }
    }
}

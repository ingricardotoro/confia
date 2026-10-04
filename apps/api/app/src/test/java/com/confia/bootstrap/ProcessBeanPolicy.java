package com.confia.bootstrap;

import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What one process may and may not contain, as reviewed code (ADR-0003, ADR-0024). Registering a
 * module in an entry point means editing this file in the same pull request: the allow-list fails
 * closed, so a {@code com.confia} bean whose package is not listed breaks the build.
 *
 * <p>A package matches an entry when it equals it or sits below it ({@code entry + "."}), never by
 * bare textual prefix: {@code com.confia.bootstrap} is not covered by {@code
 * com.confia.bootstrap.portal}.
 *
 * @param process the {@code APP_PROFILE} value that starts this process
 * @param entryPackage the package of the process entry point
 * @param allowedPackages every {@code com.confia} package this process may register beans from
 * @param forbiddenPackages named packages that must never appear, with the reason; evaluated in
 *     addition to the allow-list and on any bean, whether or not it is a {@code com.confia} one
 */
record ProcessBeanPolicy(String process, String entryPackage, Set<String> allowedPackages,
        Map<String, String> forbiddenPackages) {

    private static final String SCHEDULER_PACKAGE = "com.github.kagkarlsson";
    private static final String SCHEDULER_REASON = "only the worker runs db-scheduler (ADR-0016)";
    private static final String OTHER_ENTRY_POINT = "another process entry point (ADR-0003)";
    private static final String ADMIN_MODULE = "administrative module (ADR-0003)";
    private static final String OPENAPI_SURFACE =
            "the worker never serves the OpenAPI surface (ADR-0003)";
    private static final String ADMIN_WIRING =
            "the production data access wiring is administrative only (web-edge-foundations D5)";

    static final ProcessBeanPolicy ADMIN = new ProcessBeanPolicy("admin",
            "com.confia.bootstrap.admin",
            Set.of("com.confia.bootstrap.admin", "com.confia.shared.web.openapi",
                    "com.confia.kernel", "com.confia.shared.platform.infrastructure",
                    "com.confia.shared.security", "com.confia.shared.crypto",
                    "com.confia.shared.infrastructure"),
            Map.of("com.confia.bootstrap.portal", OTHER_ENTRY_POINT,
                    "com.confia.bootstrap.worker", OTHER_ENTRY_POINT,
                    SCHEDULER_PACKAGE, SCHEDULER_REASON));

    static final ProcessBeanPolicy PORTAL = new ProcessBeanPolicy("portal",
            "com.confia.bootstrap.portal",
            Set.of("com.confia.bootstrap.portal", "com.confia.shared.web.openapi"),
            Map.of("com.confia.bootstrap.admin", OTHER_ENTRY_POINT,
                    "com.confia.bootstrap.worker", OTHER_ENTRY_POINT,
                    "com.confia.invoicing", ADMIN_MODULE,
                    "com.confia.cashbox", ADMIN_MODULE,
                    "com.confia.reconciliation", ADMIN_MODULE,
                    "com.confia.shared.platform", ADMIN_WIRING,
                    // Holds while identity serves staff only. The change that introduces the
                    // guardian subdomain (ADR-0003) narrows this to the staff sub-package, with
                    // the edit visible in its pull request.
                    "com.confia.identity", "staff-only module while identity has no guardian "
                            + "subdomain (ADR-0003)",
                    SCHEDULER_PACKAGE, SCHEDULER_REASON));

    static final ProcessBeanPolicy WORKER = new ProcessBeanPolicy("worker",
            "com.confia.bootstrap.worker",
            Set.of("com.confia.bootstrap.worker"),
            Map.of("com.confia.bootstrap.admin", OTHER_ENTRY_POINT,
                    "com.confia.bootstrap.portal", OTHER_ENTRY_POINT,
                    "com.confia.shared.web.openapi", OPENAPI_SURFACE,
                    "com.confia.shared.platform", ADMIN_WIRING));

    static Stream<ProcessBeanPolicy> all() {
        return Stream.of(ADMIN, PORTAL, WORKER);
    }

    /** Whether {@code packageName} equals {@code candidate} or sits below it. */
    static boolean matches(String packageName, String candidate) {
        return packageName.equals(candidate) || packageName.startsWith(candidate + ".");
    }

    @Override
    public String toString() {
        return process;
    }
}

package com.confia.notifications.application.api;

import java.util.Set;

/**
 * The closed list of messages the system can send (scenario NT02): a stable key and version, which
 * every delivery attempt records, and the exact names of the parameters the template substitutes.
 * There is no way to send a subject or a body that is not one of these templates; their text lives
 * in the {@code es-HN} catalog (design.md decision 6).
 *
 * <p>Adding a template is a change of the contract: a new constant, its two catalog entries
 * ({@code TemplateCatalogCoverageTest}) and its callers. Changing the text of a published template
 * means a new version, never an edit of the old one.
 */
public enum TransactionalEmailTemplate {

    /** The link that lets the holder of an account choose a new password. */
    PASSWORD_RESET_LINK("password-reset-link", 1, Set.of("link", "validityMinutes"));

    private final String key;
    private final int version;
    private final Set<String> parameterNames;

    TransactionalEmailTemplate(String key, int version, Set<String> parameterNames) {
        this.key = key;
        this.version = version;
        this.parameterNames = Set.copyOf(parameterNames);
    }

    /** The stable key, lower-case kebab-case, as stored in the delivery log. */
    public String key() {
        return key;
    }

    /** The version of the text, a positive number that grows when the published text changes. */
    public int version() {
        return version;
    }

    /** The exact, immutable set of parameter names a message of this template must carry. */
    public Set<String> parameterNames() {
        return parameterNames;
    }
}

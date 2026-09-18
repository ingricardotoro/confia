package com.confia.bootstrap;

/**
 * The four process kinds a single {@code confia-api} artifact can start as, selected by the
 * {@code APP_PROFILE} environment variable (ADR-0003, ADR-0013, design.md decision 4).
 *
 * <p>{@code MIGRATE} is recognized here with no behavior yet: applying Flyway migrations arrives
 * with change 5. An absent, blank or unrecognized value fails closed via {@link
 * UnknownAppProfileException}.
 */
enum AppProfile {

    ADMIN,
    PORTAL,
    WORKER,
    MIGRATE;

    static final String ENV_VARIABLE = "APP_PROFILE";

    /**
     * Resolves the raw {@code APP_PROFILE} value to one of the four recognized profiles.
     *
     * @throws UnknownAppProfileException if {@code rawValue} is {@code null}, blank, or not
     *     exactly one of {@code admin}, {@code portal}, {@code worker} or {@code migrate}
     */
    static AppProfile resolve(String rawValue) {
        String trimmed = rawValue == null ? null : rawValue.trim();
        return switch (trimmed) {
            case "admin" -> ADMIN;
            case "portal" -> PORTAL;
            case "worker" -> WORKER;
            case "migrate" -> MIGRATE;
            case null, default -> throw new UnknownAppProfileException(rawValue);
        };
    }
}

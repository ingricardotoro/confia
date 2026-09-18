package com.confia.bootstrap;

/**
 * Thrown when {@code APP_PROFILE} is absent, blank, or not one of {@code admin}, {@code portal},
 * {@code worker} or {@code migrate}. Never caught silently: {@link ConfiaApplication} translates
 * it into a non-zero process exit code.
 */
final class UnknownAppProfileException extends RuntimeException {

    UnknownAppProfileException(String rawValue) {
        super("Unknown or missing " + AppProfile.ENV_VARIABLE + ": '" + rawValue
                + "'. Expected one of: admin, portal, worker, migrate.");
    }
}

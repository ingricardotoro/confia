package com.confia.shared.web.authorization;

/**
 * The permissions of an actor could not be read (rbac-permission-matrix-and-audit-integration design.md
 * D7). It is neither a denial nor a grant: it extends neither {@code AccessDeniedException} nor {@code
 * AuthenticationException}, so the chain cannot turn it into a {@code 403}, and it reaches the last
 * resort of the request filter, which answers {@code 500 internal-error}.
 *
 * <p>It has a fixed message and no cause on purpose. The message of a driver repeats the offending
 * values, and the last resort logs the exception it catches, so a wrapped cause would put them in the
 * log.
 */
public final class PermissionResolutionFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PermissionResolutionFailedException() {
        super("The permissions of the actor could not be resolved");
    }
}

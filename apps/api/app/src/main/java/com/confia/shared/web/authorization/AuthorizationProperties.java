package com.confia.shared.web.authorization;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The cap and the window of the audit of permission denials
 * (rbac-permission-matrix-and-audit-integration design.md D10), bound through the constructor so a bad
 * value stops the start. None of them is a secret.
 *
 * @param maxFullRows {@code confia.authorization.denials.max-full-rows}: full audit rows per account,
 *     permission and window; the rest are counted into a summary
 * @param window {@code confia.authorization.denials.window}: how long a window lasts
 * @param maxTrackedKeys {@code confia.authorization.denials.max-tracked-keys}: the bound of windows
 *     held in memory
 */
@ConfigurationProperties(prefix = "confia.authorization.denials")
public record AuthorizationProperties(@DefaultValue("10") int maxFullRows,
        @DefaultValue("5m") Duration window, @DefaultValue("10000") int maxTrackedKeys) {

    public AuthorizationProperties {
        if (maxFullRows < 1) {
            throw new IllegalArgumentException(
                    "confia.authorization.denials.max-full-rows must be at least 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "confia.authorization.denials.window must be positive");
        }
        if (maxTrackedKeys < 1) {
            throw new IllegalArgumentException(
                    "confia.authorization.denials.max-tracked-keys must be at least 1");
        }
    }
}

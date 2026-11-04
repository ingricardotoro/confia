package com.confia.architecture.fixture.authorization.methodsecurity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * A permanent, deliberately invalid fixture: method security enabled, one operation guarded by
 * {@code @PreAuthorize} and another by {@code @RequirePermission}. Both run after the interceptors and
 * inside the business transaction, which is where a decision must never be taken.
 */
@EnableMethodSecurity
public final class BadMethodLevelPermissions {

    @PreAuthorize("hasAuthority('identity:read')")
    public void expression() {
        // Deliberately empty: only the annotation matters to the rule.
    }

    @RequirePermission("identity:read")
    public void declared() {
        // Deliberately empty: only the annotation matters to the rule.
    }
}

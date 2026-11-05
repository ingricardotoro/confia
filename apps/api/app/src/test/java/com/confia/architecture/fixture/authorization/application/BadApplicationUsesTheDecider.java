package com.confia.architecture.fixture.authorization.application;

import com.confia.shared.web.authorization.PermissionDecider;
import com.confia.shared.web.authorization.PermissionDeniedException;

/**
 * A permanent, deliberately invalid fixture: a class of an {@code application} package that depends
 * on the permission decider of the web edge and on its exception. A use case never decides a
 * permission; the chain did it before the controller ran.
 */
public final class BadApplicationUsesTheDecider {

    private final PermissionDecider decider;

    public BadApplicationUsesTheDecider(PermissionDecider decider) {
        this.decider = decider;
    }

    public PermissionDecider decider() {
        return decider;
    }

    public boolean denied(RuntimeException failure) {
        return failure instanceof PermissionDeniedException;
    }
}

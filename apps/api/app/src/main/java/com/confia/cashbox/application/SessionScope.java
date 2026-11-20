package com.confia.cashbox.application;

import com.confia.shared.security.AuthenticatedActor;

/**
 * Object-level authorization of the reads (docs/03-seguridad.md section 5.4): a cashier reads only the sessions it opened, while
 * the roles that supervise the cash (accounting, administrator, auditor, super administrator) read all of them. Having the route
 * permission {@code cashbox:read} is decided before a controller runs; this port decides how far that read reaches.
 *
 * <p>The matrix of permissions is not visible to a use case (only the permission decider of the administrative chain may ask the
 * resolver), so the answer comes from the module that owns the roles through this port.
 */
public interface SessionScope {

    /** Whether the actor reads the sessions of every cashier; {@code false} limits it to its own. */
    boolean readsEverySession(AuthenticatedActor actor);
}

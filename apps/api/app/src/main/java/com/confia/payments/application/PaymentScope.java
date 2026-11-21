package com.confia.payments.application;

import com.confia.shared.security.AuthenticatedActor;

/**
 * Object-level authorization of the reads (docs/03-seguridad.md section 5.4): a cashier reads only the payments it registered,
 * while the roles that supervise the money (accounting, administrator, auditor, super administrator) read all of them. Having the
 * route permission {@code payments:read} is decided before a controller runs; this port decides how far that read reaches.
 *
 * <p>The matrix of permissions is not visible to a use case (only the permission decider of the administrative chain may ask the
 * resolver), so the answer comes from the module that owns the roles through this port.
 */
public interface PaymentScope {

    /** Whether the actor reads the payments of every cashier; {@code false} limits it to its own. */
    boolean readsEveryPayment(AuthenticatedActor actor);
}

package com.confia.cashbox.infrastructure;

import com.confia.cashbox.application.SessionScope;
import com.confia.shared.security.AuthenticatedActor;

/**
 * The scope of the reads while no role directory is published by {@code identity}: every actor that holds {@code cashbox:read}
 * reads every session. The cashier restriction of docs/03-seguridad.md section 5.4 ("a cashier reads only its own session")
 * is enforced by the use cases as soon as this port answers {@code false}; what is missing is the answer, which needs the role
 * of the account and is not visible to a use case (only the permission decider reads the resolver). Replace this adapter, one
 * bean in {@code CashboxConfiguration}, when {@code identity} publishes it. Pending decision of the architect, recorded in
 * docs/cortes/cashbox-sessions.md.
 */
public final class FullReadSessionScope implements SessionScope {

    @Override
    public boolean readsEverySession(AuthenticatedActor actor) {
        return true;
    }
}

package com.confia.payments.infrastructure;

import com.confia.identity.application.directory.OwnRecordPolicy;
import com.confia.kernel.Permission;
import com.confia.payments.application.PaymentScope;
import com.confia.shared.security.AuthenticatedActor;
import java.util.Objects;

/**
 * The scope of the reads, answered by the role directory that {@code identity} publishes ({@link OwnRecordPolicy}): an actor whose
 * role limits {@code payments:read} to its own data (the cashier, docs/03-seguridad.md section 5.4) reads only the payments it
 * registered; every other role reads all of them. It runs inside the transaction of the use case and fails closed with the
 * directory: an unknown role is restricted.
 */
public final class RoleDirectoryPaymentScope implements PaymentScope {

    private final OwnRecordPolicy scopes;

    public RoleDirectoryPaymentScope(OwnRecordPolicy scopes) {
        this.scopes = Objects.requireNonNull(scopes, "scopes");
    }

    @Override
    public boolean readsEveryPayment(AuthenticatedActor actor) {
        return !scopes.restrictedToOwn(actor, Permission.PAYMENTS_READ);
    }
}

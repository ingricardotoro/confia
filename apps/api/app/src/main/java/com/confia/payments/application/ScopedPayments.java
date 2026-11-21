package com.confia.payments.application;

import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.Payment;
import com.confia.shared.security.AuthenticatedActor;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The one way a use case reads a payment by identifier on behalf of an actor: the {@link PaymentScope} decides how far the actor
 * reads (a cashier only the payments it registered), and a payment out of that reach is as absent as one of another institution.
 * The use cases that decide a payment read it here, so no use case of the module reads {@link PaymentRepository#findById} without
 * the scope (docs/03-seguridad.md section 6.3; an ArchUnit rule checks it).
 */
final class ScopedPayments {

    private final PaymentRepository payments;
    private final PaymentScope scope;

    ScopedPayments(PaymentRepository payments, PaymentScope scope) {
        this.payments = Objects.requireNonNull(payments, "payments");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    Optional<Payment> find(AuthenticatedActor actor, InstitutionId institution, UUID paymentId) {
        boolean everyPayment = scope.readsEveryPayment(actor);
        return payments.findById(institution, paymentId)
                .filter(payment -> everyPayment || payment.registeredBy().equals(actor.accountId()));
    }
}

package com.confia.architecture.fixture.authorization.application;

import com.confia.kernel.InstitutionId;
import com.confia.payments.application.PaymentRepository;
import com.confia.payments.domain.Payment;
import java.util.Optional;
import java.util.UUID;

/**
 * A permanent, deliberately invalid fixture: a read use case of the payments that never asks the {@code PaymentScope} how far the
 * caller may read, so a cashier would read the payments of everyone.
 */
public final class GetEveryPaymentWithoutTheScope {

    private final PaymentRepository payments;

    public GetEveryPaymentWithoutTheScope(PaymentRepository payments) {
        this.payments = payments;
    }

    public Optional<Payment> get(InstitutionId institution, UUID id) {
        return payments.findById(institution, id);
    }
}

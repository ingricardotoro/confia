package com.confia.architecture.fixture.authorization.application;

import com.confia.kernel.InstitutionId;
import com.confia.payments.application.PaymentRepository;
import com.confia.payments.domain.Payment;
import java.util.Optional;
import java.util.UUID;

/**
 * A permanent, deliberately invalid fixture: a class of the payments whose name does not start with Get or List, and that reads a payment by id without asking the {@code PaymentScope} how far the
 * caller may read, so a cashier would read the payments of everyone.
 */
public final class ApprovePaymentWithoutTheScope {

    private final PaymentRepository payments;

    public ApprovePaymentWithoutTheScope(PaymentRepository payments) {
        this.payments = payments;
    }

    public Optional<Payment> approve(InstitutionId institution, UUID id) {
        return payments.findById(institution, id);
    }
}

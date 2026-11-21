package com.confia.architecture.fixture.authorization.web;

import com.confia.kernel.InstitutionId;
import com.confia.payments.application.PaymentRepository;
import com.confia.payments.domain.Payment;
import java.util.Optional;
import java.util.UUID;

/**
 * A permanent, deliberately invalid fixture: a class of a {@code web} package that reads the payments straight from the
 * repository, with no use case and so with no {@code PaymentScope}. A cashier would read the payment of another.
 */
public final class BadWebReadsThePaymentRepository {

    private final PaymentRepository payments;

    public BadWebReadsThePaymentRepository(PaymentRepository payments) {
        this.payments = payments;
    }

    public Optional<Payment> anyPayment(InstitutionId institution, UUID id) {
        return payments.findById(institution, id);
    }
}

package com.confia.payments.application;

import com.confia.charges.application.api.PayableCharges;
import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.ResourceNotFoundException;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.util.Objects;
import java.util.UUID;

/**
 * One payment with its decision and its allocations (charge, concept, period and applied amount). A payment of another
 * institution, an unknown one and (for an actor that reads only its own) another cashier's payment look the same: 404.
 */
public final class GetPayment {

    private final TransactionRunner transactionRunner;
    private final PaymentRepository payments;
    private final PaymentDetails details;
    private final PaymentScope scope;

    public GetPayment(TransactionRunner transactionRunner, PaymentRepository payments,
            PaymentDecisionRepository decisions, PaymentAllocationRepository allocations, StudentDirectory students,
            PayableCharges charges, PaymentScope scope) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.details = new PaymentDetails(decisions, allocations, charges, students);
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public PaymentDetail get(AuthenticatedActor actor, String requestId, UUID paymentId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(paymentId, "paymentId");
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(PaymentRequests.requestId(requestId).toString()),
                () -> {
                    boolean everyPayment = scope.readsEveryPayment(actor);
                    return payments.findById(institution, paymentId)
                            .filter(payment -> everyPayment || payment.registeredBy().equals(actor.accountId()))
                            .map(payment -> details.of(institution, payment))
                            .orElseThrow(ResourceNotFoundException::new);
                });
    }
}

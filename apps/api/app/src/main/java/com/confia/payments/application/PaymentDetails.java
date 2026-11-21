package com.confia.payments.application;

import com.confia.charges.application.api.PayableCharges;
import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentAllocation;
import com.confia.payments.domain.PaymentCoverage;
import com.confia.payments.domain.PaymentDecision;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds the {@link PaymentDetail} of a payment from what is stored: its decision, its allocations with what identifies each
 * charge, the code of its student and what stayed as a credit. It reads only, inside the transaction of the use case that asks.
 */
final class PaymentDetails {

    private final PaymentDecisionRepository decisions;
    private final PaymentAllocationRepository allocations;
    private final PayableCharges charges;
    private final StudentDirectory students;

    PaymentDetails(PaymentDecisionRepository decisions, PaymentAllocationRepository allocations, PayableCharges charges,
            StudentDirectory students) {
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.allocations = Objects.requireNonNull(allocations, "allocations");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.students = Objects.requireNonNull(students, "students");
    }

    PaymentDetail of(InstitutionId institution, Payment payment) {
        PaymentDecision decision = decisions.findByPayment(institution, payment.id()).orElse(null);
        List<PaymentAllocation> made = allocations.findByPayment(institution, payment.id());
        Map<UUID, PayableCharges.ChargeLabel> labels = charges.chargeLabels(institution,
                made.stream().map(PaymentAllocation::chargeId).toList());
        List<PaymentDetail.AllocationView> views = made.stream().map(allocation -> {
            PayableCharges.ChargeLabel label = labels.get(allocation.chargeId());
            return new PaymentDetail.AllocationView(allocation.chargeId(), label == null ? null : label.conceptCode(),
                    label == null ? null : label.conceptName(), label == null ? null : label.periodLabel(),
                    label == null ? null : label.dueOn(), allocation.amount(), allocation.allocatedAt());
        }).toList();
        PaymentCoverage coverage = PaymentCoverage.of(payment, made);
        UUID posting = payment.ledgerTransactionId() != null ? payment.ledgerTransactionId()
                : Optional.ofNullable(decision).map(PaymentDecision::ledgerTransactionId).orElse(null);
        return new PaymentDetail(payment.id(), payment.studentId(), studentCode(institution, payment.studentId()),
                payment.guardianId(), payment.method().name(), payment.status().code(), payment.amount(),
                payment.receivedOn(), payment.transfer() == null ? null : payment.transfer().reference(),
                payment.transfer() == null ? null : payment.transfer().bankName(), payment.notes(),
                payment.cashSessionId(), posting, payment.registeredBy(), payment.registeredAt(),
                PaymentDetail.Decision.of(decision), views, coverage.allocated(), coverage.unallocated());
    }

    private String studentCode(InstitutionId institution, UUID studentId) {
        return students.find(institution, studentId).map(StudentRef::studentCode).orElse(null);
    }
}

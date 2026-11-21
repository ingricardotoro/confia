package com.confia.payments.application;

import com.confia.kernel.InstitutionId;
import com.confia.payments.domain.InvalidPaymentsValueException;
import com.confia.payments.domain.Payment;
import com.confia.payments.domain.PaymentMethod;
import com.confia.payments.domain.PaymentStatus;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A page of payments, filtered by student, status, method and the days on which the money was received. An actor that reads only
 * its own payments (the cashier) is always restricted to the ones it registered, whatever it asked for: another cashier's payment
 * is never listed. The page size is limited by the server and the list is paginated by the database.
 */
public final class ListPayments {

    private final TransactionRunner transactionRunner;
    private final PaymentRepository payments;
    private final StudentDirectory students;
    private final PaymentScope scope;

    public ListPayments(TransactionRunner transactionRunner, PaymentRepository payments, StudentDirectory students,
            PaymentScope scope) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.students = Objects.requireNonNull(students, "students");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    public Page<PaymentSummary> list(AuthenticatedActor actor, String requestId, PaymentFilter filter, PageRequest page) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw new InvalidPaymentsValueException();
        }
        if (filter.status() != null && PaymentStatus.ofCode(filter.status()).isEmpty()) {
            throw new InvalidPaymentsValueException();
        }
        if (filter.method() != null && PaymentMethod.ofName(filter.method()).isEmpty()) {
            throw new InvalidPaymentsValueException();
        }
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(PaymentRequests.requestId(requestId).toString()),
                () -> {
                    PaymentFilter effective = scope.readsEveryPayment(actor) ? filter
                            : filter.onlyRegisteredBy(actor.accountId());
                    Page<Payment> found = payments.list(institution, effective, page);
                    Set<UUID> studentIds = found.items().stream().map(Payment::studentId).collect(Collectors.toSet());
                    Map<UUID, StudentRef> refs = studentIds.isEmpty() ? new HashMap<>()
                            : students.findAll(institution, studentIds);
                    return found.map(payment -> PaymentSummary.of(payment,
                            refs.containsKey(payment.studentId()) ? refs.get(payment.studentId()).studentCode() : null));
                });
    }
}

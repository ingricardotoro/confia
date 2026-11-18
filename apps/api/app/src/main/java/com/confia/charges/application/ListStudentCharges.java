package com.confia.charges.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The charges of a student, so that one can be picked to void (docs/cortes/charges-void-and-integrity.md). It writes no
 * financial datum. It does write one audit entry in the same transaction, because reading the finances of a student, a minor,
 * is access to personal data. A student of another institution looks the same as one that does not exist.
 */
public final class ListStudentCharges {

    private final TransactionRunner transactionRunner;
    private final StudentDirectory students;
    private final ChargeRepository charges;
    private final PaymentConceptDirectory concepts;
    private final AuditLogWriter auditLogWriter;

    public ListStudentCharges(TransactionRunner transactionRunner, StudentDirectory students, ChargeRepository charges,
            PaymentConceptDirectory concepts, AuditLogWriter auditLogWriter) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
    }

    public Page<StudentChargeView> list(AuthenticatedActor actor, String requestId, UUID studentId, ChargeFilter filter,
            PageRequest page) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(page, "page");
        UUID auditRequestId = ChargesAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        // Read-write on purpose: the audit entry of the access is written in the same transaction.
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            students.find(institution, studentId).orElseThrow(ResourceNotFoundException::new);
            Page<ChargeRow> rows = charges.listByStudent(institution, studentId, filter, page);
            Set<UUID> conceptIds = new HashSet<>();
            rows.items().forEach(row -> conceptIds.add(row.paymentConceptId()));
            Map<UUID, PaymentConceptLabel> labels = concepts.labels(institution, conceptIds);
            Page<StudentChargeView> result = rows.map(row -> {
                PaymentConceptLabel label = labels.get(row.paymentConceptId());
                return new StudentChargeView(row, label == null ? null : label.code(),
                        label == null ? null : label.name());
            });
            auditLogWriter.append(ChargesAudit.studentChargesViewed(actor, auditRequestId, studentId, filter, page));
            return result;
        });
    }
}

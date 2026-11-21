package com.confia.charges.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptLabel;
import com.confia.charges.application.api.ChargePayments;
import com.confia.charges.domain.ChargeBalance;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.kernel.Money;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
    private final ChargePayments payments;
    private final AuditLogWriter auditLogWriter;

    public ListStudentCharges(TransactionRunner transactionRunner, StudentDirectory students, ChargeRepository charges,
            PaymentConceptDirectory concepts, ChargePayments payments, AuditLogWriter auditLogWriter) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.charges = Objects.requireNonNull(charges, "charges");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
    }

    /** The sort of the list of what is outstanding when the client gave none: the order in which a payment is allocated. */
    public static final String ALLOCATION_ORDER = "allocation";

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
            Page<StudentChargeView> result = filter.outstandingOnly()
                    ? outstanding(institution, studentId, filter, page)
                    : view(institution, charges.listByStudent(institution, studentId, filter, page));
            auditLogWriter.append(ChargesAudit.studentChargesViewed(actor, auditRequestId, studentId, filter, page));
            return result;
        });
    }

    private Page<StudentChargeView> view(InstitutionId institution, Page<ChargeRow> rows) {
        return new Page<>(views(institution, rows.items()), rows.total(), rows.page(), rows.size());
    }

    private List<StudentChargeView> views(InstitutionId institution, List<ChargeRow> rows) {
        Set<UUID> conceptIds = new HashSet<>();
        rows.forEach(row -> conceptIds.add(row.paymentConceptId()));
        Map<UUID, PaymentConceptLabel> labels = concepts.labels(institution, conceptIds);
        // What each charge has been paid is derived from the allocations of the payments, never stored.
        Map<UUID, Money> allocated = payments.allocatedByCharge(institution, rows.stream().map(ChargeRow::id).toList());
        return rows.stream().map(row -> {
            PaymentConceptLabel label = labels.get(row.paymentConceptId());
            Money paid = allocated.getOrDefault(row.id(), Money.zero(row.total().currency()));
            ChargeBalance balance = ChargeBalance.of(row.total(), paid, row.voided() != null);
            return new StudentChargeView(row, label == null ? null : label.code(), label == null ? null : label.name(),
                    balance.paid(), balance.outstanding());
        }).toList();
    }

    /**
     * The charges with something still owed. What is outstanding is derived from the allocations of the payments, which this
     * module cannot join in a query, so the charges of the student are read, narrowed and ordered here and the page is cut after.
     * Without a sort of the client the order is the one in which a payment is allocated (due date, period, concept code,
     * identifier), so the screen shows the charges in the order they will be covered.
     */
    private Page<StudentChargeView> outstanding(InstitutionId institution, UUID studentId, ChargeFilter filter,
            PageRequest page) {
        List<StudentChargeView> owed = new ArrayList<>(views(institution, charges.listAllByStudent(institution, studentId, filter))
                .stream().filter(view -> !view.outstanding().isZero()).toList());
        Comparator<StudentChargeView> order = comparator(page.sortField());
        owed.sort(page.ascending() ? order : order.reversed());
        int from = (int) Math.min(page.offset(), owed.size());
        int to = Math.min(from + page.size(), owed.size());
        return new Page<>(List.copyOf(owed.subList(from, to)), owed.size(), page.page(), page.size());
    }

    private static Comparator<StudentChargeView> comparator(String sortField) {
        Comparator<StudentChargeView> byId = Comparator.comparing(view -> view.charge().id());
        return switch (sortField) {
            case "periodStart" -> Comparator.<StudentChargeView, java.time.LocalDate>comparing(view -> view.charge().periodStart())
                    .thenComparing(byId);
            case "dueOn" -> Comparator.<StudentChargeView, java.time.LocalDate>comparing(view -> view.charge().dueOn())
                    .thenComparing(byId);
            case "total" -> Comparator.<StudentChargeView, String>comparing(view -> view.charge().total().currency().name())
                    .thenComparing(view -> view.charge().total(), Money::compareTo).thenComparing(byId);
            default -> Comparator.<StudentChargeView, java.time.LocalDate>comparing(view -> view.charge().dueOn())
                    .thenComparing(view -> view.charge().periodStart())
                    .thenComparing(StudentChargeView::conceptCode, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(byId);
        };
    }
}

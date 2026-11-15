package com.confia.imports.application;

import com.confia.imports.application.ImportBatchRepository.AppliedTotals;
import com.confia.imports.application.ReconciliationView.KindCountView;
import com.confia.imports.domain.ImportKind;
import com.confia.imports.domain.Reconciliation;
import com.confia.imports.domain.Reconciliation.KindCount;
import com.confia.imports.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.EnrollmentCensus;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Signs and lists the reconciliations of the migration (docs/cortes/imports-reconciliation.md). A reconciliation is the
 * record of what the import left in the system for an academic year next to the total the official roster declares: the
 * server computes every count (what each kind created and left rejected, from the batches, and the active enrollments of
 * the year, from {@code students}), and the person who signs only says which year, the reference of the roster and its
 * total. The record is only inserted, never changed: a correction is another one. It holds counts and a short reference,
 * nothing of a person, and so does its audit entry.
 */
public final class ManageReconciliations {

    /** The largest total of an official roster the record accepts (also limited at the web edge). */
    public static final int MAX_OFFICIAL_COUNT = 1_000_000;

    private final TransactionRunner transactionRunner;
    private final ImportBatchRepository batches;
    private final ReconciliationRepository reconciliations;
    private final SectionDirectory sections;
    private final EnrollmentCensus census;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageReconciliations(TransactionRunner transactionRunner, ImportBatchRepository batches,
            ReconciliationRepository reconciliations, SectionDirectory sections, EnrollmentCensus census,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.batches = Objects.requireNonNull(batches, "batches");
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.census = Objects.requireNonNull(census, "census");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The counts a reconciliation signed now would record; nothing is written. */
    public ReconciliationPreview preview(AuthenticatedActor actor, String requestId, UUID academicYearId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(academicYearId, "academicYearId");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Counts counts = countsOf(institution, academicYearId);
            return new ReconciliationPreview(academicYearId, counts.enrollmentsActive(),
                    KindCountView.of(counts.students()), KindCountView.of(counts.guardians()),
                    KindCountView.of(counts.guardianLinks()), KindCountView.of(counts.enrollments()));
        });
    }

    /**
     * Signs the reconciliation of the year with the counts of this moment and writes its audit entry, in one
     * transaction. The reference is trimmed first.
     *
     * @throws ResourceNotFoundException when the year does not exist in the institution
     * @throws RejectedReconciliationException when the reference or the total is not one the record accepts (the edge
     *     validates the same bounds first)
     */
    public ReconciliationView sign(AuthenticatedActor actor, String requestId, UUID academicYearId,
            String officialReference, int officialEnrollmentCount) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(academicYearId, "academicYearId");
        Objects.requireNonNull(officialReference, "officialReference");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        String reference = officialReference.strip();
        if (!Reconciliation.isValidReference(reference)) {
            throw new RejectedReconciliationException("officialReference");
        }
        if (officialEnrollmentCount < 0 || officialEnrollmentCount > MAX_OFFICIAL_COUNT) {
            throw new RejectedReconciliationException("officialEnrollmentCount");
        }
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Counts counts = countsOf(institution, academicYearId);
            Reconciliation signed = new Reconciliation(UUID.randomUUID(), academicYearId, reference,
                    officialEnrollmentCount, counts.enrollmentsActive(), counts.students(), counts.guardians(),
                    counts.guardianLinks(), counts.enrollments(), actor.accountId(),
                    clock.instant().truncatedTo(ChronoUnit.MICROS));
            reconciliations.insert(institution, signed);
            auditLogWriter.append(ImportsAudit.reconciliationSigned(actor, auditRequestId, signed));
            return ReconciliationView.of(signed);
        });
    }

    /** The reconciliations of the institution, newest first. */
    public Page<ReconciliationView> list(AuthenticatedActor actor, String requestId, int page, int size) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ImportsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        PageRequest request = new PageRequest(page, size);
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> reconciliations.list(institution, request).map(ReconciliationView::of));
    }

    private record Counts(int enrollmentsActive, KindCount students, KindCount guardians, KindCount guardianLinks,
            KindCount enrollments) {
    }

    /** Runs inside the transaction of the caller. */
    private Counts countsOf(InstitutionId institution, UUID academicYearId) {
        if (!sections.academicYearExists(institution, academicYearId)) {
            throw new ResourceNotFoundException();
        }
        Map<ImportKind, AppliedTotals> totals = batches.appliedTotals(institution);
        return new Counts(census.activeInYear(institution, academicYearId), countOf(totals, ImportKind.STUDENTS),
                countOf(totals, ImportKind.GUARDIANS), countOf(totals, ImportKind.GUARDIAN_LINKS),
                countOf(totals, ImportKind.ENROLLMENTS));
    }

    private static KindCount countOf(Map<ImportKind, AppliedTotals> totals, ImportKind kind) {
        AppliedTotals found = totals.get(kind);
        return found == null ? new KindCount(0, 0) : new KindCount(found.created(), found.rejected());
    }
}

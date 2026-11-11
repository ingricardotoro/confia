package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.Grade;
import com.confia.organization.domain.Modality;
import com.confia.organization.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits and lists the grades of the institution of the actor. A grade is created in an
 * active modality that exists in the institution; the name and the ordinal are unique within the
 * modality, which the database enforces. Deactivating a grade leaves its sections as they are.
 */
public final class ManageGrades {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("name", "ordinal");

    private final TransactionRunner transactionRunner;
    private final GradeRepository grades;
    private final ModalityRepository modalities;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageGrades(TransactionRunner transactionRunner, GradeRepository grades,
            ModalityRepository modalities, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.grades = Objects.requireNonNull(grades, "grades");
        this.modalities = Objects.requireNonNull(modalities, "modalities");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GradeView create(AuthenticatedActor actor, String requestId, UUID modalityId, String name,
            int ordinal) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Modality modality = modalities.findById(institution, modalityId)
                    .orElseThrow(ResourceNotFoundException::new);
            Grade grade = Grade.create(UUID.randomUUID(), modality, name, ordinal, clock.instant());
            grades.insert(institution, grade);
            auditLogWriter.append(OrganizationAudit.created(actor, auditRequestId,
                    OrganizationAudit.GRADE, grade.id(), OrganizationAudit.json(grade)));
            return GradeView.of(grade);
        });
    }

    public GradeView update(AuthenticatedActor actor, String requestId, UUID id, String name,
            int ordinal, boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Grade before = grades.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            Grade after = before.edit(name, ordinal, active, clock.instant());
            grades.update(institution, after);
            auditLogWriter.append(OrganizationAudit.updated(actor, auditRequestId,
                    OrganizationAudit.GRADE, id, OrganizationAudit.json(before),
                    OrganizationAudit.json(after)));
            return GradeView.of(after);
        });
    }

    /** @param modalityId only the grades of this modality, or {@code null} for every grade */
    public Page<GradeView> list(AuthenticatedActor actor, String requestId, UUID modalityId, int page,
            int size, String sort) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "ordinal", true);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> grades.list(institution, modalityId, request).map(GradeView::of));
    }
}

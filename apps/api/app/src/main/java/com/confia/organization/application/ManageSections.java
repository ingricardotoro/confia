package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.AcademicYear;
import com.confia.organization.domain.Grade;
import com.confia.organization.domain.ResourceNotFoundException;
import com.confia.organization.domain.Section;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits and lists the sections of the institution of the actor. A section is created in a
 * year that is not CLOSED and for an active grade, both of the institution; its name is unique per
 * year and grade, which the database enforces. A section of a closed year is not edited.
 */
public final class ManageSections {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("name", "capacity");

    private final TransactionRunner transactionRunner;
    private final SectionRepository sections;
    private final AcademicYearRepository years;
    private final GradeRepository grades;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageSections(TransactionRunner transactionRunner, SectionRepository sections,
            AcademicYearRepository years, GradeRepository grades, AuditLogWriter auditLogWriter,
            Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.years = Objects.requireNonNull(years, "years");
        this.grades = Objects.requireNonNull(grades, "grades");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @param capacity the capacity, or {@code null} for none; above zero otherwise */
    public SectionView create(AuthenticatedActor actor, String requestId, UUID academicYearId,
            UUID gradeId, String name, Integer capacity) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            AcademicYear year = years.findById(institution, academicYearId)
                    .orElseThrow(ResourceNotFoundException::new);
            Grade grade = grades.findById(institution, gradeId)
                    .orElseThrow(ResourceNotFoundException::new);
            Section section = Section.create(UUID.randomUUID(), year, grade, name, capacity,
                    clock.instant());
            sections.insert(institution, section);
            auditLogWriter.append(OrganizationAudit.created(actor, auditRequestId,
                    OrganizationAudit.SECTION, section.id(), OrganizationAudit.json(section)));
            return SectionView.of(section);
        });
    }

    public SectionView update(AuthenticatedActor actor, String requestId, UUID id, String name,
            Integer capacity, boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Section before = sections.findById(institution, id)
                    .orElseThrow(ResourceNotFoundException::new);
            AcademicYear year = years.findById(institution, before.academicYearId())
                    .orElseThrow(ResourceNotFoundException::new);
            Section after = before.edit(year, name, capacity, active, clock.instant());
            sections.update(institution, after);
            auditLogWriter.append(OrganizationAudit.updated(actor, auditRequestId,
                    OrganizationAudit.SECTION, id, OrganizationAudit.json(before),
                    OrganizationAudit.json(after)));
            return SectionView.of(after);
        });
    }

    /**
     * @param academicYearId only the sections of this year, or {@code null}
     * @param gradeId only the sections of this grade, or {@code null}
     */
    public Page<SectionView> list(AuthenticatedActor actor, String requestId, UUID academicYearId,
            UUID gradeId, int page, int size, String sort) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "name", true);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> sections.list(institution, academicYearId, gradeId, request)
                        .map(SectionView::of));
    }
}

package com.confia.organization.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.AcademicYear;
import com.confia.organization.domain.AcademicYearAlreadyActiveException;
import com.confia.organization.domain.AcademicYearOverlapException;
import com.confia.organization.domain.AcademicYearStatus;
import com.confia.organization.domain.InvalidAcademicStructureException;
import com.confia.organization.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits and lists the academic years of the institution of the actor
 * (docs/cortes/organization-academic-structure.md). The institution is the actor's, never a value the
 * request carries, and every operation runs in one transaction with the actor's security context, so
 * row-level security keeps another institution out whatever the arguments say.
 *
 * <p>A write first locks the institution row, which serializes every writer of the date ranges of the
 * institution, and only then checks the two rules that need other rows: the range shares no day with
 * another year, and at most one year is ACTIVE. The names are unique by the database itself. The write
 * and its audit entry commit or roll back together.
 */
public final class ManageAcademicYears {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("name", "startDate", "endDate", "status");

    private final TransactionRunner transactionRunner;
    private final AcademicYearRepository years;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageAcademicYears(TransactionRunner transactionRunner, AcademicYearRepository years,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.years = Objects.requireNonNull(years, "years");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @param status the initial status by name; {@code null} means PLANNED */
    public AcademicYearView create(AuthenticatedActor actor, String requestId, String name,
            LocalDate startDate, LocalDate endDate, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            years.lockInstitution(institution);
            AcademicYear year = AcademicYear.create(UUID.randomUUID(), name, startDate, endDate,
                    status == null ? AcademicYearStatus.PLANNED : statusOf(status),
                    clock.instant());
            checkRules(institution, year);
            years.insert(institution, year);
            auditLogWriter.append(OrganizationAudit.created(actor, auditRequestId,
                    OrganizationAudit.ACADEMIC_YEAR, year.id(), OrganizationAudit.json(year)));
            return AcademicYearView.of(year);
        });
    }

    public AcademicYearView update(AuthenticatedActor actor, String requestId, UUID id, String name,
            LocalDate startDate, LocalDate endDate, String status, boolean active) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            years.lockInstitution(institution);
            AcademicYear before = years.findById(institution, id)
                    .orElseThrow(ResourceNotFoundException::new);
            AcademicYear after = before.edit(name, startDate, endDate,
                    statusOf(status), active, clock.instant());
            checkRules(institution, after);
            years.update(institution, after);
            auditLogWriter.append(OrganizationAudit.updated(actor, auditRequestId,
                    OrganizationAudit.ACADEMIC_YEAR, id, OrganizationAudit.json(before),
                    OrganizationAudit.json(after)));
            return AcademicYearView.of(after);
        });
    }

    public Page<AcademicYearView> list(AuthenticatedActor actor, String requestId, int page,
            int size, String sort) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = OrganizationAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "startDate", false);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> years.list(institution, request).map(AcademicYearView::of));
    }

    /** The status by name; a name outside the three is a value the edge should have refused. */
    private static AcademicYearStatus statusOf(String status) {
        try {
            return AcademicYearStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidAcademicStructureException();
        }
    }

    /** The two rules that look at the other years; every other year than {@code year} itself counts. */
    private void checkRules(InstitutionId institution, AcademicYear year) {
        boolean overlaps = years.findOverlapping(institution, year.startDate(), year.endDate()).stream()
                .anyMatch(other -> !other.id().equals(year.id()));
        if (overlaps) {
            throw new AcademicYearOverlapException();
        }
        if (year.status() == AcademicYearStatus.ACTIVE) {
            boolean otherActive = years.findActive(institution)
                    .filter(other -> !other.id().equals(year.id())).isPresent();
            if (otherActive) {
                throw new AcademicYearAlreadyActiveException();
            }
        }
    }
}

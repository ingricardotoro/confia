package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionDirectory;
import com.confia.organization.application.directory.SectionRef;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentStatus;
import com.confia.students.domain.EnrollmentTransfer;
import com.confia.students.domain.InvalidEnrollmentException;
import com.confia.students.domain.ResourceNotFoundException;
import com.confia.students.domain.SectionPlace;
import com.confia.students.domain.Student;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enrolls a student in a section of an academic year, moves the enrollment to another section of the same
 * year and ends it as a withdrawal, a transfer to another institution or a graduation
 * (docs/cortes/enrollments.md). Nothing is deleted: the history stays.
 *
 * <p>The section and its academic year, grade and modality are read through the {@link SectionDirectory}
 * that {@code organization} publishes; this module never reads its tables. The year is the one of the
 * section: the client does not send it.
 *
 * <p>Race safety. The capacity and the second active enrollment of the year are decided under locks that
 * last until the end of the transaction. An enrollment locks the row of the section before it counts the
 * seats taken, so two enrollments for the last seat take turns and the second finds the section full; a
 * transfer locks its enrollment and then the target section; an exit locks its enrollment, so two
 * simultaneous exits take turns and the second finds the enrollment no longer active. The lock order is
 * always enrollment, then section, which cannot deadlock. INV-31 has the unique index of the database as its
 * last word.
 */
public final class ManageEnrollments {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("enrolledOn", "studentLastName", "status");

    private final TransactionRunner transactionRunner;
    private final EnrollmentRepository enrollments;
    private final StudentRepository students;
    private final SectionDirectory sections;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageEnrollments(TransactionRunner transactionRunner, EnrollmentRepository enrollments,
            StudentRepository students, SectionDirectory sections, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.enrollments = Objects.requireNonNull(enrollments, "enrollments");
        this.students = Objects.requireNonNull(students, "students");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EnrollmentView enroll(AuthenticatedActor actor, String requestId, UUID studentId, UUID sectionId,
            LocalDate enrolledOn) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return named(() -> transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Student student = students.findById(institution, studentId).orElseThrow(ResourceNotFoundException::new);
            SectionRef section = sections.lock(institution, sectionId).orElseThrow(ResourceNotFoundException::new);
            boolean hasActiveInYear = enrollments.hasActiveInYear(institution, studentId, section.academicYearId());
            int taken = enrollments.countActiveInSection(institution, sectionId);
            Instant now = now();
            Enrollment enrollment = Enrollment.enroll(UUID.randomUUID(), student, placeOf(section),
                    hasActiveInYear, taken, enrolledOn, Enrollment.dayOf(now), now);
            enrollments.insert(institution, enrollment);
            auditLogWriter.append(EnrollmentsAudit.created(actor, auditRequestId, student.studentCode(), enrollment));
            return view(enrollment, student, section, List.of(), Map.of());
        }));
    }

    public EnrollmentView transfer(AuthenticatedActor actor, String requestId, UUID id, UUID toSectionId,
            LocalDate transferredOn, String reason) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return named(() -> transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Enrollment before = enrollments.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
            SectionRef from = sectionOf(institution, before);
            SectionRef to = sections.lock(institution, toSectionId).orElseThrow(ResourceNotFoundException::new);
            int taken = enrollments.countActiveInSection(institution, toSectionId);
            Instant now = now();
            Enrollment.Moved moved = before.transferTo(UUID.randomUUID(), actor.accountId(), placeOf(from),
                    placeOf(to), taken, transferredOn, reason, Enrollment.dayOf(now), now);
            enrollments.update(institution, moved.enrollment());
            enrollments.insertTransfer(institution, moved.transfer());
            Student student = studentOf(institution, before);
            auditLogWriter.append(EnrollmentsAudit.transferred(actor, auditRequestId, student.studentCode(), before,
                    moved.enrollment(), moved.transfer()));
            return viewWithHistory(institution, moved.enrollment(), student, to);
        }));
    }

    public EnrollmentView exit(AuthenticatedActor actor, String requestId, UUID id, String status,
            LocalDate withdrawnOn, String withdrawalReason) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return named(() -> {
            EnrollmentStatus target = EnrollmentStatus.parse(status);
            return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
                Enrollment before = enrollments.lockById(institution, id).orElseThrow(ResourceNotFoundException::new);
                SectionRef section = sectionOf(institution, before);
                Instant now = now();
                Enrollment after = before.exit(target, withdrawnOn, withdrawalReason, placeOf(section),
                        Enrollment.dayOf(now), now);
                enrollments.update(institution, after);
                Student student = studentOf(institution, before);
                auditLogWriter.append(EnrollmentsAudit.exited(actor, auditRequestId, student.studentCode(), before,
                        after));
                return viewWithHistory(institution, after, student, section);
            });
        });
    }

    public EnrollmentView get(AuthenticatedActor actor, String requestId, UUID id) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            Enrollment enrollment = enrollments.findById(institution, id).orElseThrow(ResourceNotFoundException::new);
            return viewWithHistory(institution, enrollment, studentOf(institution, enrollment),
                    sectionOf(institution, enrollment));
        });
    }

    /**
     * One page of the enrollments. The academic year defaults to the {@code ACTIVE} one when the client sends
     * none (and to every year when there is no active year); {@code gradeId} is resolved to the sections of the
     * grade through the directory, and together with {@code sectionId} keeps only that section when it
     * belongs to the grade.
     */
    public Page<EnrollmentView> list(AuthenticatedActor actor, String requestId, int page, int size, String sort,
            UUID academicYearId, UUID sectionId, UUID gradeId, String status) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return named(() -> {
            PageRequest request = PageRequest.of(page, size, sort, SORT_FIELDS, "studentLastName", true);
            EnrollmentStatus wanted = status == null ? null : EnrollmentStatus.parse(status);
            return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
                UUID year = academicYearId != null ? academicYearId
                        : sections.activeAcademicYearId(institution).orElse(null);
                Collection<UUID> sectionIds = sectionsToKeep(institution, year, sectionId, gradeId);
                Page<EnrollmentRow> rows = enrollments.list(institution, request,
                        new EnrollmentFilter(year, sectionIds, wanted));
                return withSections(institution, rows);
            });
        });
    }

    /** Every enrollment of a student, the most recent first. */
    public List<EnrollmentView> ofStudent(AuthenticatedActor actor, String requestId, UUID studentId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            students.findById(institution, studentId).orElseThrow(ResourceNotFoundException::new);
            List<EnrollmentRow> rows = enrollments.ofStudent(institution, studentId);
            return withSections(institution, new Page<>(rows, rows.size(), 0, Math.max(rows.size(), 1))).items();
        });
    }

    // --- Helpers ---

    /** The sections a list keeps, or {@code null} for all; an empty collection keeps nothing. */
    private Collection<UUID> sectionsToKeep(InstitutionId institution, UUID year, UUID sectionId, UUID gradeId) {
        Collection<UUID> keep = null;
        if (gradeId != null) {
            keep = new LinkedHashSet<>(sections.sectionIdsOfGrade(institution, year, gradeId));
        }
        if (sectionId != null) {
            keep = keep == null || keep.contains(sectionId) ? List.of(sectionId) : List.of();
        }
        return keep;
    }

    private Page<EnrollmentView> withSections(InstitutionId institution, Page<EnrollmentRow> rows) {
        Set<UUID> sectionIds = new LinkedHashSet<>();
        rows.items().forEach(row -> sectionIds.add(row.enrollment().sectionId()));
        Map<UUID, SectionRef> found = sections.findAll(institution, sectionIds);
        return rows.map(row -> {
            Enrollment enrollment = row.enrollment();
            SectionRef section = found.get(enrollment.sectionId());
            if (section == null) {
                throw new IllegalStateException("the section of an enrollment is not in the academic structure");
            }
            return toView(enrollment, row.studentCode(), row.studentFirstName(), row.studentLastName(), section,
                    null);
        });
    }

    private EnrollmentView viewWithHistory(InstitutionId institution, Enrollment enrollment, Student student,
            SectionRef section) {
        List<EnrollmentTransfer> history = enrollments.transfersOf(institution, enrollment.id());
        Set<UUID> ids = new LinkedHashSet<>();
        history.forEach(entry -> {
            ids.add(entry.fromSectionId());
            ids.add(entry.toSectionId());
        });
        return view(enrollment, student, section, history, sections.findAll(institution, ids));
    }

    private static EnrollmentView view(Enrollment enrollment, Student student, SectionRef section,
            List<EnrollmentTransfer> history, Map<UUID, SectionRef> historySections) {
        List<TransferView> transfers = new ArrayList<>();
        for (EnrollmentTransfer entry : history) {
            transfers.add(new TransferView(entry.id(), entry.fromSectionId(), nameOf(historySections, entry.fromSectionId()),
                    entry.toSectionId(), nameOf(historySections, entry.toSectionId()), entry.transferredOn(),
                    entry.reason(), entry.actorId(), entry.createdAt()));
        }
        return toView(enrollment, student.studentCode(), student.firstName(), student.lastName(), section,
                transfers);
    }

    private static String nameOf(Map<UUID, SectionRef> found, UUID sectionId) {
        SectionRef section = found.get(sectionId);
        return section == null ? "" : section.name();
    }

    private static EnrollmentView toView(Enrollment enrollment, String studentCode, String firstName,
            String lastName, SectionRef section, List<TransferView> transfers) {
        return new EnrollmentView(enrollment.id(), enrollment.studentId(), studentCode, firstName, lastName,
                section.academicYearId(), section.academicYearName(), section.sectionId(), section.name(),
                section.gradeId(), section.gradeName(), section.modalityName(), enrollment.enrolledOn(),
                enrollment.status().name(), enrollment.withdrawnOn(), enrollment.withdrawalReason(),
                enrollment.createdAt(), enrollment.updatedAt(), transfers);
    }

    private SectionRef sectionOf(InstitutionId institution, Enrollment enrollment) {
        return sections.find(institution, enrollment.sectionId()).orElseThrow(
                () -> new IllegalStateException("the section of an enrollment is not in the academic structure"));
    }

    private Student studentOf(InstitutionId institution, Enrollment enrollment) {
        return students.findById(institution, enrollment.studentId()).orElseThrow(
                () -> new IllegalStateException("the student of an enrollment is not in the registry"));
    }

    private static SectionPlace placeOf(SectionRef section) {
        return new SectionPlace(section.sectionId(), section.academicYearId(), section.active(),
                section.academicYearClosed(), section.startDate(), section.endDate(), section.capacity());
    }

    /**
     * Runs the work and turns a domain error that names a member of the request into the application's own
     * {@link RequestFieldException}, which the web layer can answer with the field.
     */
    private static <T> T named(Supplier<T> work) {
        try {
            return work.get();
        } catch (InvalidEnrollmentException e) {
            throw new RequestFieldException(e.field(), e.reason());
        }
    }

    /** The instant of the write, to the microsecond the column keeps, so an answer equals what a read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

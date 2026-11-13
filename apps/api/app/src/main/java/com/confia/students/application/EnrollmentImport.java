package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.organization.application.directory.SectionDirectory;
import com.confia.organization.application.directory.SectionNames;
import com.confia.organization.application.directory.SectionRef;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.students.application.directory.StudentRef;
import com.confia.students.application.importing.EnrollmentImportGateway;
import com.confia.students.application.importing.EnrollmentImportRow;
import com.confia.students.application.importing.EnrollmentOutcome;
import com.confia.students.application.importing.PartialEnrollmentImportException;
import com.confia.students.application.importing.RowProblem;
import com.confia.students.domain.ClosedAcademicYearException;
import com.confia.students.domain.DuplicateActiveEnrollmentException;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentImportRules;
import com.confia.students.domain.InactiveSectionException;
import com.confia.students.domain.SectionFullException;
import com.confia.students.domain.StudentNotActiveException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The bulk import of enrollments as {@code students} offers it to {@code imports}: it judges the rows of a file with
 * {@link EnrollmentImportRules} and enrolls the valid ones through {@link ManageEnrollments}, the same use case as the
 * hand enrollment, so INV-31, the capacity under the lock of the section, the academic year, the dates and the audit
 * are exactly those. It restates no rule of the write and has no route of its own into the tables.
 *
 * <p>Each row is its own short transaction (the section is locked per row): a rejection never affects the others and
 * the capacity is never exceeded, not even by two simultaneous applications. The import never moves or replaces an
 * enrollment: a student who already holds one in the same section is skipped and in another section is rejected.
 *
 * <p>Nothing here keeps, returns or logs a cell: a problem is a line, a column name and a reason.
 */
public final class EnrollmentImport implements EnrollmentImportGateway {

    static final int LOOKUP_CHUNK = 1000;
    private static final String LOST_REFERENCE = "a row that was judged lost its reference before it was applied";

    private final TransactionRunner transactionRunner;
    private final StudentDirectory students;
    private final SectionDirectory sections;
    private final EnrollmentRepository enrollments;
    private final ManageEnrollments manageEnrollments;
    private final Clock clock;

    public EnrollmentImport(TransactionRunner transactionRunner, StudentDirectory students,
            SectionDirectory sections, EnrollmentRepository enrollments, ManageEnrollments manageEnrollments,
            Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.students = Objects.requireNonNull(students, "students");
        this.sections = Objects.requireNonNull(sections, "sections");
        this.enrollments = Objects.requireNonNull(enrollments, "enrollments");
        this.manageEnrollments = Objects.requireNonNull(manageEnrollments, "manageEnrollments");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the registry and the academic structure say about the references of a set of rows. */
    private record Facts(Map<String, EnrollmentImportRules.StudentFacts> students,
            Map<String, List<EnrollmentImportRules.SectionFacts>> sectionsByKey,
            Map<String, UUID> activeSectionByStudentYear) {
    }

    @Override
    public List<RowProblem> evaluate(AuthenticatedActor actor, String requestId, List<EnrollmentImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        List<EnrollmentImportRules.Row> mapped = rows.stream().map(EnrollmentImport::rowOf).toList();
        Facts facts = transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> factsOf(institution, mapped));
        return EnrollmentImportRules.judge(mapped, facts.students(), facts.sectionsByKey(),
                facts.activeSectionByStudentYear(), Enrollment.dayOf(clock.instant())).stream()
                .map(problem -> new RowProblem(problem.line(), problem.field(), problem.reason())).toList();
    }

    @Override
    public List<EnrollmentOutcome> applyAll(AuthenticatedActor actor, String requestId,
            List<EnrollmentImportRow> rows) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(rows, "rows");
        UUID auditRequestId = StudentsAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        List<EnrollmentImportRules.Row> mapped = rows.stream().map(EnrollmentImport::rowOf).toList();
        Facts facts = transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()),
                () -> factsOf(institution, mapped));

        List<EnrollmentOutcome> outcomes = new ArrayList<>();
        int created = 0;
        try {
            for (EnrollmentImportRules.Row row : mapped) {
                EnrollmentOutcome outcome = applyRow(actor, requestId, institution, auditRequestId, facts, row);
                outcomes.add(outcome);
                if (outcome == EnrollmentOutcome.CREATED) {
                    created++;
                }
            }
        } catch (RuntimeException failure) {
            // The enrollments already committed stay: the caller is told how many, to leave a trace of the partial result.
            if (created > 0) {
                throw new PartialEnrollmentImportException(created, failure);
            }
            throw failure;
        }
        return List.copyOf(outcomes);
    }

    private EnrollmentOutcome applyRow(AuthenticatedActor actor, String requestId, InstitutionId institution,
            UUID auditRequestId, Facts facts, EnrollmentImportRules.Row row) {
        EnrollmentImportRules.StudentFacts student = facts.students().get(EnrollmentImportRules.studentReference(row));
        List<EnrollmentImportRules.SectionFacts> candidates = facts.sectionsByKey()
                .getOrDefault(EnrollmentImportRules.sectionKey(row), List.of());
        if (student == null || candidates.size() != 1) {
            throw new IllegalStateException(LOST_REFERENCE);
        }
        EnrollmentImportRules.SectionFacts section = candidates.get(0);
        UUID held = facts.activeSectionByStudentYear()
                .get(EnrollmentImportRules.studentYearKey(student.studentId(), section.academicYearId()));
        if (held != null) {
            return held.equals(section.sectionId()) ? EnrollmentOutcome.ALREADY_IMPORTED
                    : EnrollmentOutcome.DUPLICATE_ACTIVE_ENROLLMENT;
        }
        try {
            manageEnrollments.enroll(actor, requestId, student.studentId(), section.sectionId(),
                    EnrollmentImportRules.dateOf(row.enrolledOn()));
            return EnrollmentOutcome.CREATED;
        } catch (DuplicateActiveEnrollmentException e) {
            // An enrollment appeared after the facts were read: whether it is in this section decides if it is a repeat.
            return heldNow(institution, auditRequestId, actor, student.studentId(), section);
        } catch (SectionFullException e) {
            return EnrollmentOutcome.SECTION_FULL;
        } catch (ClosedAcademicYearException e) {
            return EnrollmentOutcome.ACADEMIC_YEAR_CLOSED;
        } catch (InactiveSectionException e) {
            return EnrollmentOutcome.INACTIVE_SECTION;
        } catch (StudentNotActiveException e) {
            return EnrollmentOutcome.STUDENT_NOT_ACTIVE;
        } catch (RequestFieldException e) {
            if (e.field().equals("enrolledOn")) {
                return EnrollmentOutcome.DATE_OUT_OF_RANGE;
            }
            throw e;
        }
    }

    private EnrollmentOutcome heldNow(InstitutionId institution, UUID auditRequestId, AuthenticatedActor actor,
            UUID studentId, EnrollmentImportRules.SectionFacts section) {
        List<EnrollmentRepository.ActiveSeat> seats = transactionRunner.executeReadOnly(
                actor.securityContext(auditRequestId.toString()),
                () -> enrollments.activeSeatsOf(institution, List.of(studentId)));
        boolean sameSection = seats.stream().anyMatch(seat -> seat.academicYearId().equals(section.academicYearId())
                && seat.sectionId().equals(section.sectionId()));
        return sameSection ? EnrollmentOutcome.ALREADY_IMPORTED : EnrollmentOutcome.DUPLICATE_ACTIVE_ENROLLMENT;
    }

    /** Resolves the references of the rows: the students, the sections by their names, and what the students hold. */
    private Facts factsOf(InstitutionId institution, List<EnrollmentImportRules.Row> rows) {
        Map<String, StudentRef> studentRefs = new HashMap<>();
        List<String> references = rows.stream().map(EnrollmentImportRules::studentReference)
                .filter(reference -> !reference.isEmpty()).distinct().toList();
        for (List<String> chunk : chunks(references)) {
            studentRefs.putAll(students.findByExternalRefs(institution, chunk));
        }

        Map<String, SectionNames> namesByKey = new HashMap<>();
        for (EnrollmentImportRules.Row row : rows) {
            if (hasAllNames(row)) {
                namesByKey.putIfAbsent(EnrollmentImportRules.sectionKey(row), new SectionNames(row.academicYear(),
                        row.modality(), row.grade(), row.section()));
            }
        }
        Map<SectionNames, List<SectionRef>> found = sections.findByNames(institution, namesByKey.values());
        Map<String, List<EnrollmentImportRules.SectionFacts>> sectionsByKey = new HashMap<>();
        Map<UUID, Integer> taken = new HashMap<>();
        namesByKey.forEach((key, names) -> {
            List<EnrollmentImportRules.SectionFacts> facts = new ArrayList<>();
            for (SectionRef ref : found.getOrDefault(names, List.of())) {
                int active = taken.computeIfAbsent(ref.sectionId(),
                        id -> enrollments.countActiveInSection(institution, id));
                facts.add(new EnrollmentImportRules.SectionFacts(ref.sectionId(), ref.academicYearId(), ref.active(),
                        ref.academicYearClosed(), ref.startDate(), ref.endDate(), ref.capacity(), active));
            }
            sectionsByKey.put(key, facts);
        });

        Map<String, UUID> seats = new HashMap<>();
        for (List<UUID> chunk : chunks(studentRefs.values().stream().map(StudentRef::studentId).toList())) {
            for (EnrollmentRepository.ActiveSeat seat : enrollments.activeSeatsOf(institution, chunk)) {
                seats.put(EnrollmentImportRules.studentYearKey(seat.studentId(), seat.academicYearId()),
                        seat.sectionId());
            }
        }
        Map<String, EnrollmentImportRules.StudentFacts> studentFacts = new HashMap<>();
        studentRefs.forEach((reference, ref) -> studentFacts.put(reference,
                new EnrollmentImportRules.StudentFacts(ref.studentId(), ref.active())));
        return new Facts(studentFacts, sectionsByKey, seats);
    }

    private static boolean hasAllNames(EnrollmentImportRules.Row row) {
        return row.academicYear() != null && row.modality() != null && row.grade() != null && row.section() != null;
    }

    private static <T> List<List<T>> chunks(Collection<T> values) {
        List<T> all = List.copyOf(values);
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < all.size(); from += LOOKUP_CHUNK) {
            chunks.add(all.subList(from, Math.min(from + LOOKUP_CHUNK, all.size())));
        }
        return chunks;
    }

    private static EnrollmentImportRules.Row rowOf(EnrollmentImportRow row) {
        return new EnrollmentImportRules.Row(row.line(), row.studentLegacyId(), row.academicYear(), row.modality(),
                row.grade(), row.section(), row.enrolledOn());
    }
}

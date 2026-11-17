package com.confia.students.infrastructure;

import static confia.generated.jooq.tables.StudentsEnrollment.STUDENTS_ENROLLMENT;
import static confia.generated.jooq.tables.StudentsEnrollmentTransfer.STUDENTS_ENROLLMENT_TRANSFER;

import com.confia.kernel.InstitutionId;
import com.confia.students.application.directory.EnrollmentRoster;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

/**
 * The jOOQ adapter of {@link EnrollmentRoster}: one read-only query. The section of an enrollment on the reference date is
 * the {@code from_section_id} of its first transfer after that date, or the current section when there is none: a transfer
 * on a date puts the student in its target section from that day on. It never opens a transaction and never sets the
 * institution of the session; row-level security does the second filter.
 */
public final class JooqEnrollmentRoster implements EnrollmentRoster {

    private final DSLContext dsl;

    public JooqEnrollmentRoster(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<RosterEntry> enrolledDuring(InstitutionId institutionId, UUID academicYearId,
            Collection<UUID> sectionIds, LocalDate periodStart, LocalDate periodEnd) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        Field<LocalDate> reference = DSL.greatest(DSL.val(periodStart), STUDENTS_ENROLLMENT.ENROLLED_ON);
        Field<UUID> firstLaterFromSection = dsl.select(STUDENTS_ENROLLMENT_TRANSFER.FROM_SECTION_ID)
                .from(STUDENTS_ENROLLMENT_TRANSFER)
                .where(STUDENTS_ENROLLMENT_TRANSFER.INSTITUTION_ID.eq(STUDENTS_ENROLLMENT.INSTITUTION_ID))
                .and(STUDENTS_ENROLLMENT_TRANSFER.ENROLLMENT_ID.eq(STUDENTS_ENROLLMENT.ID))
                .and(STUDENTS_ENROLLMENT_TRANSFER.TRANSFERRED_ON.gt(reference))
                .orderBy(STUDENTS_ENROLLMENT_TRANSFER.TRANSFERRED_ON, STUDENTS_ENROLLMENT_TRANSFER.CREATED_AT)
                .limit(1).asField();
        Field<UUID> sectionAtReference = DSL.coalesce(firstLaterFromSection, STUDENTS_ENROLLMENT.SECTION_ID);

        record Candidate(UUID studentId, UUID enrollmentId, UUID sectionId, LocalDate enrolledOn,
                OffsetDateTime createdAt) {
        }
        List<Candidate> candidates = dsl
                .select(STUDENTS_ENROLLMENT.STUDENT_ID, STUDENTS_ENROLLMENT.ID, sectionAtReference,
                        STUDENTS_ENROLLMENT.ENROLLED_ON, STUDENTS_ENROLLMENT.CREATED_AT)
                .from(STUDENTS_ENROLLMENT)
                .where(STUDENTS_ENROLLMENT.INSTITUTION_ID.eq(institutionId.value()))
                .and(STUDENTS_ENROLLMENT.ACADEMIC_YEAR_ID.eq(academicYearId))
                .and(STUDENTS_ENROLLMENT.ENROLLED_ON.le(periodEnd))
                .and(STUDENTS_ENROLLMENT.WITHDRAWN_ON.isNull().or(STUDENTS_ENROLLMENT.WITHDRAWN_ON.ge(periodStart)))
                .fetch(row -> new Candidate(row.get(STUDENTS_ENROLLMENT.STUDENT_ID), row.get(STUDENTS_ENROLLMENT.ID),
                        row.get(sectionAtReference), row.get(STUDENTS_ENROLLMENT.ENROLLED_ON),
                        row.get(STUDENTS_ENROLLMENT.CREATED_AT)));

        // One enrollment per student: the most recent that overlaps the period; only then the section decides.
        Comparator<Candidate> mostRecentLast = Comparator.comparing(Candidate::enrolledOn)
                .thenComparing(Candidate::createdAt).thenComparing(Candidate::enrollmentId);
        Map<UUID, Candidate> latest = new HashMap<>();
        for (Candidate candidate : candidates) {
            latest.merge(candidate.studentId(), candidate,
                    (a, b) -> mostRecentLast.compare(a, b) >= 0 ? a : b);
        }
        List<RosterEntry> roster = new ArrayList<>();
        for (Candidate candidate : latest.values()) {
            if (sectionIds.contains(candidate.sectionId())) {
                roster.add(new RosterEntry(candidate.studentId(), candidate.enrollmentId(), candidate.sectionId()));
            }
        }
        roster.sort(Comparator.comparing(RosterEntry::studentId));
        return roster;
    }
}

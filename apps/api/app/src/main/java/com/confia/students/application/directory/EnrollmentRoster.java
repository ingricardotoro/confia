package com.confia.students.application.directory;

import com.confia.kernel.InstitutionId;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Output port, read-only, through which {@code charges} asks {@code students} who is enrolled in a set of sections during a
 * period (docs/cortes/charges-accrual.md, engine point 2). Scoped to the institution given; it writes nothing and takes no
 * lock.
 */
public interface EnrollmentRoster {

    /**
     * The students with an enrollment of the academic year (in any status) that overlaps the period ({@code enrolledOn <=
     * periodEnd} and no exit date or {@code withdrawnOn >= periodStart}), placed in one of the sections on the reference
     * date {@code max(periodStart, enrolledOn)}. The section on that date is rebuilt from the transfers of the enrollment,
     * because the enrollment row holds only the current one. A student with two enrollments that overlap the period
     * appears once, with the most recent one (the later enrollment date, then the later creation).
     */
    List<RosterEntry> enrolledDuring(InstitutionId institutionId, UUID academicYearId, Collection<UUID> sectionIds,
            LocalDate periodStart, LocalDate periodEnd);

    /** A student of the roster, the enrollment that qualifies it and the section it was in on the reference date. */
    record RosterEntry(UUID studentId, UUID enrollmentId, UUID sectionId) {
    }
}

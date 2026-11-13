package com.confia.students.application;

import com.confia.kernel.InstitutionId;
import com.confia.students.domain.Enrollment;
import com.confia.students.domain.EnrollmentTransfer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Output port of the enrollments of an institution. A second active enrollment of a student in the year
 * (INV-31) is refused by {@link #insert} as a {@code DuplicateActiveEnrollmentException}. Nothing is ever
 * deleted, and the history of transfers is only inserted and read.
 */
public interface EnrollmentRepository {

    Optional<Enrollment> findById(InstitutionId institutionId, UUID id);

    /**
     * The enrollment, locked until the end of the transaction ({@code SELECT ... FOR UPDATE}), so two
     * simultaneous exits or transfers of it take turns and the second sees what the first left.
     */
    Optional<Enrollment> lockById(InstitutionId institutionId, UUID id);

    void insert(InstitutionId institutionId, Enrollment enrollment);

    /** Writes the section, the status, the exit data and the update instant; an unknown enrollment is a {@code ResourceNotFoundException}. */
    void update(InstitutionId institutionId, Enrollment enrollment);

    void insertTransfer(InstitutionId institutionId, EnrollmentTransfer transfer);

    /** The history of the sections of the enrollment, oldest first. */
    List<EnrollmentTransfer> transfersOf(InstitutionId institutionId, UUID enrollmentId);

    /** The active enrollments of the section: the seats taken. */
    int countActiveInSection(InstitutionId institutionId, UUID sectionId);

    /** Whether the student has an active enrollment in the academic year. */
    boolean hasActiveInYear(InstitutionId institutionId, UUID studentId, UUID academicYearId);

    /** The section of an active enrollment, with the student and the academic year it belongs to. */
    record ActiveSeat(UUID studentId, UUID academicYearId, UUID sectionId) {
    }

    /**
     * The active enrollments these students hold, one per student and year (INV-31), in one query. Students with none
     * are absent.
     */
    List<ActiveSeat> activeSeatsOf(InstitutionId institutionId, Collection<UUID> studentIds);

    Page<EnrollmentRow> list(InstitutionId institutionId, PageRequest request, EnrollmentFilter filter);

    /** Every enrollment of the student, the most recent first. */
    List<EnrollmentRow> ofStudent(InstitutionId institutionId, UUID studentId);
}

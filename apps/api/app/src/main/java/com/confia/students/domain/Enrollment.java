package com.confia.students.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * The enrollment of a student in a section of an academic year (docs/cortes/enrollments.md). The academic
 * year is the one of the section; the grade and the modality are derived from the section and not kept.
 * Immutable: every transition returns a new value. Nothing is deleted: an enrollment leaves {@link
 * EnrollmentStatus#ACTIVE} for one of the three final statuses and keeps its row.
 *
 * <p>What needs other rows is passed in by the use case, which reads it under the locks that make it true:
 * whether the student already has an active enrollment in the year (INV-31, which the database also
 * enforces with a partial unique index) and how many active enrollments the section has (the capacity, which
 * the use case counts after locking the section).
 *
 * @param withdrawnOn the day the enrollment left {@code ACTIVE}; {@code null} while it is active
 * @param withdrawalReason why; {@code null} while active, required for {@code WITHDRAWN} and {@code
 *     TRANSFERRED}, optional for {@code GRADUATED}
 */
public record Enrollment(UUID id, UUID studentId, UUID sectionId, UUID academicYearId, LocalDate enrolledOn,
        EnrollmentStatus status, LocalDate withdrawnOn, String withdrawalReason, Instant createdAt,
        Instant updatedAt) {

    static final int MIN_REASON_LENGTH = 3;
    static final int MAX_REASON_LENGTH = 500;

    private static final ZoneId HONDURAS = ZoneId.of("America/Tegucigalpa");

    public Enrollment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(sectionId, "sectionId");
        Objects.requireNonNull(academicYearId, "academicYearId");
        Objects.requireNonNull(enrolledOn, "enrolledOn");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        boolean active = status == EnrollmentStatus.ACTIVE;
        if (active != (withdrawnOn == null) || (active && withdrawalReason != null)
                || (withdrawnOn != null && withdrawnOn.isBefore(enrolledOn))) {
            throw new IllegalArgumentException("the exit data of the enrollment does not match its status");
        }
    }

    /** The day it is in Honduras at {@code instant}: the "today" of every date rule. */
    public static LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, HONDURAS);
    }

    /**
     * A new, active enrollment of {@code student} in the section described by {@code place}.
     *
     * @param studentHasActiveInYear whether the student already has an active enrollment in that year
     * @param activeInSection the active enrollments the section holds now
     * @param today the day it is in Honduras
     */
    public static Enrollment enroll(UUID id, Student student, SectionPlace place, boolean studentHasActiveInYear,
            int activeInSection, LocalDate enrolledOn, LocalDate today, Instant now) {
        if (student.status() != StudentStatus.ACTIVE) {
            throw new StudentNotActiveException();
        }
        requireOpenYear(place);
        requireActiveSection(place);
        if (enrolledOn == null) {
            throw new InvalidEnrollmentException("enrolledOn", "not-null");
        }
        if (!place.contains(enrolledOn) || enrolledOn.isAfter(today)) {
            throw new InvalidEnrollmentException("enrolledOn", InvalidEnrollmentException.DATE_OUT_OF_RANGE);
        }
        if (studentHasActiveInYear) {
            throw new DuplicateActiveEnrollmentException();
        }
        requireRoom(place, activeInSection);
        return new Enrollment(id, student.id(), place.sectionId(), place.academicYearId(), enrolledOn,
                EnrollmentStatus.ACTIVE, null, null, now, now);
    }

    /** The result of a transfer between sections: the enrollment in its new section and the history entry. */
    public record Moved(Enrollment enrollment, EnrollmentTransfer transfer) {
    }

    /**
     * Moves the enrollment to another section of the same academic year, which must be active and have a free
     * seat. The enrollment stays active and keeps its enrollment date.
     *
     * @param current the section the enrollment is in now
     * @param target the section it moves to
     * @param activeInTarget the active enrollments the target section holds now
     * @param reason why; optional, 3 to 500 characters once stripped
     */
    public Moved transferTo(UUID transferId, UUID actorId, SectionPlace current, SectionPlace target,
            int activeInTarget, LocalDate transferredOn, String reason, LocalDate today, Instant now) {
        requireActive();
        requireOpenYear(current);
        if (target.sectionId().equals(sectionId)) {
            throw new InvalidEnrollmentException("toSectionId", "same-section");
        }
        if (!target.academicYearId().equals(academicYearId)) {
            throw new TransferYearMismatchException();
        }
        requireActiveSection(target);
        if (transferredOn == null) {
            throw new InvalidEnrollmentException("transferredOn", "not-null");
        }
        if (transferredOn.isBefore(enrolledOn) || transferredOn.isAfter(today)
                || transferredOn.isAfter(current.yearEnd())) {
            throw new InvalidEnrollmentException("transferredOn", InvalidEnrollmentException.DATE_OUT_OF_RANGE);
        }
        String cleanReason = reason("reason", reason, false);
        requireRoom(target, activeInTarget);
        Enrollment moved = new Enrollment(id, studentId, target.sectionId(), academicYearId, enrolledOn, status,
                null, null, createdAt, now);
        return new Moved(moved, new EnrollmentTransfer(transferId, id, sectionId, target.sectionId(),
                transferredOn, cleanReason, actorId, now));
    }

    /**
     * Ends the active enrollment as {@code target}: {@code WITHDRAWN}, {@code TRANSFERRED} (to another
     * institution) or {@code GRADUATED}. The exit date is not before the enrollment date and not in the
     * future; the reason is required for the first two and optional for a graduation.
     *
     * @param place the section the enrollment is in, to know whether its year is closed
     */
    public Enrollment exit(EnrollmentStatus target, LocalDate on, String reason, SectionPlace place,
            LocalDate today, Instant now) {
        requireActive();
        requireOpenYear(place);
        if (target == null || target == EnrollmentStatus.ACTIVE) {
            throw new InvalidEnrollmentException("status", "pattern");
        }
        if (on == null) {
            throw new InvalidEnrollmentException("withdrawnOn", "not-null");
        }
        if (on.isBefore(enrolledOn) || on.isAfter(today)) {
            throw new InvalidEnrollmentException("withdrawnOn", InvalidEnrollmentException.DATE_OUT_OF_RANGE);
        }
        String cleanReason = reason("withdrawalReason", reason, target != EnrollmentStatus.GRADUATED);
        return new Enrollment(id, studentId, sectionId, academicYearId, enrolledOn, target, on, cleanReason,
                createdAt, now);
    }

    private void requireActive() {
        if (status != EnrollmentStatus.ACTIVE) {
            throw new InvalidEnrollmentStateException();
        }
    }

    private static void requireOpenYear(SectionPlace place) {
        if (place.yearClosed()) {
            throw new ClosedAcademicYearException();
        }
    }

    private static void requireActiveSection(SectionPlace place) {
        if (!place.active()) {
            throw new InactiveSectionException();
        }
    }

    private static void requireRoom(SectionPlace place, int activeEnrollments) {
        if (!place.hasRoom(activeEnrollments)) {
            throw new SectionFullException();
        }
    }

    /** A reason stripped; blank is none, which is only allowed when not required; 3 to 500 characters otherwise. */
    private static String reason(String field, String reason, boolean required) {
        String stripped = reason == null ? "" : reason.strip();
        if (stripped.isEmpty()) {
            if (required) {
                throw new InvalidEnrollmentException(field, "not-blank");
            }
            return null;
        }
        if (stripped.length() < MIN_REASON_LENGTH || stripped.length() > MAX_REASON_LENGTH) {
            throw new InvalidEnrollmentException(field, "size");
        }
        return stripped;
    }

    /** The reason is free text typed by the staff: it never reaches a log through this method. */
    @Override
    public String toString() {
        return "Enrollment[id=" + id + "]";
    }
}

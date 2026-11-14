package com.confia.students.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules of an enrollment (docs/cortes/enrollments.md): who may be enrolled, where and when, the closed
 * set of transitions, the date and reason rules of an exit and the rules of a section transfer. The
 * database and the use case own what needs other rows (the second active enrollment of the year is decided
 * with a flag the use case computes).
 */
class EnrollmentTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final LocalDate YEAR_START = LocalDate.of(2026, 1, 15);
    private static final LocalDate YEAR_END = LocalDate.of(2026, 11, 30);

    private final UUID yearId = UUID.randomUUID();
    private final SectionPlace place = place(UUID.randomUUID(), yearId, true, false, 30);

    private static SectionPlace place(UUID sectionId, UUID yearId, boolean active, boolean closed, Integer capacity) {
        return new SectionPlace(sectionId, yearId, active, closed, YEAR_START, YEAR_END, capacity);
    }

    private static Student student(StudentStatus status) {
        return new Student(UUID.randomUUID(), "E-000001", "Ana", "Perez", LocalDate.of(2012, 5, 20), Sex.F, status,
                false, NOW, NOW);
    }

    private Enrollment enroll(LocalDate on) {
        return Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), place, false, 0, on, TODAY, NOW);
    }

    private Enrollment active() {
        return enroll(LocalDate.of(2026, 2, 1));
    }

    private static InvalidEnrollmentException invalid(Enrollment ignored, Runnable action) {
        return (InvalidEnrollmentException) catchOf(action);
    }

    private static Throwable catchOf(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        throw new AssertionError("nothing was thrown");
    }

    // --- Enrolling ---

    @Test
    void anEnrollmentIsBornActiveInTheSectionAndTheYearOfTheSectionWithoutExitData() {
        Student student = student(StudentStatus.ACTIVE);

        Enrollment enrollment = Enrollment.enroll(UUID.randomUUID(), student, place, false, 0,
                LocalDate.of(2026, 2, 1), TODAY, NOW);

        assertThat(enrollment.studentId()).isEqualTo(student.id());
        assertThat(enrollment.sectionId()).isEqualTo(place.sectionId());
        assertThat(enrollment.academicYearId()).isEqualTo(yearId);
        assertThat(enrollment.status()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(enrollment.withdrawnOn()).isNull();
        assertThat(enrollment.withdrawalReason()).isNull();
        assertThat(enrollment.createdAt()).isEqualTo(NOW).isEqualTo(enrollment.updatedAt());
    }

    @Test
    void anInactiveStudentCannotBeEnrolled() {
        assertThatThrownBy(() -> Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.INACTIVE), place, false,
                0, TODAY, TODAY, NOW)).isInstanceOf(StudentNotActiveException.class)
                .extracting("code").isEqualTo("student-not-active");
    }

    @Test
    void anInactiveSectionCannotBeEnrolledInAndAnswersInactiveReference() {
        SectionPlace inactive = place(UUID.randomUUID(), yearId, false, false, 30);

        assertThatThrownBy(() -> Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), inactive, false,
                0, TODAY, TODAY, NOW)).isInstanceOf(InactiveSectionException.class)
                .extracting("code").isEqualTo("inactive-reference");
    }

    @Test
    void aClosedYearAdmitsNoEnrollmentAndAnswersAcademicYearClosed() {
        SectionPlace closed = place(UUID.randomUUID(), yearId, true, true, 30);

        assertThatThrownBy(() -> Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), closed, false,
                0, TODAY, TODAY, NOW)).isInstanceOf(ClosedAcademicYearException.class)
                .extracting("code").isEqualTo("academic-year-closed");
    }

    @Test
    void theEnrollmentDateIncludesTheFirstDayAndTheDayItIsToday() {
        assertThat(enroll(YEAR_START).enrolledOn()).isEqualTo(YEAR_START);
        assertThat(enroll(TODAY).enrolledOn()).isEqualTo(TODAY);
    }

    @Test
    void theLastDayOfTheYearIsInsideTheRangeWhenItIsNotInTheFuture() {
        Instant afterTheYear = Instant.parse("2026-12-05T15:00:00Z");

        Enrollment enrollment = Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), place, false, 0,
                YEAR_END, LocalDate.of(2026, 12, 5), afterTheYear);

        assertThat(enrollment.enrolledOn()).isEqualTo(YEAR_END);
    }

    @Test
    void theDayBeforeTheYearStartsTheDayAfterItEndsAndTheFutureAreOutOfRangeOnTheEnrollmentDate() {
        LocalDate afterTheYear = YEAR_END.plusDays(1);
        for (LocalDate bad : new LocalDate[] {YEAR_START.minusDays(1), TODAY.plusDays(1)}) {
            InvalidEnrollmentException e = invalid(null, () -> enroll(bad));
            assertThat(e.field()).isEqualTo("enrolledOn");
            assertThat(e.reason()).isEqualTo("enrollment-date-out-of-range");
            assertThat(e.code()).isEqualTo("validation-failed");
        }
        InvalidEnrollmentException late = invalid(null, () -> Enrollment.enroll(UUID.randomUUID(),
                student(StudentStatus.ACTIVE), place, false, 0, afterTheYear, afterTheYear.plusDays(3), NOW));
        assertThat(late.field()).isEqualTo("enrolledOn");
        assertThat(late.reason()).isEqualTo("enrollment-date-out-of-range");
    }

    @Test
    void aMissingEnrollmentDateIsRefusedOnItsField() {
        InvalidEnrollmentException e = invalid(null, () -> enroll(null));

        assertThat(e.field()).isEqualTo("enrolledOn");
    }

    @Test
    void aSecondActiveEnrollmentOfTheStudentInTheYearIsDuplicate() {
        assertThatThrownBy(() -> Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), place, true, 0,
                TODAY, TODAY, NOW)).isInstanceOf(DuplicateActiveEnrollmentException.class)
                .extracting("code").isEqualTo("duplicate-active-enrollment");
    }

    @Test
    void theLastSeatIsTakenAndTheNextEnrollmentFindsTheSectionFull() {
        SectionPlace small = place(UUID.randomUUID(), yearId, true, false, 2);

        Enrollment last = Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), small, false, 1, TODAY,
                TODAY, NOW);

        assertThat(last.status()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThatThrownBy(() -> Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), small, false, 2,
                TODAY, TODAY, NOW)).isInstanceOf(SectionFullException.class)
                .extracting("code").isEqualTo("section-full");
    }

    @Test
    void aSectionWithoutCapacityHasNoLimit() {
        SectionPlace unlimited = place(UUID.randomUUID(), yearId, true, false, null);

        assertThat(Enrollment.enroll(UUID.randomUUID(), student(StudentStatus.ACTIVE), unlimited, false, 100_000,
                TODAY, TODAY, NOW).status()).isEqualTo(EnrollmentStatus.ACTIVE);
    }

    // --- Exits ---

    @Test
    void anActiveEnrollmentCanBeWithdrawnTransferredOutOrGraduated() {
        Enrollment withdrawn = active().exit(EnrollmentStatus.WITHDRAWN, TODAY, "  Cambio de ciudad ", place, TODAY,
                NOW.plusSeconds(60));
        Enrollment transferred = active().exit(EnrollmentStatus.TRANSFERRED, TODAY, "Otra institucion", place, TODAY,
                NOW);
        Enrollment graduated = active().exit(EnrollmentStatus.GRADUATED, TODAY, null, place, TODAY, NOW);

        assertThat(withdrawn.status()).isEqualTo(EnrollmentStatus.WITHDRAWN);
        assertThat(withdrawn.withdrawnOn()).isEqualTo(TODAY);
        assertThat(withdrawn.withdrawalReason()).as("stripped").isEqualTo("Cambio de ciudad");
        assertThat(withdrawn.updatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(transferred.status()).isEqualTo(EnrollmentStatus.TRANSFERRED);
        assertThat(graduated.status()).isEqualTo(EnrollmentStatus.GRADUATED);
        assertThat(graduated.withdrawalReason()).isNull();
    }

    @Test
    void theReasonOfAGraduationIsOptionalAndABlankOneIsNoReason() {
        assertThat(active().exit(EnrollmentStatus.GRADUATED, TODAY, "   ", place, TODAY, NOW).withdrawalReason())
                .isNull();
        assertThat(active().exit(EnrollmentStatus.GRADUATED, TODAY, "Promocion 2026", place, TODAY, NOW)
                .withdrawalReason()).isEqualTo("Promocion 2026");
    }

    @Test
    void aWithdrawalOrATransferOutWithoutReasonIsRefusedOnTheReasonField() {
        for (EnrollmentStatus status : new EnrollmentStatus[] {EnrollmentStatus.WITHDRAWN,
                EnrollmentStatus.TRANSFERRED}) {
            for (String reason : new String[] {null, "", "   "}) {
                InvalidEnrollmentException e = invalid(null,
                        () -> active().exit(status, TODAY, reason, place, TODAY, NOW));
                assertThat(e.field()).isEqualTo("withdrawalReason");
                assertThat(e.reason()).isEqualTo("not-blank");
            }
        }
    }

    @Test
    void theReasonHasBetweenThreeAndFiveHundredCharactersOnceStripped() {
        assertThat(active().exit(EnrollmentStatus.WITHDRAWN, TODAY, " abc ", place, TODAY, NOW).withdrawalReason())
                .isEqualTo("abc");
        assertThat(active().exit(EnrollmentStatus.WITHDRAWN, TODAY, "a".repeat(500), place, TODAY, NOW)
                .withdrawalReason()).hasSize(500);
        for (String bad : new String[] {"ab", " ab ", "a".repeat(501)}) {
            InvalidEnrollmentException e = invalid(null,
                    () -> active().exit(EnrollmentStatus.WITHDRAWN, TODAY, bad, place, TODAY, NOW));
            assertThat(e.field()).isEqualTo("withdrawalReason");
            assertThat(e.reason()).isEqualTo("size");
        }
        InvalidEnrollmentException graduated = invalid(null,
                () -> active().exit(EnrollmentStatus.GRADUATED, TODAY, "ab", place, TODAY, NOW));
        assertThat(graduated.reason()).as("also optional but not too short").isEqualTo("size");
    }

    @Test
    void theExitDateIsNotBeforeTheEnrollmentDateAndNotInTheFuture() {
        Enrollment enrollment = enroll(LocalDate.of(2026, 3, 1));

        assertThat(enrollment.exit(EnrollmentStatus.WITHDRAWN, LocalDate.of(2026, 3, 1), "Motivo", place, TODAY, NOW)
                .withdrawnOn()).as("the same day is allowed").isEqualTo(LocalDate.of(2026, 3, 1));
        for (LocalDate bad : new LocalDate[] {LocalDate.of(2026, 2, 28), TODAY.plusDays(1)}) {
            InvalidEnrollmentException e = invalid(null,
                    () -> enrollment.exit(EnrollmentStatus.WITHDRAWN, bad, "Motivo", place, TODAY, NOW));
            assertThat(e.field()).isEqualTo("withdrawnOn");
            assertThat(e.reason()).isEqualTo("enrollment-date-out-of-range");
        }
        assertThat(invalid(null, () -> enrollment.exit(EnrollmentStatus.WITHDRAWN, null, "Motivo", place, TODAY, NOW))
                .field()).isEqualTo("withdrawnOn");
    }

    @Test
    void activeIsNotAnExitStatus() {
        InvalidEnrollmentException e = invalid(null,
                () -> active().exit(EnrollmentStatus.ACTIVE, TODAY, "Motivo", place, TODAY, NOW));
        assertThat(e.field()).isEqualTo("status");
        assertThat(invalid(null, () -> active().exit(null, TODAY, "Motivo", place, TODAY, NOW)).field())
                .isEqualTo("status");
    }

    @Test
    void anExitIsOnlyPossibleFromActiveEveryOtherStateIsFinal() {
        for (EnrollmentStatus finalState : new EnrollmentStatus[] {EnrollmentStatus.WITHDRAWN,
                EnrollmentStatus.TRANSFERRED, EnrollmentStatus.GRADUATED}) {
            Enrollment done = active().exit(finalState, TODAY, "Motivo", place, TODAY, NOW);
            for (EnrollmentStatus target : new EnrollmentStatus[] {EnrollmentStatus.WITHDRAWN,
                    EnrollmentStatus.TRANSFERRED, EnrollmentStatus.GRADUATED}) {
                assertThatThrownBy(() -> done.exit(target, TODAY, "Motivo", place, TODAY, NOW))
                        .isInstanceOf(InvalidEnrollmentStateException.class)
                        .extracting("code").isEqualTo("invalid-enrollment-state");
            }
        }
    }

    @Test
    void aClosedYearAdmitsNoExit() {
        SectionPlace closed = place(place.sectionId(), yearId, true, true, 30);

        assertThatThrownBy(() -> active().exit(EnrollmentStatus.WITHDRAWN, TODAY, "Motivo", closed, TODAY, NOW))
                .isInstanceOf(ClosedAcademicYearException.class);
    }

    // --- Transfers between sections ---

    private SectionPlace target(boolean active, boolean closed, Integer capacity, UUID year) {
        return place(UUID.randomUUID(), year, active, closed, capacity);
    }

    @Test
    void aTransferMovesTheEnrollmentToTheNewSectionAndWritesOneImmutableHistoryEntry() {
        Enrollment enrollment = active();
        SectionPlace to = target(true, false, 30, yearId);
        UUID actor = UUID.randomUUID();

        Enrollment.Moved moved = enrollment.transferTo(UUID.randomUUID(), actor, place, to, 0,
                LocalDate.of(2026, 3, 5), " Cambio de turno ", TODAY, NOW.plusSeconds(5));

        assertThat(moved.enrollment().sectionId()).isEqualTo(to.sectionId());
        assertThat(moved.enrollment().status()).isEqualTo(EnrollmentStatus.ACTIVE);
        assertThat(moved.enrollment().academicYearId()).isEqualTo(yearId);
        assertThat(moved.enrollment().enrolledOn()).isEqualTo(enrollment.enrolledOn());
        assertThat(moved.enrollment().updatedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(moved.transfer().enrollmentId()).isEqualTo(enrollment.id());
        assertThat(moved.transfer().fromSectionId()).isEqualTo(place.sectionId());
        assertThat(moved.transfer().toSectionId()).isEqualTo(to.sectionId());
        assertThat(moved.transfer().transferredOn()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(moved.transfer().reason()).isEqualTo("Cambio de turno");
        assertThat(moved.transfer().actorId()).isEqualTo(actor);
        assertThat(enrollment.sectionId()).as("the original is a value that never changes").isEqualTo(place.sectionId());
    }

    @Test
    void theReasonOfATransferIsOptionalButNotTooShortOrTooLong() {
        Enrollment enrollment = active();
        SectionPlace to = target(true, false, null, yearId);

        assertThat(enrollment.transferTo(UUID.randomUUID(), UUID.randomUUID(), place, to, 0, TODAY, null, TODAY, NOW)
                .transfer().reason()).isNull();
        assertThat(enrollment.transferTo(UUID.randomUUID(), UUID.randomUUID(), place, to, 0, TODAY, "  ", TODAY, NOW)
                .transfer().reason()).isNull();
        for (String bad : new String[] {"ab", "a".repeat(501)}) {
            InvalidEnrollmentException e = invalid(null, () -> enrollment.transferTo(UUID.randomUUID(),
                    UUID.randomUUID(), place, to, 0, TODAY, bad, TODAY, NOW));
            assertThat(e.field()).isEqualTo("reason");
            assertThat(e.reason()).isEqualTo("size");
        }
    }

    @Test
    void aTransferToTheSameSectionIsRefusedOnTheTargetField() {
        InvalidEnrollmentException e = invalid(null, () -> active().transferTo(UUID.randomUUID(), UUID.randomUUID(),
                place, place, 0, TODAY, null, TODAY, NOW));

        assertThat(e.field()).isEqualTo("toSectionId");
        assertThat(e.reason()).isEqualTo("same-section");
    }

    @Test
    void aTransferToASectionOfAnotherYearIsAYearMismatch() {
        SectionPlace otherYear = target(true, false, 30, UUID.randomUUID());

        assertThatThrownBy(() -> active().transferTo(UUID.randomUUID(), UUID.randomUUID(), place, otherYear, 0,
                TODAY, null, TODAY, NOW)).isInstanceOf(TransferYearMismatchException.class)
                .extracting("code").isEqualTo("transfer-year-mismatch");
    }

    @Test
    void aTransferToAnInactiveOrFullSectionIsRefused() {
        SectionPlace inactive = target(false, false, 30, yearId);
        SectionPlace full = target(true, false, 2, yearId);

        assertThatThrownBy(() -> active().transferTo(UUID.randomUUID(), UUID.randomUUID(), place, inactive, 0,
                TODAY, null, TODAY, NOW)).isInstanceOf(InactiveSectionException.class);
        assertThatThrownBy(() -> active().transferTo(UUID.randomUUID(), UUID.randomUUID(), place, full, 2, TODAY,
                null, TODAY, NOW)).isInstanceOf(SectionFullException.class);
        assertThat(active().transferTo(UUID.randomUUID(), UUID.randomUUID(), place, full, 1, TODAY, null, TODAY, NOW)
                .enrollment().sectionId()).as("one seat left").isEqualTo(full.sectionId());
    }

    @Test
    void aTransferNeedsAnActiveEnrollmentAndAnOpenYear() {
        Enrollment withdrawn = active().exit(EnrollmentStatus.WITHDRAWN, TODAY, "Motivo", place, TODAY, NOW);
        SectionPlace to = target(true, false, 30, yearId);
        SectionPlace closedFrom = place(place.sectionId(), yearId, true, true, 30);

        assertThatThrownBy(() -> withdrawn.transferTo(UUID.randomUUID(), UUID.randomUUID(), place, to, 0, TODAY,
                null, TODAY, NOW)).isInstanceOf(InvalidEnrollmentStateException.class);
        assertThatThrownBy(() -> active().transferTo(UUID.randomUUID(), UUID.randomUUID(), closedFrom, to, 0,
                TODAY, null, TODAY, NOW)).isInstanceOf(ClosedAcademicYearException.class);
    }

    @Test
    void theDateOfATransferIsNotBeforeTheEnrollmentNotInTheFutureAndNotAfterTheYear() {
        Enrollment enrollment = enroll(LocalDate.of(2026, 3, 1));
        SectionPlace to = target(true, false, 30, yearId);

        assertThat(enrollment.transferTo(UUID.randomUUID(), UUID.randomUUID(), place, to, 0,
                LocalDate.of(2026, 3, 1), null, TODAY, NOW).transfer().transferredOn())
                .isEqualTo(LocalDate.of(2026, 3, 1));
        for (LocalDate bad : new LocalDate[] {LocalDate.of(2026, 2, 28), TODAY.plusDays(1)}) {
            InvalidEnrollmentException e = invalid(null, () -> enrollment.transferTo(UUID.randomUUID(),
                    UUID.randomUUID(), place, to, 0, bad, null, TODAY, NOW));
            assertThat(e.field()).isEqualTo("transferredOn");
            assertThat(e.reason()).isEqualTo("enrollment-date-out-of-range");
        }
        assertThat(invalid(null, () -> enrollment.transferTo(UUID.randomUUID(), UUID.randomUUID(), place, to, 0,
                null, null, TODAY, NOW)).field()).isEqualTo("transferredOn");
    }

    // --- Values ---

    @Test
    void theStatusesAreParsedExactlyAndAnythingElseIsRefusedOnTheStatusField() {
        assertThat(EnrollmentStatus.parse("GRADUATED")).isEqualTo(EnrollmentStatus.GRADUATED);
        for (String bad : new String[] {null, "", "active", "DELETED"}) {
            InvalidEnrollmentException e = invalid(null, () -> EnrollmentStatus.parse(bad));
            assertThat(e.field()).isEqualTo("status");
        }
    }

    @Test
    void anExitedEnrollmentCannotBeRebuiltWithoutItsExitDateOrAnActiveOneWithIt() {
        UUID id = UUID.randomUUID();
        LocalDate on = LocalDate.of(2026, 2, 1);

        assertThatThrownBy(() -> new Enrollment(id, id, id, id, on, EnrollmentStatus.WITHDRAWN, null, "Motivo", NOW,
                NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Enrollment(id, id, id, id, on, EnrollmentStatus.ACTIVE, on, null, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Enrollment(id, id, id, id, on, EnrollmentStatus.ACTIVE, null, "Motivo", NOW,
                NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTodayOfTheRulesIsTheDayInHonduras() {
        assertThat(Enrollment.dayOf(Instant.parse("2026-03-11T04:30:00Z"))).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(Enrollment.dayOf(Instant.parse("2026-03-11T06:00:00Z"))).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test
    void theToStringHoldsNoReasonAndNoPersonalData() {
        Enrollment withdrawn = active().exit(EnrollmentStatus.WITHDRAWN, TODAY, "Motivo privado", place, TODAY, NOW);

        assertThat(withdrawn.toString()).doesNotContain("Motivo privado").contains(withdrawn.id().toString());
    }
}

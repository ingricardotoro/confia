package com.confia.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModalityGradeSectionTest {

    private static final Instant T0 = Instant.parse("2026-01-10T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-10T12:00:00Z");

    private static Modality modality() {
        return Modality.create(UUID.randomUUID(), " BACH ", " Bachillerato ", T0);
    }

    private static Grade grade(Modality modality) {
        return Grade.create(UUID.randomUUID(), modality, "Primero", 1, T0);
    }

    private static AcademicYear year(AcademicYearStatus status) {
        return new AcademicYear(UUID.randomUUID(), "2026", LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 11, 30), status, true, T0, T0);
    }

    // --- Modality ---

    @Test
    void aModalityStripsItsCodeAndNameAndIsBornActive() {
        Modality created = modality();

        assertThat(created.code()).isEqualTo("BACH");
        assertThat(created.name()).isEqualTo("Bachillerato");
        assertThat(created.active()).isTrue();
    }

    @Test
    void aModalityNeedsACodeAndANameWithinTheirColumns() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> Modality.create(id, " ", "Nombre", T0))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> Modality.create(id, "C".repeat(31), "Nombre", T0))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> Modality.create(id, "COD", "", T0))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> Modality.create(id, "COD", null, T0))
                .isInstanceOf(InvalidAcademicStructureException.class);
    }

    @Test
    void aModalityEditKeepsTheCreationInstantAndCanDeactivate() {
        Modality edited = modality().edit("BACH2", "Otro", false, T1);

        assertThat(edited.code()).isEqualTo("BACH2");
        assertThat(edited.active()).isFalse();
        assertThat(edited.createdAt()).isEqualTo(T0);
        assertThat(edited.updatedAt()).isEqualTo(T1);
    }

    // --- Grade ---

    @Test
    void aGradeBelongsToTheModalityItWasCreatedWith() {
        Modality modality = modality();

        Grade created = grade(modality);

        assertThat(created.modalityId()).isEqualTo(modality.id());
        assertThat(created.active()).isTrue();
    }

    @Test
    void aGradeNeedsAnActiveModality() {
        Modality inactive = modality().edit("BACH", "Bachillerato", false, T1);

        assertThatThrownBy(() -> Grade.create(UUID.randomUUID(), inactive, "Primero", 1, T1))
                .isInstanceOf(InactiveReferenceException.class);
    }

    @Test
    void anOrdinalBelowZeroIsRefusedAndZeroIsAllowed() {
        Modality modality = modality();

        assertThatThrownBy(() -> Grade.create(UUID.randomUUID(), modality, "Primero", -1, T0))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThat(Grade.create(UUID.randomUUID(), modality, "Pre", 0, T0).ordinal()).isZero();
    }

    @Test
    void aGradeEditKeepsItsModality() {
        Modality modality = modality();
        Grade edited = grade(modality).edit("Segundo", 2, false, T1);

        assertThat(edited.modalityId()).isEqualTo(modality.id());
        assertThat(edited.ordinal()).isEqualTo(2);
        assertThat(edited.active()).isFalse();
    }

    // --- Section ---

    @Test
    void aSectionIsCreatedInAnOpenYearForAnActiveGrade() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality());

        Section created = Section.create(UUID.randomUUID(), year, grade, " A ", 30, T0);

        assertThat(created.academicYearId()).isEqualTo(year.id());
        assertThat(created.gradeId()).isEqualTo(grade.id());
        assertThat(created.name()).isEqualTo("A");
        assertThat(created.capacity()).isEqualTo(30);
        assertThat(created.active()).isTrue();
    }

    @Test
    void theCapacityIsOptional() {
        Section created = Section.create(UUID.randomUUID(), year(AcademicYearStatus.PLANNED),
                grade(modality()), "A", null, T0);

        assertThat(created.capacity()).isNull();
    }

    @Test
    void aCapacityOfZeroOrBelowIsRefusedAndOneIsAllowed() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality());

        assertThatThrownBy(() -> Section.create(UUID.randomUUID(), year, grade, "A", 0, T0))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> Section.create(UUID.randomUUID(), year, grade, "A", -5, T0))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThat(Section.create(UUID.randomUUID(), year, grade, "A", 1, T0).capacity()).isEqualTo(1);
    }

    @Test
    void aSectionCannotBeCreatedInAClosedYear() {
        AcademicYear closed = year(AcademicYearStatus.CLOSED);

        assertThatThrownBy(() -> Section.create(UUID.randomUUID(), closed, grade(modality()), "A", 10, T0))
                .isInstanceOf(AcademicYearClosedException.class);
    }

    @Test
    void aSectionCannotBeCreatedForAnInactiveGrade() {
        Grade inactive = grade(modality()).edit("Primero", 1, false, T1);

        assertThatThrownBy(() -> Section.create(UUID.randomUUID(), year(AcademicYearStatus.ACTIVE),
                inactive, "A", 10, T1)).isInstanceOf(InactiveReferenceException.class);
    }

    @Test
    void aSectionAlreadyCreatedKeepsWorkingAfterItsGradeIsDeactivated() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Section created = Section.create(UUID.randomUUID(), year, grade(modality()), "A", 10, T0);

        // The grade is deactivated afterwards: nothing in the section depends on it any more.
        Section edited = created.edit(year, "A", 25, true, T1);

        assertThat(edited.capacity()).isEqualTo(25);
        assertThat(edited.active()).isTrue();
        assertThat(edited.gradeId()).isEqualTo(created.gradeId());
        assertThat(edited.academicYearId()).isEqualTo(created.academicYearId());
        assertThat(edited.createdAt()).isEqualTo(T0);
        assertThat(edited.updatedAt()).isEqualTo(T1);
    }

    @Test
    void aSectionOfAClosedYearIsNotEdited() {
        AcademicYear open = year(AcademicYearStatus.ACTIVE);
        Section created = Section.create(UUID.randomUUID(), open, grade(modality()), "A", 10, T0);
        AcademicYear closed = new AcademicYear(open.id(), open.name(), open.startDate(), open.endDate(),
                AcademicYearStatus.CLOSED, true, T0, T1);

        assertThatThrownBy(() -> created.edit(closed, "A", 20, true, T1))
                .isInstanceOf(AcademicYearClosedException.class);
    }

    @Test
    void theDomainExceptionsCarryTheCodesOfTheProblemCatalog() {
        assertThat(new AcademicYearOverlapException().code()).isEqualTo("academic-year-overlap");
        assertThat(new AcademicYearAlreadyActiveException().code()).isEqualTo("academic-year-already-active");
        assertThat(new AcademicYearClosedException().code()).isEqualTo("academic-year-closed");
        assertThat(new DuplicateNameException().code()).isEqualTo("duplicate-name");
        assertThat(new ResourceNotFoundException().code()).isEqualTo("resource-not-found");
        assertThat(new InactiveReferenceException().code()).isEqualTo("inactive-reference");
        assertThat(new InvalidAcademicStructureException().code()).isEqualTo("validation-failed");
    }
}

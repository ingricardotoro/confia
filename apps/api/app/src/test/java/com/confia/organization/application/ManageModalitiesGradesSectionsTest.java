package com.confia.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.organization.domain.AcademicYear;
import com.confia.organization.domain.AcademicYearClosedException;
import com.confia.organization.domain.AcademicYearStatus;
import com.confia.organization.domain.DuplicateNameException;
import com.confia.organization.domain.Grade;
import com.confia.organization.domain.InactiveReferenceException;
import com.confia.organization.domain.InvalidAcademicStructureException;
import com.confia.organization.domain.Modality;
import com.confia.organization.domain.ResourceNotFoundException;
import com.confia.organization.domain.Section;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageModalitiesGradesSectionsTest extends UseCaseFixture {

    private static final Instant EARLIER = NOW.minusSeconds(86_400);

    private final ManageModalities modalities =
            new ManageModalities(runner, store.modalities, auditWriter, clock);
    private final ManageGrades grades =
            new ManageGrades(runner, store.grades, store.modalities, auditWriter, clock);
    private final ManageSections sections = new ManageSections(runner, store.sections, store.years,
            store.grades, auditWriter, clock);

    private Modality modality(boolean active) {
        Modality modality = new Modality(UUID.randomUUID(), "BACH", "Bachillerato", active, EARLIER, EARLIER);
        store.arrange(institution, modality);
        return modality;
    }

    private Grade grade(Modality modality, String name, int ordinal, boolean active) {
        Grade grade = new Grade(UUID.randomUUID(), modality.id(), name, ordinal, active, EARLIER, EARLIER);
        store.arrange(institution, grade);
        return grade;
    }

    private AcademicYear year(AcademicYearStatus status) {
        AcademicYear year = new AcademicYear(UUID.randomUUID(), "2026", LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 11, 30), status, true, EARLIER, EARLIER);
        store.arrange(institution, year);
        return year;
    }

    // --- Modality ---

    @Test
    void creatingAModalityStoresItAndWritesOneCreatedEntry() {
        ModalityView view = modalities.create(actor, requestId, "BACH", "Bachillerato");

        assertThat(view.active()).isTrue();
        assertThat(view.code()).isEqualTo("BACH");
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("organization.modality.created");
            assertThat(entry.entityType()).isEqualTo("organization.modality");
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue())
                    .isEqualTo("{\"code\":\"BACH\",\"name\":\"Bachillerato\",\"isActive\":true}");
        });
        assertThat(committed()).isTrue();
    }

    @Test
    void aCodeAlreadyTakenIsADuplicateAndLeavesNoEntry() {
        modalities.create(actor, requestId, "BACH", "Bachillerato");
        audit.clear();

        assertThatThrownBy(() -> modalities.create(actor, requestId, "BACH", "Otro"))
                .isInstanceOf(DuplicateNameException.class);

        assertThat(audit).isEmpty();
        assertThat(store.modalityRows).hasSize(1);
    }

    @Test
    void theSameCodeInAnotherInstitutionIsNotADuplicate() {
        store.arrange(new InstitutionId(UUID.randomUUID()),
                new Modality(UUID.randomUUID(), "BACH", "Otra", true, EARLIER, EARLIER));

        assertThat(modalities.create(actor, requestId, "BACH", "Bachillerato").code()).isEqualTo("BACH");
    }

    @Test
    void deactivatingAModalityWritesBeforeAndAfterAndTheListStillShowsItAsInactive() {
        Modality modality = modality(true);

        ModalityView view = modalities.update(actor, requestId, modality.id(), "BACH", "Bachillerato", false);

        assertThat(view.active()).isFalse();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("organization.modality.updated");
            assertThat(entry.beforeValue()).contains("\"isActive\":true");
            assertThat(entry.afterValue()).contains("\"isActive\":false");
        });
        assertThat(modalities.list(actor, requestId, 0, 50, null).items())
                .singleElement().extracting(ModalityView::active).isEqualTo(false);
    }

    @Test
    void anUnknownModalityIsNotFound() {
        assertThatThrownBy(() -> modalities.update(actor, requestId, UUID.randomUUID(), "X", "X", true))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void theModalityListRefusesAFieldOutsideItsSet() {
        assertThatThrownBy(() -> modalities.list(actor, requestId, 0, 50, "startDate"))
                .isInstanceOf(InvalidAcademicStructureException.class);
    }

    // --- Grade ---

    @Test
    void creatingAGradeInAnActiveModalityWritesOneCreatedEntry() {
        Modality modality = modality(true);

        GradeView view = grades.create(actor, requestId, modality.id(), "Primero", 1);

        assertThat(view.modalityId()).isEqualTo(modality.id());
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("organization.grade.created");
            assertThat(entry.afterValue()).isEqualTo("{\"modalityId\":\"" + modality.id()
                    + "\",\"name\":\"Primero\",\"ordinal\":1,\"isActive\":true}");
        });
    }

    @Test
    void aGradeInAnInactiveModalityIsAnInactiveReference() {
        Modality inactive = modality(false);

        assertThatThrownBy(() -> grades.create(actor, requestId, inactive.id(), "Primero", 1))
                .isInstanceOf(InactiveReferenceException.class);
        assertThat(audit).isEmpty();
        assertThat(store.gradeRows).isEmpty();
    }

    @Test
    void aGradeInAMissingModalityIsNotFound() {
        assertThatThrownBy(() -> grades.create(actor, requestId, UUID.randomUUID(), "Primero", 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aDuplicateOrdinalOrNameInTheSameModalityIsRefusedButAnotherModalityMayRepeatThem() {
        Modality modality = modality(true);
        grade(modality, "Primero", 1, true);
        Modality other = new Modality(UUID.randomUUID(), "TEC", "Tecnico", true, EARLIER, EARLIER);
        store.arrange(institution, other);

        assertThatThrownBy(() -> grades.create(actor, requestId, modality.id(), "Segundo", 1))
                .as("same ordinal").isInstanceOf(DuplicateNameException.class);
        assertThatThrownBy(() -> grades.create(actor, requestId, modality.id(), "Primero", 2))
                .as("same name").isInstanceOf(DuplicateNameException.class);
        assertThat(grades.create(actor, requestId, other.id(), "Primero", 1).ordinal()).isEqualTo(1);
        assertThat(audit).hasSize(1);
    }

    @Test
    void anOrdinalBelowZeroIsRefused() {
        Modality modality = modality(true);

        assertThatThrownBy(() -> grades.create(actor, requestId, modality.id(), "Primero", -1))
                .isInstanceOf(InvalidAcademicStructureException.class);
    }

    @Test
    void deactivatingAGradeWritesBeforeAndAfterAndKeepsItsModality() {
        Modality modality = modality(true);
        Grade grade = grade(modality, "Primero", 1, true);

        GradeView view = grades.update(actor, requestId, grade.id(), "Primero", 1, false);

        assertThat(view.active()).isFalse();
        assertThat(view.modalityId()).isEqualTo(modality.id());
        assertThat(audit).singleElement().extracting(e -> e.action())
                .isEqualTo("organization.grade.updated");
    }

    @Test
    void theGradeListFiltersByModality() {
        Modality modality = modality(true);
        Modality other = new Modality(UUID.randomUUID(), "TEC", "Tecnico", true, EARLIER, EARLIER);
        store.arrange(institution, other);
        grade(modality, "Primero", 1, true);
        grade(other, "Primero", 1, true);

        assertThat(grades.list(actor, requestId, modality.id(), 0, 50, null).items()).hasSize(1);
        assertThat(grades.list(actor, requestId, null, 0, 50, "name").items()).hasSize(2);
        assertThat(store.lastRequest).isEqualTo(new PageRequest(0, 50, "name", true));
    }

    // --- Section ---

    @Test
    void creatingASectionWritesOneCreatedEntryWithTheCapacity() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);

        SectionView view = sections.create(actor, requestId, year.id(), grade.id(), "A", 30);

        assertThat(view.capacity()).isEqualTo(30);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("organization.section.created");
            assertThat(entry.afterValue()).isEqualTo("{\"academicYearId\":\"" + year.id()
                    + "\",\"gradeId\":\"" + grade.id() + "\",\"name\":\"A\",\"capacity\":30,"
                    + "\"isActive\":true}");
        });
    }

    @Test
    void aSectionWithoutCapacityIsStoredWithNoCapacityMember() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);

        SectionView view = sections.create(actor, requestId, year.id(), grade.id(), "A", null);

        assertThat(view.capacity()).isNull();
        assertThat(audit.get(0).afterValue()).doesNotContain("capacity");
    }

    @Test
    void aCapacityOfZeroOrBelowIsRefusedWithNoRowAndNoEntry() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);

        assertThatThrownBy(() -> sections.create(actor, requestId, year.id(), grade.id(), "A", 0))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> sections.create(actor, requestId, year.id(), grade.id(), "A", -3))
                .isInstanceOf(InvalidAcademicStructureException.class);

        assertThat(store.sectionRows).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    void aSectionInAClosedYearIsRefused() {
        AcademicYear closed = year(AcademicYearStatus.CLOSED);
        Grade grade = grade(modality(true), "Primero", 1, true);

        assertThatThrownBy(() -> sections.create(actor, requestId, closed.id(), grade.id(), "A", 10))
                .isInstanceOf(AcademicYearClosedException.class);
        assertThat(store.sectionRows).isEmpty();
        assertThat(audit).isEmpty();
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void aSectionForAnInactiveGradeIsAnInactiveReference() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade inactive = grade(modality(true), "Primero", 1, false);

        assertThatThrownBy(() -> sections.create(actor, requestId, year.id(), inactive.id(), "A", 10))
                .isInstanceOf(InactiveReferenceException.class);
    }

    @Test
    void aMissingYearOrGradeIsNotFound() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);

        assertThatThrownBy(() -> sections.create(actor, requestId, UUID.randomUUID(), grade.id(), "A", 10))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> sections.create(actor, requestId, year.id(), UUID.randomUUID(), "A", 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSectionNameTakenInTheSameYearAndGradeIsADuplicate() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);
        Grade second = grade(store.modalityRows.values().iterator().next(), "Segundo", 2, true);
        sections.create(actor, requestId, year.id(), grade.id(), "A", 10);
        audit.clear();

        assertThatThrownBy(() -> sections.create(actor, requestId, year.id(), grade.id(), "A", 10))
                .isInstanceOf(DuplicateNameException.class);
        assertThat(sections.create(actor, requestId, year.id(), second.id(), "A", 10).name())
                .as("another grade may reuse the name").isEqualTo("A");
        assertThat(audit).hasSize(1);
    }

    @Test
    void deactivatingAGradeLeavesItsSectionsEditable() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);
        SectionView section = sections.create(actor, requestId, year.id(), grade.id(), "A", 10);
        grades.update(actor, requestId, grade.id(), "Primero", 1, false);
        audit.clear();

        SectionView edited = sections.update(actor, requestId, section.id(), "A", 25, true);

        assertThat(edited.capacity()).isEqualTo(25);
        assertThat(audit).singleElement().extracting(e -> e.action())
                .isEqualTo("organization.section.updated");
    }

    @Test
    void aSectionOfAClosedYearIsNotEditedAndDeactivatingASectionWritesBeforeAndAfter() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);
        SectionView section = sections.create(actor, requestId, year.id(), grade.id(), "A", 10);
        audit.clear();

        SectionView off = sections.update(actor, requestId, section.id(), "A", 10, false);
        assertThat(off.active()).isFalse();
        assertThat(audit.get(0).beforeValue()).contains("\"isActive\":true");
        assertThat(audit.get(0).afterValue()).contains("\"isActive\":false");

        store.arrange(institution, new AcademicYear(year.id(), "2026", year.startDate(), year.endDate(),
                AcademicYearStatus.CLOSED, true, EARLIER, EARLIER));
        audit.clear();
        assertThatThrownBy(() -> sections.update(actor, requestId, section.id(), "A", 20, true))
                .isInstanceOf(AcademicYearClosedException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void anUnknownSectionIsNotFoundAndTheListFiltersByYearAndGrade() {
        AcademicYear year = year(AcademicYearStatus.ACTIVE);
        Grade grade = grade(modality(true), "Primero", 1, true);
        Grade second = grade(store.modalityRows.values().iterator().next(), "Segundo", 2, true);
        sections.create(actor, requestId, year.id(), grade.id(), "A", 10);
        sections.create(actor, requestId, year.id(), second.id(), "A", 10);

        assertThatThrownBy(() -> sections.update(actor, requestId, UUID.randomUUID(), "A", 1, true))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(sections.list(actor, requestId, year.id(), null, 0, 50, null).items()).hasSize(2);
        assertThat(sections.list(actor, requestId, year.id(), grade.id(), 0, 50, "capacity,desc").items())
                .hasSize(1);
        assertThat(sections.list(actor, requestId, UUID.randomUUID(), null, 0, 50, null).items()).isEmpty();
        assertThat(store.lastRequest).isEqualTo(new PageRequest(0, 50, "name", true));
    }
}

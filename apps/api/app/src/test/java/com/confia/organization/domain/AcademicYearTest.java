package com.confia.organization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AcademicYearTest {

    private static final Instant T0 = Instant.parse("2026-01-10T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-02-10T12:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);

    private static AcademicYear year(AcademicYearStatus status) {
        return AcademicYear.create(UUID.randomUUID(), "  2026  ", START, END, status, T0);
    }

    @Test
    void aNewYearIsActiveStripsItsNameAndStampsBothInstants() {
        AcademicYear created = year(AcademicYearStatus.PLANNED);

        assertThat(created.name()).isEqualTo("2026");
        assertThat(created.status()).isEqualTo(AcademicYearStatus.PLANNED);
        assertThat(created.active()).isTrue();
        assertThat(created.createdAt()).isEqualTo(T0);
        assertThat(created.updatedAt()).isEqualTo(T0);
    }

    @Test
    void theEndMustBeStrictlyAfterTheStart() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> AcademicYear.create(id, "2026", START, START,
                AcademicYearStatus.PLANNED, T0)).isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> AcademicYear.create(id, "2026", START, START.minusDays(1),
                AcademicYearStatus.PLANNED, T0)).isInstanceOf(InvalidAcademicStructureException.class);
        assertThat(AcademicYear.create(id, "2026", START, START.plusDays(1),
                AcademicYearStatus.PLANNED, T0).endDate()).isEqualTo(START.plusDays(1));
    }

    @Test
    void theNameIsRequiredAndBoundedByItsColumn() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> AcademicYear.create(id, "   ", START, END,
                AcademicYearStatus.PLANNED, T0)).isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> AcademicYear.create(id, "x".repeat(121), START, END,
                AcademicYearStatus.PLANNED, T0)).isInstanceOf(InvalidAcademicStructureException.class);
        assertThat(AcademicYear.create(id, "x".repeat(120), START, END, AcademicYearStatus.PLANNED, T0)
                .name()).hasSize(120);
    }

    @Test
    void aYearCannotBeCreatedClosed() {
        assertThatThrownBy(() -> year(AcademicYearStatus.CLOSED))
                .isInstanceOf(AcademicYearClosedException.class);
    }

    @Test
    void anEditKeepsTheIdAndTheCreationInstantAndMovesTheUpdateInstant() {
        AcademicYear created = year(AcademicYearStatus.PLANNED);

        AcademicYear edited = created.edit("2026-B", START, END, AcademicYearStatus.ACTIVE, false, T1);

        assertThat(edited.id()).isEqualTo(created.id());
        assertThat(edited.name()).isEqualTo("2026-B");
        assertThat(edited.status()).isEqualTo(AcademicYearStatus.ACTIVE);
        assertThat(edited.active()).isFalse();
        assertThat(edited.createdAt()).isEqualTo(T0);
        assertThat(edited.updatedAt()).isEqualTo(T1);
    }

    @Test
    void aYearCanBeClosedFromActiveAndFromPlanned() {
        AcademicYear active = year(AcademicYearStatus.ACTIVE);
        AcademicYear planned = year(AcademicYearStatus.PLANNED);

        assertThat(active.edit("2026", START, END, AcademicYearStatus.CLOSED, true, T1).isClosed())
                .isTrue();
        assertThat(planned.edit("2026", START, END, AcademicYearStatus.CLOSED, true, T1).isClosed())
                .isTrue();
    }

    @Test
    void aClosedYearIsNeitherEditedNorReopenedNorDeactivated() {
        AcademicYear closed = year(AcademicYearStatus.ACTIVE)
                .edit("2026", START, END, AcademicYearStatus.CLOSED, true, T1);

        assertThatThrownBy(() -> closed.edit("2026", START, END, AcademicYearStatus.ACTIVE, true, T1))
                .isInstanceOf(AcademicYearClosedException.class);
        assertThatThrownBy(() -> closed.edit("2026", START, END, AcademicYearStatus.PLANNED, true, T1))
                .isInstanceOf(AcademicYearClosedException.class);
        assertThatThrownBy(() -> closed.edit("renamed", START, END, AcademicYearStatus.CLOSED, true, T1))
                .isInstanceOf(AcademicYearClosedException.class);
        assertThatThrownBy(() -> closed.edit("2026", START, END, AcademicYearStatus.CLOSED, false, T1))
                .isInstanceOf(AcademicYearClosedException.class);
    }

    @Test
    void anEditThatBreaksTheRangeIsRefused() {
        AcademicYear created = year(AcademicYearStatus.PLANNED);

        assertThatThrownBy(() -> created.edit("2026", END, START, AcademicYearStatus.PLANNED, true, T1))
                .isInstanceOf(InvalidAcademicStructureException.class);
    }

    @Test
    void sharingASingleDayIsAnOverlapInBothDirections() {
        AcademicYear created = year(AcademicYearStatus.PLANNED); // 2026-02-01 .. 2026-11-30

        assertThat(created.overlaps(END, END.plusDays(300))).as("starts the day this one ends").isTrue();
        assertThat(created.overlaps(START.minusDays(300), START))
                .as("ends the day this one starts").isTrue();
    }

    @Test
    void aYearThatStartsTheDayAfterThisOneEndsDoesNotOverlap() {
        AcademicYear created = year(AcademicYearStatus.PLANNED);

        assertThat(created.overlaps(END.plusDays(1), END.plusDays(300))).isFalse();
        assertThat(created.overlaps(START.minusDays(300), START.minusDays(1))).isFalse();
    }

    @Test
    void containedAndContainingRangesOverlap() {
        AcademicYear created = year(AcademicYearStatus.PLANNED);

        assertThat(created.overlaps(START.plusDays(10), END.minusDays(10))).isTrue();
        assertThat(created.overlaps(START.minusDays(10), END.plusDays(10))).isTrue();
    }
}

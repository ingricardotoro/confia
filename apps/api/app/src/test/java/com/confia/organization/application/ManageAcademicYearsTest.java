package com.confia.organization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.organization.domain.AcademicYear;
import com.confia.organization.domain.AcademicYearAlreadyActiveException;
import com.confia.organization.domain.AcademicYearClosedException;
import com.confia.organization.domain.AcademicYearOverlapException;
import com.confia.organization.domain.AcademicYearStatus;
import com.confia.organization.domain.DuplicateNameException;
import com.confia.organization.domain.InvalidAcademicStructureException;
import com.confia.organization.domain.ResourceNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageAcademicYearsTest extends UseCaseFixture {

    private static final LocalDate START = LocalDate.of(2026, 2, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 30);

    private final ManageAcademicYears useCase =
            new ManageAcademicYears(runner, store.years, auditWriter, clock);

    private AcademicYear arrange(String name, LocalDate start, LocalDate end, AcademicYearStatus status) {
        Instant before = NOW.minusSeconds(86_400);
        AcademicYear year = new AcademicYear(UUID.randomUUID(), name, start, end, status, true, before,
                before);
        store.arrange(institution, year);
        return year;
    }

    @Test
    void creatingAYearStoresItAsPlannedAndWritesOneCreatedEntryInTheSameTransaction() {
        AcademicYearView view = useCase.create(actor, requestId, " 2026 ", START, END, null);

        assertThat(view.name()).isEqualTo("2026");
        assertThat(view.status()).isEqualTo("PLANNED");
        assertThat(view.active()).isTrue();
        assertThat(view.createdAt()).isEqualTo(NOW);
        assertThat(store.yearRows).hasSize(1);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("organization.academic_year.created");
            assertThat(entry.entityType()).isEqualTo("organization.academic_year");
            assertThat(entry.entityId()).isEqualTo(view.id().toString());
            assertThat(entry.institutionId()).isEqualTo(institutionUuid);
            assertThat(entry.actorId()).isEqualTo(actor.accountId());
            assertThat(entry.actorKind()).isEqualTo("staff");
            assertThat(entry.requestId()).hasToString(requestId);
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(entry.beforeValue()).isNull();
            assertThat(entry.afterValue()).isEqualTo("{\"name\":\"2026\",\"startDate\":\"2026-02-01\","
                    + "\"endDate\":\"2026-11-30\",\"status\":\"PLANNED\",\"isActive\":true}");
        });
        assertThat(committed()).isTrue();
    }

    @Test
    void theOverlapIsCheckedAfterTheLockOfTheInstitution() {
        useCase.create(actor, requestId, "2026", START, END, null);

        assertThat(store.events).containsExactly("lock", "findOverlapping");
    }

    @Test
    void aYearThatSharesASingleDayWithAnotherIsRefusedWithNoRowAndNoEntry() {
        arrange("2025", LocalDate.of(2025, 2, 1), LocalDate.of(2025, 11, 30), AcademicYearStatus.CLOSED);
        arrange("2026", START, END, AcademicYearStatus.PLANNED);

        assertThatThrownBy(() -> useCase.create(actor, requestId, "2027", END, END.plusDays(300), null))
                .isInstanceOf(AcademicYearOverlapException.class);
        assertThatThrownBy(() -> useCase.create(actor, requestId, "2024", START.minusDays(300), START,
                null)).isInstanceOf(AcademicYearOverlapException.class);

        assertThat(store.yearRows).hasSize(2);
        assertThat(audit).isEmpty();
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void aYearThatStartsTheDayAfterAnotherEndsIsCreated() {
        arrange("2026", START, END, AcademicYearStatus.PLANNED);

        AcademicYearView next = useCase.create(actor, requestId, "2027", END.plusDays(1),
                END.plusDays(300), null);

        assertThat(next.startDate()).isEqualTo(END.plusDays(1));
        assertThat(store.yearRows).hasSize(2);
    }

    @Test
    void theRangesOfAnotherInstitutionNeverOverlap() {
        store.arrange(new com.confia.kernel.InstitutionId(UUID.randomUUID()),
                new AcademicYear(UUID.randomUUID(), "2026", START, END, AcademicYearStatus.ACTIVE, true,
                        NOW, NOW));

        AcademicYearView view = useCase.create(actor, requestId, "2026", START, END, "ACTIVE");

        assertThat(view.status()).isEqualTo("ACTIVE");
    }

    @Test
    void aSecondActiveYearIsRefusedOnCreate() {
        arrange("2026", START, END, AcademicYearStatus.ACTIVE);

        assertThatThrownBy(() -> useCase.create(actor, requestId, "2027", END.plusDays(1),
                END.plusDays(300), "ACTIVE")).isInstanceOf(AcademicYearAlreadyActiveException.class);

        assertThat(store.yearRows).hasSize(1);
        assertThat(audit).isEmpty();
    }

    @Test
    void aSecondActiveYearIsRefusedOnUpdateAndTheYearItselfIsNotItsOwnObstacle() {
        AcademicYear active = arrange("2026", START, END, AcademicYearStatus.ACTIVE);
        AcademicYear planned = arrange("2027", END.plusDays(1), END.plusDays(300),
                AcademicYearStatus.PLANNED);

        assertThatThrownBy(() -> useCase.update(actor, requestId, planned.id(), "2027", planned.startDate(),
                planned.endDate(), "ACTIVE", true)).isInstanceOf(AcademicYearAlreadyActiveException.class);

        AcademicYearView renamed = useCase.update(actor, requestId, active.id(), "2026 A", START, END,
                "ACTIVE", true);
        assertThat(renamed.name()).isEqualTo("2026 A");
    }

    @Test
    void closingTheActiveYearLetsAnotherBeActivated() {
        AcademicYear active = arrange("2026", START, END, AcademicYearStatus.ACTIVE);
        AcademicYear planned = arrange("2027", END.plusDays(1), END.plusDays(300),
                AcademicYearStatus.PLANNED);

        useCase.update(actor, requestId, active.id(), "2026", START, END, "CLOSED", true);
        AcademicYearView activated = useCase.update(actor, requestId, planned.id(), "2027",
                planned.startDate(), planned.endDate(), "ACTIVE", true);

        assertThat(activated.status()).isEqualTo("ACTIVE");
    }

    @Test
    void anUpdateWritesOneEntryWithTheStateBeforeAndAfter() {
        AcademicYear planned = arrange("2026", START, END, AcademicYearStatus.PLANNED);

        AcademicYearView view = useCase.update(actor, requestId, planned.id(), "2026 B", START,
                END.minusDays(1), "ACTIVE", false);

        assertThat(view.updatedAt()).isEqualTo(NOW);
        assertThat(view.createdAt()).isEqualTo(planned.createdAt());
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("organization.academic_year.updated");
            assertThat(entry.entityId()).isEqualTo(planned.id().toString());
            assertThat(entry.beforeValue()).isEqualTo("{\"name\":\"2026\",\"startDate\":\"2026-02-01\","
                    + "\"endDate\":\"2026-11-30\",\"status\":\"PLANNED\",\"isActive\":true}");
            assertThat(entry.afterValue()).isEqualTo("{\"name\":\"2026 B\",\"startDate\":\"2026-02-01\","
                    + "\"endDate\":\"2026-11-29\",\"status\":\"ACTIVE\",\"isActive\":false}");
        });
        assertThat(committed()).isTrue();
    }

    @Test
    void aClosedYearIsNeitherEditedNorReopenedAndLeavesNoEntry() {
        AcademicYear closed = arrange("2025", LocalDate.of(2025, 2, 1), LocalDate.of(2025, 11, 30),
                AcademicYearStatus.CLOSED);

        assertThatThrownBy(() -> useCase.update(actor, requestId, closed.id(), "2025",
                closed.startDate(), closed.endDate(), "ACTIVE", true))
                .isInstanceOf(AcademicYearClosedException.class);
        assertThatThrownBy(() -> useCase.update(actor, requestId, closed.id(), "renamed",
                closed.startDate(), closed.endDate(), "CLOSED", true))
                .isInstanceOf(AcademicYearClosedException.class);

        assertThat(store.yearRows.values()).containsExactly(closed);
        assertThat(audit).isEmpty();
        assertThat(rolledBack()).isTrue();
    }

    @Test
    void aYearCannotBeCreatedClosed() {
        assertThatThrownBy(() -> useCase.create(actor, requestId, "2026", START, END, "CLOSED"))
                .isInstanceOf(AcademicYearClosedException.class);
    }

    @Test
    void anEditThatMovesTheRangeOntoAnotherYearIsRefusedButOneInsideItsOwnRangeIsNot() {
        AcademicYear first = arrange("2026", START, END, AcademicYearStatus.PLANNED);
        arrange("2027", END.plusDays(1), END.plusDays(300), AcademicYearStatus.PLANNED);

        assertThatThrownBy(() -> useCase.update(actor, requestId, first.id(), "2026", START,
                END.plusDays(1), "PLANNED", true)).isInstanceOf(AcademicYearOverlapException.class);

        assertThat(useCase.update(actor, requestId, first.id(), "2026", START, END, "PLANNED", true)
                .endDate()).isEqualTo(END);
    }

    @Test
    void aNameTakenByAnotherYearIsADuplicate() {
        arrange("2026", START, END, AcademicYearStatus.PLANNED);

        assertThatThrownBy(() -> useCase.create(actor, requestId, "2026", END.plusDays(1),
                END.plusDays(300), null)).isInstanceOf(DuplicateNameException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void aMissingYearOrOneOfAnotherInstitutionIsNotFound() {
        store.arrange(new com.confia.kernel.InstitutionId(UUID.randomUUID()),
                new AcademicYear(UUID.randomUUID(), "2026", START, END, AcademicYearStatus.PLANNED, true,
                        NOW, NOW));
        UUID foreign = store.yearRows.keySet().iterator().next().id();

        assertThatThrownBy(() -> useCase.update(actor, requestId, UUID.randomUUID(), "x", START, END,
                "PLANNED", true)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> useCase.update(actor, requestId, foreign, "x", START, END, "PLANNED",
                true)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(audit).isEmpty();
    }

    @Test
    void aStatusOutsideTheThreeIsRefused() {
        assertThatThrownBy(() -> useCase.create(actor, requestId, "2026", START, END, "OPEN"))
                .isInstanceOf(InvalidAcademicStructureException.class);
    }

    @Test
    void aBlankRequestIdGetsAFreshOneInTheEntry() {
        useCase.create(actor, "", "2026", START, END, null);

        assertThat(audit.get(0).requestId()).isNotNull();
    }

    @Test
    void listingReadsInAReadOnlyTransactionWithTheDefaultsAndTheLimits() {
        arrange("2026", START, END, AcademicYearStatus.PLANNED);

        Page<AcademicYearView> page = useCase.list(actor, requestId, 0, 50, null);

        assertThat(page.items()).hasSize(1);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(50);
        assertThat(store.lastRequest).isEqualTo(new PageRequest(0, 50, "startDate", false));
        assertThat(audit).isEmpty();
    }

    @Test
    void listingKeepsTheSortFieldAndDirectionAndRefusesWhatIsNotAllowed() {
        useCase.list(actor, requestId, 2, 10, "name,desc");
        assertThat(store.lastRequest).isEqualTo(new PageRequest(2, 10, "name", false));

        assertThatThrownBy(() -> useCase.list(actor, requestId, 0, 50, "password"))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> useCase.list(actor, requestId, 0, 201, null))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> useCase.list(actor, requestId, 0, 0, null))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> useCase.list(actor, requestId, -1, 10, null))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> useCase.list(actor, requestId, 0, 50, "name;drop"))
                .isInstanceOf(InvalidAcademicStructureException.class);
        assertThatThrownBy(() -> useCase.list(actor, requestId, 0, 50, "name,sideways"))
                .isInstanceOf(InvalidAcademicStructureException.class);
    }

    @Test
    void theMaximumSizeIsAccepted() {
        useCase.list(actor, requestId, 0, 200, "startDate");

        assertThat(store.lastRequest.size()).isEqualTo(200);
        assertThat(store.lastRequest.ascending()).isTrue();
    }
}

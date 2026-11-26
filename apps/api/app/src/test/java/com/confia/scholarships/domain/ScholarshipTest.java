package com.confia.scholarships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The scholarship: its code, name, concepts, edit and deactivation (docs/cortes/scholarships-awards.md). */
class ScholarshipTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final Instant LATER = Instant.parse("2027-02-11T18:00:00Z");
    private static final UUID CONCEPT = UUID.randomUUID();
    private static final UUID OTHER_CONCEPT = UUID.randomUUID();

    private static Scholarship scholarship() {
        return Scholarship.create(UUID.randomUUID(), "BECA-25", "Beca del 25 %", Benefit.percentage("25"), null,
                Set.of(CONCEPT), UUID.randomUUID(), NOW);
    }

    @Test
    void aScholarshipIsBornActiveAtVersionZero() {
        Scholarship created = scholarship();

        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.covers(CONCEPT)).isTrue();
        assertThat(created.covers(OTHER_CONCEPT)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-A", "A B", "A​B", "=SUM", "ÁB", "A234567890123456789012345678901"})
    void aCodeOutOfTheWhitelistIsRefused(String code) {
        assertThatThrownBy(() -> Scholarship.create(UUID.randomUUID(), code, "Nombre", Benefit.percentage("5"), null,
                Set.of(CONCEPT), UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("code");
    }

    @Test
    void aNullCodeAndABlankOrControlNameAreRefused() {
        assertThatThrownBy(() -> Scholarship.create(UUID.randomUUID(), null, "N", Benefit.percentage("5"), null,
                Set.of(CONCEPT), UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
        for (String name : new String[] {null, "", "   ", "a\nb", "a‮b", "x".repeat(121)}) {
            assertThatThrownBy(() -> Scholarship.create(UUID.randomUUID(), "C1", name, Benefit.percentage("5"), null,
                    Set.of(CONCEPT), UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class)
                    .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("name");
        }
        assertThat(Scholarship.create(UUID.randomUUID(), "C1", "  Con bordes  ", Benefit.percentage("5"), null,
                Set.of(CONCEPT), UUID.randomUUID(), NOW).name()).isEqualTo("Con bordes");
        assertThat(Scholarship.create(UUID.randomUUID(), "C1", "x".repeat(120), Benefit.percentage("5"), null,
                Set.of(CONCEPT), UUID.randomUUID(), NOW).name()).hasSize(120);
    }

    @Test
    void aScholarshipCoversAtLeastOneConceptAndNeverANullOne() {
        for (Set<UUID> concepts : new Set[] {null, Set.of(), new HashSet<>(java.util.Arrays.asList(CONCEPT, null))}) {
            assertThatThrownBy(() -> Scholarship.create(UUID.randomUUID(), "C1", "N", Benefit.percentage("5"), null,
                    concepts, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class)
                    .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("concepts");
        }
    }

    @Test
    void aNegativeVersionIsRefused() {
        Scholarship base = scholarship();
        assertThatThrownBy(() -> new Scholarship(base.id(), "C1", "N", base.benefit(), null, base.concepts(), true, -1,
                base.createdBy(), NOW, NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void editChangesNameBenefitCapAndConceptsAndAdvancesTheVersionButKeepsTheCode() {
        Scholarship before = scholarship();

        Scholarship after = before.edit(0, "Otro nombre", Benefit.fixed("300.00"), Benefit.cap("250.00"),
                Set.of(CONCEPT, OTHER_CONCEPT), false, LATER);

        assertThat(after.code()).isEqualTo("BECA-25");
        assertThat(after.name()).isEqualTo("Otro nombre");
        assertThat(after.benefit().type()).isEqualTo(BenefitType.FIXED_AMOUNT);
        assertThat(after.periodCap()).isNotNull();
        assertThat(after.concepts()).containsExactlyInAnyOrder(CONCEPT, OTHER_CONCEPT);
        assertThat(after.version()).isEqualTo(1);
        assertThat(after.createdAt()).isEqualTo(NOW);
        assertThat(after.updatedAt()).isEqualTo(LATER);
        assertThat(after.active()).isTrue();
    }

    @Test
    void editWithAnOldVersionIsStale() {
        Scholarship before = scholarship();

        assertThatThrownBy(() -> before.edit(1, "N", before.benefit(), null, before.concepts(), false, LATER))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> before.edit(-1, "N", before.benefit(), null, before.concepts(), false, LATER))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void theStaleVersionIsReportedBeforeALockedBenefit() {
        Scholarship before = scholarship();

        assertThatThrownBy(() -> before.edit(5, "N", Benefit.percentage("30"), null, before.concepts(), true, LATER))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void withApprovedAwardsTheTypeAndTheValueOfTheBenefitDoNotChange() {
        Scholarship before = scholarship();

        assertThatThrownBy(() -> before.edit(0, "N", Benefit.percentage("30"), null, before.concepts(), true, LATER))
                .isInstanceOf(ScholarshipBenefitLockedException.class);
        assertThatThrownBy(() -> before.edit(0, "N", Benefit.fixed("25.00"), null, before.concepts(), true, LATER))
                .isInstanceOf(ScholarshipBenefitLockedException.class);
    }

    @Test
    void withApprovedAwardsTheNameStillChangesIfTheBenefitIsTheSame() {
        Scholarship before = scholarship();

        Scholarship after = before.edit(0, "Nuevo nombre", Benefit.percentage("25.00"), null, before.concepts(), true, LATER);

        assertThat(after.name()).isEqualTo("Nuevo nombre");
        assertThat(after.version()).isEqualTo(1);
    }

    @Test
    void withApprovedAwardsTheCapMayOnlyGoDown() {
        Scholarship capped = scholarship().edit(0, "N", Benefit.percentage("25"), Benefit.cap("500.00"), Set.of(CONCEPT), false, LATER);

        Scholarship lower = capped.edit(1, "N", Benefit.percentage("25"), Benefit.cap("400.00"), Set.of(CONCEPT), true, LATER);
        Scholarship same = capped.edit(1, "N", Benefit.percentage("25"), Benefit.cap("500.00"), Set.of(CONCEPT), true, LATER);

        assertThat(lower.periodCap()).isEqualTo(Benefit.cap("400.00"));
        assertThat(same.periodCap()).isEqualTo(Benefit.cap("500.00"));
    }

    @Test
    void withApprovedAwardsTheCapIsNeverRaisedNorRemoved() {
        Scholarship capped = scholarship().edit(0, "N", Benefit.percentage("25"), Benefit.cap("500.00"), Set.of(CONCEPT), false, LATER);

        assertThatThrownBy(() -> capped.edit(1, "N", Benefit.percentage("25"), Benefit.cap("500.01"), Set.of(CONCEPT), true, LATER))
                .isInstanceOf(ScholarshipScopeLockedException.class);
        assertThatThrownBy(() -> capped.edit(1, "N", Benefit.percentage("25"), null, Set.of(CONCEPT), true, LATER))
                .isInstanceOf(ScholarshipScopeLockedException.class);
    }

    @Test
    void withApprovedAwardsACapCanBeSetOnAScholarshipThatHadNone() {
        Scholarship before = scholarship();

        Scholarship after = before.edit(0, "N", Benefit.percentage("25"), Benefit.cap("100.00"), Set.of(CONCEPT), true, LATER);

        assertThat(after.periodCap()).isEqualTo(Benefit.cap("100.00"));
    }

    @Test
    void withApprovedAwardsConceptsMayOnlyBeRemoved() {
        Scholarship two = scholarship().edit(0, "N", Benefit.percentage("25"), null, Set.of(CONCEPT, OTHER_CONCEPT), false, LATER);

        Scholarship fewer = two.edit(1, "N", Benefit.percentage("25"), null, Set.of(CONCEPT), true, LATER);

        assertThat(fewer.concepts()).containsExactly(CONCEPT);
        assertThatThrownBy(() -> fewer.edit(2, "N", Benefit.percentage("25"), null, Set.of(CONCEPT, OTHER_CONCEPT), true, LATER))
                .isInstanceOf(ScholarshipScopeLockedException.class);
        assertThatThrownBy(() -> fewer.edit(2, "N", Benefit.percentage("25"), null, Set.of(OTHER_CONCEPT), true, LATER))
                .isInstanceOf(ScholarshipScopeLockedException.class);
    }

    @Test
    void withoutApprovedAwardsTheCapAndTheConceptsChangeFreely() {
        Scholarship capped = scholarship().edit(0, "N", Benefit.percentage("25"), Benefit.cap("100.00"), Set.of(CONCEPT), false, LATER);

        Scholarship after = capped.edit(1, "N", Benefit.percentage("25"), null, Set.of(OTHER_CONCEPT, CONCEPT), false, LATER);

        assertThat(after.periodCap()).isNull();
        assertThat(after.concepts()).containsExactlyInAnyOrder(CONCEPT, OTHER_CONCEPT);
    }

    @Test
    void theLockedScopeIsAConflictWithItsOwnCode() {
        assertThat(new ScholarshipScopeLockedException().code()).isEqualTo("scholarship-scope-locked");
    }

    @Test
    void deactivatingAndReactivatingAdvanceTheVersionOnlyWhenTheStateChanges() {
        Scholarship active = scholarship();

        Scholarship inactive = active.withActive(false, LATER);
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(1);
        assertThat(inactive.updatedAt()).isEqualTo(LATER);

        assertThat(inactive.withActive(false, LATER)).isSameAs(inactive);
        assertThat(active.withActive(true, LATER)).isSameAs(active);

        Scholarship again = inactive.withActive(true, LATER);
        assertThat(again.active()).isTrue();
        assertThat(again.version()).isEqualTo(2);
    }

    @Test
    void anInactiveScholarshipIsNotAReferenceForWhatIsNew() {
        scholarship().requireActive();

        assertThatThrownBy(() -> scholarship().withActive(false, LATER).requireActive())
                .isInstanceOf(InactiveReferenceException.class);
    }

    @Test
    void aZeroCapIsRefusedByTheRecordToo() {
        Scholarship base = scholarship();
        assertThatThrownBy(() -> new Scholarship(base.id(), "C1", "N", base.benefit(),
                com.confia.kernel.Money.zero(Benefit.CURRENCY), base.concepts(), true, 0, base.createdBy(), NOW, NOW))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("periodCap");
    }
}

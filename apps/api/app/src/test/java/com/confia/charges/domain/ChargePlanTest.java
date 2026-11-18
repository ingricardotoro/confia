package com.confia.charges.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The rules of the life of a charge plan (docs/cortes/charges-plans.md): states, due day, version. */
class ChargePlanTest {

    private static final Instant T0 = Instant.parse("2026-11-16T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-11-17T12:00:00Z");
    private static final UUID YEAR = UUID.randomUUID();
    private static final UUID GRADE = UUID.randomUUID();

    private static ChargePlan draft() {
        return ChargePlan.create(UUID.randomUUID(), YEAR, " Primaria 2027 ", PlanTargetScope.GRADE, GRADE,
                PlanFrequency.MONTHLY, 5, T0);
    }

    private static ChargePlan active() {
        return draft().edit(0, "Primaria 2027", PlanFrequency.MONTHLY, 5, PlanStatus.ACTIVE, 2, T1);
    }

    private static ChargePlan archived() {
        return active().edit(1, "Primaria 2027", PlanFrequency.MONTHLY, 5, PlanStatus.ARCHIVED, 2, T1);
    }

    @Test
    void aPlanIsBornAsADraftAtVersionZeroWithItsNameStripped() {
        ChargePlan plan = draft();

        assertThat(plan.name()).isEqualTo("Primaria 2027");
        assertThat(plan.status()).isEqualTo(PlanStatus.DRAFT);
        assertThat(plan.version()).isZero();
        assertThat(plan.targetScope()).isEqualTo(PlanTargetScope.GRADE);
        assertThat(plan.targetRefId()).isEqualTo(GRADE);
        assertThat(plan.createdAt()).isEqualTo(T0);
        assertThat(plan.updatedAt()).isEqualTo(T0);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 29, 31, 100})
    void aDueDayOutsideOneToTwentyEightIsRefused(int day) {
        assertThatThrownBy(() -> ChargePlan.create(UUID.randomUUID(), YEAR, "X", PlanTargetScope.GRADE, GRADE,
                PlanFrequency.MONTHLY, day, T0)).isInstanceOf(InvalidChargesValueException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 15, 28})
    void aDueDayFromOneToTwentyEightIsAccepted(int day) {
        assertThat(ChargePlan.create(UUID.randomUUID(), YEAR, "X", PlanTargetScope.SECTION, GRADE,
                PlanFrequency.ONE_TIME, day, T0).dueDayOfMonth()).isEqualTo(day);
    }

    @Test
    void aPlanNeedsAYearATargetAFrequencyAndAValidName() {
        assertThatThrownBy(() -> ChargePlan.create(UUID.randomUUID(), null, "X", PlanTargetScope.GRADE, GRADE,
                PlanFrequency.MONTHLY, 5, T0)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargePlan.create(UUID.randomUUID(), YEAR, "X", null, GRADE,
                PlanFrequency.MONTHLY, 5, T0)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargePlan.create(UUID.randomUUID(), YEAR, "X", PlanTargetScope.GRADE, null,
                PlanFrequency.MONTHLY, 5, T0)).isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> ChargePlan.create(UUID.randomUUID(), YEAR, "X", PlanTargetScope.GRADE, GRADE,
                null, 5, T0)).isInstanceOf(InvalidChargesValueException.class);
        for (String name : new String[] {null, "", "   ", "N".repeat(121), "Con\nsalto", "Con​cero"}) {
            assertThatThrownBy(() -> ChargePlan.create(UUID.randomUUID(), YEAR, name, PlanTargetScope.GRADE, GRADE,
                    PlanFrequency.MONTHLY, 5, T0)).isInstanceOf(InvalidChargesValueException.class);
        }
        assertThat(ChargePlan.create(UUID.randomUUID(), YEAR, "N".repeat(120), PlanTargetScope.GRADE, GRADE,
                PlanFrequency.MONTHLY, 5, T0).name()).hasSize(120);
    }

    @Test
    void aDraftChangesItsNameFrequencyAndDueDayAndAdvancesTheVersion() {
        ChargePlan plan = draft();

        ChargePlan edited = plan.edit(0, " Primaria ", PlanFrequency.BIMONTHLY, 10, PlanStatus.DRAFT, 0, T1);

        assertThat(edited.name()).isEqualTo("Primaria");
        assertThat(edited.frequency()).isEqualTo(PlanFrequency.BIMONTHLY);
        assertThat(edited.dueDayOfMonth()).isEqualTo(10);
        assertThat(edited.status()).isEqualTo(PlanStatus.DRAFT);
        assertThat(edited.version()).isEqualTo(1);
        assertThat(edited.updatedAt()).isEqualTo(T1);
        assertThat(edited.createdAt()).isEqualTo(T0);
        assertThat(edited.id()).isEqualTo(plan.id());
        assertThat(edited.academicYearId()).isEqualTo(YEAR);
        assertThat(edited.targetScope()).isEqualTo(PlanTargetScope.GRADE);
        assertThat(edited.targetRefId()).isEqualTo(GRADE);
    }

    @Test
    void anEditWithAnotherVersionIsStaleWhateverItsContent() {
        ChargePlan plan = draft();

        assertThatThrownBy(() -> plan.edit(1, "X", PlanFrequency.MONTHLY, 5, PlanStatus.DRAFT, 0, T1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> plan.edit(-1, "X", PlanFrequency.MONTHLY, 5, PlanStatus.DRAFT, 0, T1))
                .isInstanceOf(StaleVersionException.class);
        assertThatThrownBy(() -> archived().edit(0, "X", PlanFrequency.MONTHLY, 5, PlanStatus.ARCHIVED, 2, T1))
                .as("the version is checked before the state").isInstanceOf(StaleVersionException.class);
    }

    @Test
    void aDraftWithItemsIsActivatedAndTheVersionAdvances() {
        ChargePlan plan = active();

        assertThat(plan.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(plan.version()).isEqualTo(1);
    }

    @Test
    void aDraftWithoutItemsIsNotActivated() {
        ChargePlan plan = draft();

        assertThatThrownBy(() -> plan.edit(0, "X", PlanFrequency.MONTHLY, 5, PlanStatus.ACTIVE, 0, T1))
                .isInstanceOf(PlanWithoutItemsException.class);
    }

    @Test
    void aDraftCannotBeArchivedDirectly() {
        ChargePlan plan = draft();

        assertThatThrownBy(() -> plan.edit(0, "X", PlanFrequency.MONTHLY, 5, PlanStatus.ARCHIVED, 3, T1))
                .isInstanceOf(PlanNotEditableException.class);
    }

    @Test
    void anActivePlanChangesItsNameAndKeepsItsFrequencyAndDueDay() {
        ChargePlan plan = active();

        ChargePlan renamed = plan.edit(1, "Primaria (2027)", PlanFrequency.MONTHLY, 5, PlanStatus.ACTIVE, 2, T1);

        assertThat(renamed.name()).isEqualTo("Primaria (2027)");
        assertThat(renamed.status()).isEqualTo(PlanStatus.ACTIVE);
        assertThat(renamed.version()).isEqualTo(2);
    }

    @Test
    void anActivePlanRefusesAnotherFrequencyOrDueDayEvenWhenItIsArchivedInTheSameEdit() {
        ChargePlan plan = active();

        assertThatThrownBy(() -> plan.edit(1, "X", PlanFrequency.ANNUAL, 5, PlanStatus.ACTIVE, 2, T1))
                .isInstanceOf(PlanNotEditableException.class);
        assertThatThrownBy(() -> plan.edit(1, "X", PlanFrequency.MONTHLY, 6, PlanStatus.ACTIVE, 2, T1))
                .isInstanceOf(PlanNotEditableException.class);
        assertThatThrownBy(() -> plan.edit(1, "X", PlanFrequency.ANNUAL, 5, PlanStatus.ARCHIVED, 2, T1))
                .isInstanceOf(PlanNotEditableException.class);
    }

    @Test
    void anActivePlanIsArchivedOnceAndNeverGoesBack() {
        ChargePlan plan = active();

        ChargePlan done = plan.edit(1, "Primaria 2027", PlanFrequency.MONTHLY, 5, PlanStatus.ARCHIVED, 2, T1);

        assertThat(done.status()).isEqualTo(PlanStatus.ARCHIVED);
        assertThatThrownBy(() -> plan.edit(1, "X", PlanFrequency.MONTHLY, 5, PlanStatus.DRAFT, 2, T1))
                .isInstanceOf(PlanNotEditableException.class);
    }

    @Test
    void anArchivedPlanChangesNothingAndCannotBeReactivated() {
        ChargePlan plan = archived();

        for (PlanStatus status : PlanStatus.values()) {
            assertThatThrownBy(() -> plan.edit(2, "Primaria 2027", PlanFrequency.MONTHLY, 5, status, 2, T1))
                    .as("archived to %s", status).isInstanceOf(PlanNotEditableException.class);
        }
        assertThatThrownBy(() -> plan.edit(2, "Otro", PlanFrequency.MONTHLY, 5, PlanStatus.ARCHIVED, 2, T1))
                .isInstanceOf(PlanNotEditableException.class);
    }

    @Test
    void anEditNeedsEveryMember() {
        ChargePlan plan = draft();

        assertThatThrownBy(() -> plan.edit(0, null, PlanFrequency.MONTHLY, 5, PlanStatus.DRAFT, 0, T1))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> plan.edit(0, "X", null, 5, PlanStatus.DRAFT, 0, T1))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> plan.edit(0, "X", PlanFrequency.MONTHLY, 5, null, 0, T1))
                .isInstanceOf(InvalidChargesValueException.class);
        assertThatThrownBy(() -> plan.edit(0, "X", PlanFrequency.MONTHLY, 29, PlanStatus.DRAFT, 0, T1))
                .isInstanceOf(InvalidChargesValueException.class);
    }

    @Test
    void onlyADraftTakesOrLosesItems() {
        draft().requireDraft();

        assertThatThrownBy(() -> active().requireDraft()).isInstanceOf(PlanNotEditableException.class);
        assertThatThrownBy(() -> archived().requireDraft()).isInstanceOf(PlanNotEditableException.class);
    }

    @Test
    void theSetsAreParsedExactlyAndCaseMatters() {
        assertThat(PlanTargetScope.parse("SECTION")).isEqualTo(PlanTargetScope.SECTION);
        assertThat(PlanFrequency.parse("ONE_TIME")).isEqualTo(PlanFrequency.ONE_TIME);
        assertThat(PlanStatus.parse("ARCHIVED")).isEqualTo(PlanStatus.ARCHIVED);
        for (String bad : new String[] {null, "", "section", "INSTITUTION", " GRADE"}) {
            assertThatThrownBy(() -> PlanTargetScope.parse(bad)).isInstanceOf(InvalidChargesValueException.class);
        }
        for (String bad : new String[] {null, "", "monthly", "WEEKLY"}) {
            assertThatThrownBy(() -> PlanFrequency.parse(bad)).isInstanceOf(InvalidChargesValueException.class);
        }
        for (String bad : new String[] {null, "", "active", "DELETED"}) {
            assertThatThrownBy(() -> PlanStatus.parse(bad)).isInstanceOf(InvalidChargesValueException.class);
        }
    }
}

package com.confia.scholarships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The discount: its benefit, validity, edit, narrowing and deactivation (docs/cortes/scholarships-discounts.md). */
class DiscountTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final Instant LATER = Instant.parse("2027-02-11T18:00:00Z");
    private static final LocalDate FROM = LocalDate.parse("2027-02-01");
    private static final LocalDate TO = LocalDate.parse("2027-06-30");
    private static final UUID CONCEPT = UUID.randomUUID();
    private static final UUID OTHER_CONCEPT = UUID.randomUUID();

    private static Discount discount(DiscountKind kind, Benefit benefit) {
        return Discount.create(UUID.randomUUID(), "CONV-10", "Convenio 10 %", kind, benefit, FROM, TO, Set.of(CONCEPT), true,
                UUID.randomUUID(), NOW);
    }

    private static Discount agreement() {
        return discount(DiscountKind.AGREEMENT, Benefit.percentage("10"));
    }

    private static Discount edit(Discount before, Benefit benefit, LocalDate from, LocalDate to, Set<UUID> concepts,
            boolean inUse) {
        return before.edit(0, "Convenio", benefit, from, to, concepts, before.stackable(), inUse, LATER);
    }

    // --- Birth ---

    @Test
    void aDiscountIsBornActiveAtVersionZeroAndCoversItsConcepts() {
        Discount created = agreement();

        assertThat(created.active()).isTrue();
        assertThat(created.version()).isZero();
        assertThat(created.stackable()).isTrue();
        assertThat(created.covers(CONCEPT)).isTrue();
        assertThat(created.covers(OTHER_CONCEPT)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "100.01", "101", "10.001", "abc", ""})
    void aPercentageOutOfItsBoundsIsRefused(String points) {
        assertThatThrownBy(() -> Benefit.percentage(points)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "10", "99.99", "100", "100.00"})
    void aPercentageInsideItsBoundsIsAccepted(String points) {
        assertThat(discount(DiscountKind.CAMPAIGN, Benefit.percentage(points)).benefit().percent()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "10.005", "abc"})
    void aFixedAmountThatIsNotPositiveOrHasSubCentDigitsIsRefused(String amount) {
        assertThatThrownBy(() -> Benefit.fixed(amount)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aFixedAmountDiscountIsAccepted() {
        assertThat(discount(DiscountKind.CAMPAIGN, Benefit.fixed("150.00")).benefit().fixedAmount().isPositive()).isTrue();
    }

    @Test
    void aValidityThatEndsBeforeItStartsIsRefusedAndOneDayIsAccepted() {
        assertThatThrownBy(() -> Discount.create(UUID.randomUUID(), "C1", "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                TO, FROM, Set.of(CONCEPT), false, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("validTo");
        Discount oneDay = Discount.create(UUID.randomUUID(), "C1", "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"), FROM, FROM,
                Set.of(CONCEPT), false, UUID.randomUUID(), NOW);
        assertThat(oneDay.validFrom()).isEqualTo(oneDay.validTo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1999-12-31", "2101-01-01"})
    void aValidityOutsideTheWindowOfTheDatabaseIsRefused(String day) {
        LocalDate bad = LocalDate.parse(day);
        assertThatThrownBy(() -> Discount.create(UUID.randomUUID(), "C1", "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                bad, bad, Set.of(CONCEPT), false, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aMissingDayAndAnEmptyOrNullSetOfConceptsAreRefused() {
        assertThatThrownBy(() -> Discount.create(UUID.randomUUID(), "C1", "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                null, TO, Set.of(CONCEPT), false, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> Discount.create(UUID.randomUUID(), "C1", "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                FROM, null, Set.of(CONCEPT), false, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> Discount.create(UUID.randomUUID(), "C1", "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                FROM, TO, Set.of(), false, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> Discount.create(UUID.randomUUID(), "C1", "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                FROM, TO, null, false, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "-A", "A B", "=SUM", "A234567890123456789012345678901"})
    void aCodeOutOfTheWhitelistIsRefused(String code) {
        assertThatThrownBy(() -> Discount.create(UUID.randomUUID(), code, "N", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                FROM, TO, Set.of(CONCEPT), false, UUID.randomUUID(), NOW)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("code");
    }

    @Test
    void theKindIsRecognisedByItsStableCode() {
        assertThat(DiscountKind.ofCode("campaign")).contains(DiscountKind.CAMPAIGN);
        assertThat(DiscountKind.ofCode("agreement")).contains(DiscountKind.AGREEMENT);
        assertThat(DiscountKind.ofCode("CAMPAIGN")).isEmpty();
        assertThat(DiscountKind.ofCode(null)).isEmpty();
    }

    // --- Edit and narrowing ---

    @Test
    void anUnusedDiscountChangesEverythingAndAdvancesItsVersion() {
        Discount before = agreement();

        Discount after = edit(before, Benefit.fixed("100.00"), LocalDate.parse("2027-01-01"), LocalDate.parse("2027-12-31"),
                Set.of(CONCEPT, OTHER_CONCEPT), false);

        assertThat(after.version()).isEqualTo(1);
        assertThat(after.benefit().type()).isEqualTo(BenefitType.FIXED_AMOUNT);
        assertThat(after.validFrom()).isEqualTo(LocalDate.parse("2027-01-01"));
        assertThat(after.validTo()).isEqualTo(LocalDate.parse("2027-12-31"));
        assertThat(after.concepts()).containsExactlyInAnyOrder(CONCEPT, OTHER_CONCEPT);
        assertThat(after.code()).isEqualTo(before.code());
        assertThat(after.kind()).isEqualTo(before.kind());
        assertThat(after.active()).isTrue();
    }

    @Test
    void anEditAsksForTheVersionTheEditorSaw() {
        Discount before = agreement();

        assertThatThrownBy(() -> before.edit(3, "N", before.benefit(), FROM, TO, before.concepts(), true, false, LATER))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void theBenefitOfADiscountInUseNeverChanges() {
        Discount before = agreement();

        assertThatThrownBy(() -> edit(before, Benefit.percentage("11"), FROM, TO, before.concepts(), true))
                .isInstanceOf(DiscountBenefitLockedException.class);
        assertThatThrownBy(() -> edit(before, Benefit.fixed("10.00"), FROM, TO, before.concepts(), true))
                .isInstanceOf(DiscountBenefitLockedException.class);
        assertThat(edit(before, Benefit.percentage("10.00"), FROM, TO, before.concepts(), true).version()).isEqualTo(1);
    }

    @Test
    void theValidityOfADiscountInUseOnlyShortens() {
        Discount before = agreement();

        assertThatThrownBy(() -> edit(before, before.benefit(), FROM.minusDays(1), TO, before.concepts(), true))
                .isInstanceOf(DiscountScopeLockedException.class);
        assertThatThrownBy(() -> edit(before, before.benefit(), FROM, TO.plusDays(1), before.concepts(), true))
                .isInstanceOf(DiscountScopeLockedException.class);
        Discount shorter = edit(before, before.benefit(), FROM.plusDays(1), TO.minusDays(1), before.concepts(), true);
        assertThat(shorter.validFrom()).isEqualTo(FROM.plusDays(1));
        assertThat(shorter.validTo()).isEqualTo(TO.minusDays(1));
        assertThat(edit(before, before.benefit(), FROM, TO, before.concepts(), true).version()).isEqualTo(1);
    }

    @Test
    void theConceptsOfADiscountInUseAreOnlyRemoved() {
        Discount before = discount(DiscountKind.AGREEMENT, Benefit.percentage("10"));
        Discount two = edit(before, before.benefit(), FROM, TO, Set.of(CONCEPT, OTHER_CONCEPT), false);

        assertThatThrownBy(() -> edit(before, before.benefit(), FROM, TO, Set.of(CONCEPT, OTHER_CONCEPT), true))
                .isInstanceOf(DiscountScopeLockedException.class);
        Discount fewer = two.edit(1, "N", two.benefit(), FROM, TO, Set.of(OTHER_CONCEPT), true, true, LATER);
        assertThat(fewer.concepts()).containsExactly(OTHER_CONCEPT);
    }

    @Test
    void theStackableFlagMayChangeEvenWhenInUse() {
        Discount before = agreement();

        Discount after = before.edit(0, "N", before.benefit(), FROM, TO, before.concepts(), false, true, LATER);

        assertThat(after.stackable()).isFalse();
    }

    // --- Activation ---

    @Test
    void deactivatingAndReactivatingMoveTheVersionOnlyWithARealChange() {
        Discount active = agreement();

        Discount inactive = active.withActive(false, LATER);
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(1);
        assertThat(inactive.withActive(false, LATER)).isSameAs(inactive);
        Discount again = inactive.withActive(true, LATER);
        assertThat(again.active()).isTrue();
        assertThat(again.version()).isEqualTo(2);
        assertThat(active.withActive(true, LATER)).isSameAs(active);
    }

    @Test
    void anInactiveDiscountIsRefusedWhereAnActiveOneIsRequired() {
        Discount inactive = agreement().withActive(false, LATER);

        assertThatThrownBy(inactive::requireActive).isInstanceOf(InactiveReferenceException.class);
    }

    // --- Where and when it applies ---

    @Test
    void itAppliesOnBothEndsOfItsValidityAndNotOutsideNorWhenInactive() {
        Discount discount = agreement();

        assertThat(discount.appliesOn(FROM)).isTrue();
        assertThat(discount.appliesOn(TO)).isTrue();
        assertThat(discount.appliesOn(FROM.minusDays(1))).isFalse();
        assertThat(discount.appliesOn(TO.plusDays(1))).isFalse();
        assertThat(discount.withActive(false, LATER).appliesOn(FROM)).isFalse();
    }

    // --- Assignable ---

    @Test
    void onlyAnActiveAgreementDiscountInsideItsValidityIsAssignable() {
        Discount agreement = agreement();

        agreement.requireAssignable(FROM, TO);
        agreement.requireAssignable(FROM, FROM);
        assertThatThrownBy(() -> discount(DiscountKind.CAMPAIGN, Benefit.percentage("5")).requireAssignable(FROM, TO))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).reason()).isEqualTo("not-agreement");
        assertThatThrownBy(() -> agreement.withActive(false, LATER).requireAssignable(FROM, TO))
                .isInstanceOf(InactiveReferenceException.class);
        assertThatThrownBy(() -> agreement.requireAssignable(FROM.minusDays(1), TO))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("validFrom");
        assertThatThrownBy(() -> agreement.requireAssignable(FROM, TO.plusDays(1)))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("validTo");
    }
}

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
    private static final UUID EDITOR = UUID.randomUUID();

    private static Discount discount(DiscountKind kind, Benefit benefit) {
        return Discount.create(UUID.randomUUID(), "CONV-10", "Convenio 10 %", kind, benefit, FROM, TO, Set.of(CONCEPT), true,
                UUID.randomUUID(), NOW);
    }

    private static Discount agreement() {
        return discount(DiscountKind.AGREEMENT, Benefit.percentage("10"));
    }

    private static Discount edit(Discount before, Benefit benefit, LocalDate from, LocalDate to, Set<UUID> concepts,
            boolean inUse) {
        return before.edit(0, EDITOR, "Convenio", benefit, from, to, concepts, before.stackable(), inUse, LATER);
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

        assertThatThrownBy(() -> before.edit(3, EDITOR, "N", before.benefit(), FROM, TO, before.concepts(), true, false, LATER))
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
        Discount fewer = two.edit(1, EDITOR, "N", two.benefit(), FROM, TO, Set.of(OTHER_CONCEPT), true, true, LATER);
        assertThat(fewer.concepts()).containsExactly(OTHER_CONCEPT);
    }

    // --- Activation ---

    @Test
    void deactivatingAndReactivatingMoveTheVersionOnlyWithARealChange() {
        Discount active = agreement();

        Discount inactive = active.withActive(false, EDITOR, LATER);
        assertThat(inactive.active()).isFalse();
        assertThat(inactive.version()).isEqualTo(1);
        assertThat(inactive.withActive(false, EDITOR, LATER)).isSameAs(inactive);
        Discount again = inactive.withActive(true, EDITOR, LATER);
        assertThat(again.active()).isTrue();
        assertThat(again.version()).isEqualTo(2);
        assertThat(active.withActive(true, EDITOR, LATER)).isSameAs(active);
    }

    @Test
    void anInactiveDiscountIsRefusedWhereAnActiveOneIsRequired() {
        Discount inactive = agreement().withActive(false, EDITOR, LATER);

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
        assertThat(discount.withActive(false, EDITOR, LATER).appliesOn(FROM)).isFalse();
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
        assertThatThrownBy(() -> agreement.withActive(false, EDITOR, LATER).requireAssignable(FROM, TO))
                .isInstanceOf(InactiveReferenceException.class);
        assertThatThrownBy(() -> agreement.requireAssignable(FROM.minusDays(1), TO))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("validFrom");
        assertThatThrownBy(() -> agreement.requireAssignable(FROM, TO.plusDays(1)))
                .isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("validTo");
    }

    // --- S8: the stackable flag is locked like the type and the value once the discount is in use ---

    @Test
    void theStackableFlagIsLockedOnceTheDiscountIsInUseLikeTheTypeAndTheValue() {
        Discount before = agreement();

        assertThatThrownBy(() -> before.edit(0, EDITOR, "N", before.benefit(), FROM, TO, before.concepts(), false, true, LATER))
                .isInstanceOf(DiscountBenefitLockedException.class);
        // The same flag is no change, and with no assignment approved nor a charge issued the flag moves freely.
        assertThat(before.edit(0, EDITOR, "N", before.benefit(), FROM, TO, before.concepts(), true, true, LATER).stackable()).isTrue();
        assertThat(before.edit(0, EDITOR, "N", before.benefit(), FROM, TO, before.concepts(), false, false, LATER).stackable())
                .isFalse();
    }

    // --- S7: the approval of a campaign discount by another person ---

    private static Discount campaign(String points) {
        return discount(DiscountKind.CAMPAIGN, Benefit.percentage(points));
    }

    private static Discount approved(Discount waiting, UUID by) {
        return waiting.decided(waiting.approveChange(UUID.randomUUID(), by, waiting.version(), LATER, "key", "a".repeat(64)), LATER);
    }

    private static Discount edit(Discount before, String points, LocalDate from, LocalDate to, Set<UUID> concepts,
            boolean stackable) {
        return before.edit(before.version(), EDITOR, "Campana", Benefit.percentage(points), from, to, concepts, stackable, false,
                LATER);
    }

    @Test
    void aCampaignDiscountIsBornPendingAndAnAgreementOneApproved() {
        assertThat(campaign("10").approval()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(campaign("10").awaitingApproval()).isTrue();
        assertThat(campaign("10").appliesOn(FROM)).as("a pending campaign applies to nothing").isFalse();
        assertThat(agreement().approval()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(agreement().awaitingApproval()).isFalse();
    }

    @Test
    void anotherPersonApprovesTheCreationAndFromThenOnItApplies() {
        Discount waiting = campaign("10");
        UUID approver = UUID.randomUUID();

        DiscountDecision decision = waiting.approveChange(UUID.randomUUID(), approver, 0, LATER, "key-1", "b".repeat(64));
        Discount after = waiting.decided(decision, LATER);

        assertThat(decision.discountVersion()).isZero();
        assertThat(decision.kind()).isEqualTo(DecisionKind.APPROVED);
        assertThat(after.approval()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(after.version()).isEqualTo(1);
        assertThat(after.appliesOn(FROM)).isTrue();
        assertThat(after.awaitingApproval()).isFalse();
    }

    @Test
    void whoCreatedACampaignDiscountNeverApprovesNorRejectsItAndNothingChanges() {
        Discount waiting = campaign("10");

        assertThatThrownBy(() -> waiting.approveChange(UUID.randomUUID(), waiting.createdBy(), 0, LATER, "k", "c".repeat(64)))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> waiting.rejectChange(UUID.randomUUID(), waiting.createdBy(), 0,
                DecisionReason.of("No cumple con el presupuesto aprobado"), LATER, "k", "c".repeat(64)))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThat(waiting.approval()).isEqualTo(ApprovalStatus.PENDING);
    }

    @Test
    void aDecisionNamesTheVersionTheDeciderSawAndNothingElseIsApproved() {
        Discount waiting = campaign("10");

        assertThatThrownBy(() -> waiting.approveChange(UUID.randomUUID(), UUID.randomUUID(), 5, LATER, "k", "d".repeat(64)))
                .isInstanceOf(StaleVersionException.class);
    }

    @Test
    void aRejectedCreationNeverAppliesAndIsNeverChanged() {
        Discount waiting = campaign("10");
        DiscountDecision rejection = waiting.rejectChange(UUID.randomUUID(), UUID.randomUUID(), 0,
                DecisionReason.of("No cumple con el presupuesto aprobado"), LATER, "k", "e".repeat(64));
        Discount rejected = waiting.decided(rejection, LATER);

        assertThat(rejected.approval()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(rejected.appliesOn(FROM)).isFalse();
        assertThatThrownBy(() -> edit(rejected, "5", FROM, TO, rejected.concepts(), true))
                .isInstanceOf(DiscountPendingApprovalException.class);
        assertThatThrownBy(() -> rejected.withActive(false, EDITOR, LATER)).isInstanceOf(DiscountPendingApprovalException.class);
        assertThatThrownBy(() -> rejected.approveChange(UUID.randomUUID(), UUID.randomUUID(), 1, LATER, "k", "e".repeat(64)))
                .isInstanceOf(InvalidDiscountChangeStateException.class);
    }

    @Test
    void aDiscountWaitingForItsFirstApprovalIsNotEditedNorDeactivated() {
        Discount waiting = campaign("10");

        assertThatThrownBy(() -> edit(waiting, "5", FROM, TO, waiting.concepts(), true))
                .isInstanceOf(DiscountPendingApprovalException.class);
        assertThatThrownBy(() -> waiting.withActive(false, EDITOR, LATER)).isInstanceOf(DiscountPendingApprovalException.class);
    }

    @Test
    void nothingThatDoesNotWaitCanBeDecided() {
        Discount settled = approved(campaign("10"), UUID.randomUUID());

        assertThatThrownBy(() -> settled.approveChange(UUID.randomUUID(), UUID.randomUUID(), 1, LATER, "k", "f".repeat(64)))
                .isInstanceOf(InvalidDiscountChangeStateException.class);
    }

    @Test
    void wideningAnApprovedCampaignDiscountLeavesAProposalAndTheTermsInForceStay() {
        Discount settled = approved(campaign("10"), UUID.randomUUID());

        Discount proposed = edit(settled, "15", FROM, TO, settled.concepts(), true);

        assertThat(proposed.benefit()).as("the terms in force").isEqualTo(settled.benefit());
        assertThat(proposed.proposal().benefit()).isEqualTo(Benefit.percentage("15"));
        assertThat(proposed.proposal().requestedBy()).isEqualTo(EDITOR);
        assertThat(proposed.version()).isEqualTo(settled.version() + 1);
        assertThat(proposed.appliesOn(FROM)).as("it keeps applying with the terms in force").isTrue();
        assertThat(proposed.awaitingApproval()).isTrue();
    }

    @Test
    void everyKindOfWideningIsAProposalAndEveryNarrowingIsImmediate() {
        UUID other = UUID.randomUUID();
        Discount stackableOff = Discount.create(UUID.randomUUID(), "CAMP", "Campana", DiscountKind.CAMPAIGN,
                Benefit.percentage("10"), FROM, TO, Set.of(CONCEPT), false, UUID.randomUUID(), NOW);
        Discount settled = approved(stackableOff, UUID.randomUUID());

        assertThat(edit(settled, "10.01", FROM, TO, settled.concepts(), false).proposal()).as("a greater value").isNotNull();
        assertThat(edit(settled, "10", FROM.minusDays(1), TO, settled.concepts(), false).proposal()).as("an earlier start")
                .isNotNull();
        assertThat(edit(settled, "10", FROM, TO.plusDays(1), settled.concepts(), false).proposal()).as("a later end").isNotNull();
        assertThat(edit(settled, "10", FROM, TO, Set.of(CONCEPT, other), false).proposal()).as("a concept more").isNotNull();
        assertThat(edit(settled, "10", FROM, TO, settled.concepts(), true).proposal()).as("stackable on").isNotNull();
        assertThat(settled.edit(settled.version(), EDITOR, "Campana", Benefit.fixed("1.00"), FROM, TO, settled.concepts(), false,
                false, LATER).proposal()).as("another type of benefit").isNotNull();

        Discount smaller = edit(settled, "9.99", FROM, TO, settled.concepts(), false);
        assertThat(smaller.proposal()).as("a smaller value is immediate").isNull();
        assertThat(smaller.benefit()).isEqualTo(Benefit.percentage("9.99"));
        assertThat(edit(settled, "10", FROM.plusDays(1), TO.minusDays(1), settled.concepts(), false).proposal())
                .as("a shorter validity").isNull();
        assertThat(edit(settled, "10", FROM, TO, settled.concepts(), false).proposal()).as("the same terms").isNull();
    }

    @Test
    void aSecondEditWhileAProposalWaitsIsRefusedButTheDiscountCanBeDeactivated() {
        Discount settled = approved(campaign("10"), UUID.randomUUID());
        Discount proposed = edit(settled, "15", FROM, TO, settled.concepts(), true);

        assertThatThrownBy(() -> edit(proposed, "5", FROM, TO, proposed.concepts(), true))
                .isInstanceOf(DiscountPendingApprovalException.class);
        Discount off = proposed.withActive(false, EDITOR, LATER);
        assertThat(off.active()).isFalse();
        assertThat(off.proposal()).isEqualTo(proposed.proposal());
        assertThatThrownBy(() -> off.withActive(true, EDITOR, LATER)).isInstanceOf(DiscountPendingApprovalException.class);
    }

    @Test
    void anotherPersonApprovesTheProposalAndItBecomesTheTermsInForce() {
        Discount settled = approved(campaign("10"), UUID.randomUUID());
        Discount proposed = edit(settled, "15", FROM.minusDays(5), TO.plusDays(5), settled.concepts(), true);
        UUID approver = UUID.randomUUID();

        Discount after = proposed.decided(proposed.approveChange(UUID.randomUUID(), approver, proposed.version(), LATER, "k",
                "a".repeat(64)), LATER);

        assertThat(after.benefit()).isEqualTo(Benefit.percentage("15"));
        assertThat(after.validFrom()).isEqualTo(FROM.minusDays(5));
        assertThat(after.validTo()).isEqualTo(TO.plusDays(5));
        assertThat(after.stackable()).isTrue();
        assertThat(after.proposal()).isNull();
        assertThat(after.version()).isEqualTo(proposed.version() + 1);
        assertThat(after.approval()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void whoProposedNeverApprovesAndARejectionDropsTheProposalKeepingTheTerms() {
        Discount settled = approved(campaign("10"), UUID.randomUUID());
        Discount proposed = edit(settled, "15", FROM, TO, settled.concepts(), true);

        assertThatThrownBy(() -> proposed.approveChange(UUID.randomUUID(), EDITOR, proposed.version(), LATER, "k", "a".repeat(64)))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        Discount dropped = proposed.decided(proposed.rejectChange(UUID.randomUUID(), UUID.randomUUID(), proposed.version(),
                DecisionReason.of("El valor propuesto excede lo autorizado"), LATER, "k", "a".repeat(64)), LATER);
        assertThat(dropped.proposal()).isNull();
        assertThat(dropped.benefit()).isEqualTo(Benefit.percentage("10"));
        assertThat(dropped.approval()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void reactivatingACampaignDiscountIsAProposalAndOnlyItsApprovalTurnsItOn() {
        Discount settled = approved(campaign("10"), UUID.randomUUID());
        Discount off = settled.withActive(false, EDITOR, LATER);
        assertThat(off.proposal()).as("deactivating is immediate").isNull();

        Discount asked = off.withActive(true, EDITOR, LATER);

        assertThat(asked.active()).isFalse();
        assertThat(asked.proposal().reactivate()).isTrue();
        Discount on = asked.decided(asked.approveChange(UUID.randomUUID(), UUID.randomUUID(), asked.version(), LATER, "k",
                "a".repeat(64)), LATER);
        assertThat(on.active()).isTrue();
        assertThat(on.proposal()).isNull();
    }

    @Test
    void reactivatingAnAgreementDiscountStaysImmediate() {
        Discount off = agreement().withActive(false, EDITOR, LATER);

        assertThat(off.withActive(true, EDITOR, LATER).active()).isTrue();
        assertThat(off.withActive(true, EDITOR, LATER).proposal()).isNull();
    }

    @Test
    void anAgreementDiscountIsNeverWidenedIntoAProposal() {
        Discount before = agreement();

        Discount after = before.edit(0, EDITOR, "N", Benefit.percentage("50"), FROM, TO, before.concepts(), true, false, LATER);

        assertThat(after.proposal()).isNull();
        assertThat(after.benefit()).isEqualTo(Benefit.percentage("50"));
    }

    @Test
    void approvingAProposalOnAnInactiveDiscountAppliesTheTermsAndKeepsItOffUnlessItAskedToReactivate() {
        Discount settled = approved(campaign("10"), UUID.randomUUID());
        Discount off = settled.withActive(false, EDITOR, LATER);
        Discount proposed = edit(off, "15", FROM, TO, off.concepts(), true);

        Discount after = proposed.decided(proposed.approveChange(UUID.randomUUID(), UUID.randomUUID(), proposed.version(), LATER, "k",
                "a".repeat(64)), LATER);

        assertThat(proposed.proposal().reactivate()).isFalse();
        assertThat(after.benefit()).isEqualTo(Benefit.percentage("15"));
        assertThat(after.active()).as("a widening does not turn the discount on").isFalse();
    }
}

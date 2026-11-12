package com.confia.guardians.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * INV-32 (docs/02-modelo-de-dominio.md): the percentages of the current links of a student add up to exactly 100,
 * in exact decimal arithmetic. The cases are the ones of docs/cortes/guardians-registry.md: sums of 99.99, 100.01
 * and 100.00 (with three guardians, 33.33 + 33.33 + 33.34), no links at all, percentages of 0 and 100.01, two
 * primary payers, two billing recipients, a guardian twice and a guardian that is not active.
 */
class ResponsibilitySplitTest {

    private static LinkDraft link(UUID guardian, String percent) {
        return link(guardian, percent, false, false);
    }

    private static LinkDraft link(UUID guardian, String percent, boolean primary, boolean billing) {
        return new LinkDraft(guardian, Relationship.MOTHER, new BigDecimal(percent), primary, billing);
    }

    private static void assertRejected(List<LinkDraft> drafts, String reason) {
        assertThatThrownBy(() -> ResponsibilitySplit.of(drafts))
                .isInstanceOfSatisfying(InvalidResponsibilitySplitException.class,
                        e -> assertThat(e.reason()).isEqualTo(reason))
                .extracting("code").isEqualTo("invalid-responsibility-split");
    }

    @Test
    void aSingleGuardianWithAHundredPercentIsValid() {
        ResponsibilitySplit split = ResponsibilitySplit.of(List.of(link(UUID.randomUUID(), "100")));

        assertThat(split.total()).isEqualByComparingTo("100.00");
        assertThat(split.links()).hasSize(1);
    }

    @Test
    void sixtyAndFortyIsValidAndFiftyAndFiftyIsValid() {
        assertThat(ResponsibilitySplit.of(List.of(link(UUID.randomUUID(), "60"), link(UUID.randomUUID(), "40")))
                .total()).isEqualByComparingTo("100.00");
        assertThat(ResponsibilitySplit.of(List.of(link(UUID.randomUUID(), "50.00"), link(UUID.randomUUID(), "50.0")))
                .total()).isEqualByComparingTo("100.00");
    }

    @Test
    void sixtyAndThirtyIsRejectedAsSumNotOneHundred() {
        assertRejected(List.of(link(UUID.randomUUID(), "60"), link(UUID.randomUUID(), "30")), "sum-not-100");
    }

    @Test
    void aSumOfNinetyNinePointNinetyNineIsRejected() {
        assertRejected(List.of(link(UUID.randomUUID(), "50"), link(UUID.randomUUID(), "49.99")), "sum-not-100");
    }

    @Test
    void aSumOfOneHundredPointZeroOneIsRejected() {
        assertRejected(List.of(link(UUID.randomUUID(), "50"), link(UUID.randomUUID(), "50.01")), "sum-not-100");
    }

    @Test
    void threeGuardiansOfThirtyThreeThirtyThreeAndThirtyThreePointThirtyFourAddUpToExactlyOneHundred() {
        ResponsibilitySplit split = ResponsibilitySplit.of(List.of(link(UUID.randomUUID(), "33.33"),
                link(UUID.randomUUID(), "33.33"), link(UUID.randomUUID(), "33.34")));

        assertThat(split.total()).isEqualByComparingTo("100.00");
        // Binary floating point would have said 99.99999999999999 or 100.00000000000001 for some orders; the
        // exact sum is the same in every order.
        List<LinkDraft> shuffled = new ArrayList<>(split.links());
        java.util.Collections.reverse(shuffled);
        assertThat(ResponsibilitySplit.of(shuffled).total()).isEqualByComparingTo("100.00");
    }

    @Test
    void threeGuardiansOfThirtyThreeThirtyThreeAndThirtyThreePointThirtyThreeAreRejected() {
        assertRejected(List.of(link(UUID.randomUUID(), "33.33"), link(UUID.randomUUID(), "33.33"),
                link(UUID.randomUUID(), "33.33")), "sum-not-100");
    }

    @Test
    void aStudentWithoutLinksIsValidAndItsTotalIsZero() {
        ResponsibilitySplit split = ResponsibilitySplit.of(List.of());

        assertThat(split.links()).isEmpty();
        assertThat(split.total()).isEqualByComparingTo("0.00");
        assertThat(split.guardianIds()).isEmpty();
    }

    @Test
    void twoPrimaryPayersAreRejected() {
        assertRejected(List.of(link(UUID.randomUUID(), "50", true, false), link(UUID.randomUUID(), "50", true, false)),
                "multiple-primary-payer");
    }

    @Test
    void twoBillingRecipientsAreRejected() {
        assertRejected(List.of(link(UUID.randomUUID(), "50", false, true), link(UUID.randomUUID(), "50", false, true)),
                "multiple-billing-recipient");
    }

    @Test
    void oneOfEachFlagOnDifferentGuardiansIsValidAndNoFlagAtAllIsValid() {
        assertThat(ResponsibilitySplit.of(List.of(link(UUID.randomUUID(), "50", true, false),
                link(UUID.randomUUID(), "50", false, true))).links()).hasSize(2);
        assertThat(ResponsibilitySplit.of(List.of(link(UUID.randomUUID(), "100"))).links()).hasSize(1);
    }

    @Test
    void theSameGuardianTwiceIsRejectedEvenWhenTheSumIsRight() {
        UUID guardian = UUID.randomUUID();

        assertRejected(List.of(link(guardian, "50"), link(guardian, "50")), "duplicate-guardian");
    }

    @Test
    void aGuardianThatIsNotActiveIsRefused() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        ResponsibilitySplit split = ResponsibilitySplit.of(List.of(link(active, "60"), link(inactive, "40")));

        assertThatThrownBy(() -> split.requireActive(Set.of(active)))
                .isInstanceOf(GuardianNotActiveException.class).extracting("code").isEqualTo("guardian-not-active");
        split.requireActive(Set.of(active, inactive));
    }

    @Test
    void theErrorsNeverCarryAValueOfTheRequest() {
        UUID guardian = UUID.randomUUID();

        assertThatThrownBy(() -> ResponsibilitySplit.of(List.of(link(guardian, "33.33"))))
                .hasMessageNotContaining("33.33").hasMessageNotContaining(guardian.toString());
    }

    // --- The percentage of one link ---

    @Test
    void aPercentageOfZeroIsRejected() {
        assertThatThrownBy(() -> new LinkDraft(UUID.randomUUID(), Relationship.FATHER, new BigDecimal("0"), false,
                false)).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> new LinkDraft(UUID.randomUUID(), Relationship.FATHER, new BigDecimal("0.00"), false,
                false)).isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void aPercentageOfOneHundredPointZeroOneIsRejectedAndOneHundredIsAccepted() {
        assertThatThrownBy(() -> link(UUID.randomUUID(), "100.01")).isInstanceOf(InvalidGuardianException.class);
        assertThat(link(UUID.randomUUID(), "100").percent()).isEqualByComparingTo("100.00");
    }

    @Test
    void theSmallestPercentageIsOneHundredth() {
        assertThat(link(UUID.randomUUID(), "0.01").percent()).isEqualByComparingTo("0.01");
        assertThatThrownBy(() -> link(UUID.randomUUID(), "0.009")).isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void aNegativePercentageAndOneWithThreeDecimalsAreRejected() {
        assertThatThrownBy(() -> link(UUID.randomUUID(), "-10")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> link(UUID.randomUUID(), "33.333")).isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> link(UUID.randomUUID(), "99.999")).isInstanceOf(InvalidGuardianException.class);
    }

    @Test
    void trailingZerosBeyondTwoDecimalsAreTheSamePercentage() {
        LinkDraft draft = link(UUID.randomUUID(), "60.000");

        assertThat(draft.percent().scale()).isEqualTo(2);
        assertThat(draft.percent()).isEqualByComparingTo("60");
    }

    @Test
    void aMissingPercentageGuardianOrRelationshipIsRejected() {
        assertThatThrownBy(() -> LinkDraft.of(UUID.randomUUID(), "MOTHER", null, false, false))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> LinkDraft.of(null, "MOTHER", BigDecimal.TEN, false, false))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> LinkDraft.of(UUID.randomUUID(), "UNCLE", BigDecimal.TEN, false, false))
                .isInstanceOf(InvalidGuardianException.class);
        assertThatThrownBy(() -> LinkDraft.of(UUID.randomUUID(), null, BigDecimal.TEN, false, false))
                .isInstanceOf(InvalidGuardianException.class);
    }

    // --- Properties ---

    /** Any division of 100.00 into whole hundredths, in any number of parts, adds up exactly and is accepted. */
    @Property
    void anyDivisionOfOneHundredIntoHundredthsAddsUpToExactlyOneHundred(
            @ForAll("divisions") List<Integer> hundredths) {
        List<LinkDraft> drafts = new ArrayList<>();
        for (int part : hundredths) {
            drafts.add(new LinkDraft(UUID.randomUUID(), Relationship.OTHER, BigDecimal.valueOf(part, 2), false, false));
        }

        assertThat(ResponsibilitySplit.of(drafts).total()).isEqualByComparingTo("100.00");
    }

    /** The same division with one hundredth moved from a part to another breaks nothing; with one removed, it does. */
    @Property
    void takingOneHundredthAwayFromAnyDivisionIsRejected(@ForAll("divisions") List<Integer> hundredths,
            @ForAll @IntRange(min = 0, max = 49) int which) {
        List<Integer> shorted = new ArrayList<>(hundredths);
        int index = which % shorted.size();
        if (shorted.get(index) == 1) {
            return;
        }
        shorted.set(index, shorted.get(index) - 1);
        List<LinkDraft> drafts = new ArrayList<>();
        for (int part : shorted) {
            drafts.add(new LinkDraft(UUID.randomUUID(), Relationship.OTHER, BigDecimal.valueOf(part, 2), false, false));
        }

        assertRejected(drafts, "sum-not-100");
    }

    @Provide
    Arbitrary<List<Integer>> divisions() {
        return Arbitraries.integers().between(1, 10).flatMap(parts -> {
            if (parts == 1) {
                return Arbitraries.just(List.of(10000));
            }
            // parts - 1 cut points inside 1..9999 give parts positive pieces that add up to 10000.
            return Arbitraries.integers().between(1, 9999).set().ofSize(parts - 1).map(cuts -> {
                List<Integer> sorted = new ArrayList<>(cuts);
                java.util.Collections.sort(sorted);
                List<Integer> pieces = new ArrayList<>();
                int previous = 0;
                for (int cut : sorted) {
                    pieces.add(cut - previous);
                    previous = cut;
                }
                pieces.add(10000 - previous);
                return pieces;
            });
        });
    }
}

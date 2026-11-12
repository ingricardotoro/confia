package com.confia.guardians.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The history rule of docs/cortes/guardians-registry.md: replacing the links of a student closes what changes and
 * opens what is new, leaves what is identical, and never edits or reopens a closed link.
 */
class LinkReplacementTest {

    private static final Instant NOW = Instant.parse("2026-03-10T15:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final LocalDate EARLIER = LocalDate.of(2026, 2, 1);

    private final UUID student = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final UUID mother = UUID.randomUUID();
    private final UUID father = UUID.randomUUID();
    private final UUID aunt = UUID.randomUUID();

    private GuardianLink current(UUID guardian, Relationship relationship, String percent, boolean primary,
            boolean billing) {
        return new GuardianLink(UUID.randomUUID(), guardian, student, relationship, new BigDecimal(percent), primary,
                billing, EARLIER, null, actor, NOW.minusSeconds(3600));
    }

    private static LinkDraft draft(UUID guardian, Relationship relationship, String percent, boolean primary,
            boolean billing) {
        return new LinkDraft(guardian, relationship, new BigDecimal(percent), primary, billing);
    }

    private LinkReplacement replace(List<GuardianLink> current, LinkDraft... drafts) {
        return LinkReplacement.of(current, ResponsibilitySplit.of(List.of(drafts)), student, TODAY, actor, NOW,
                UUID::randomUUID);
    }

    @Test
    void theFirstSetOpensEveryLinkTodayAndClosesNothing() {
        LinkReplacement result = replace(List.of(),
                draft(mother, Relationship.MOTHER, "60", true, true), draft(father, Relationship.FATHER, "40", false, false));

        assertThat(result.closed()).isEmpty();
        assertThat(result.kept()).isEmpty();
        assertThat(result.opened()).hasSize(2).allSatisfy(link -> {
            assertThat(link.validFrom()).isEqualTo(TODAY);
            assertThat(link.validTo()).isNull();
            assertThat(link.studentId()).isEqualTo(student);
            assertThat(link.createdBy()).isEqualTo(actor);
        });
        assertThat(result.changed()).isTrue();
    }

    @Test
    void changingThePercentagesClosesTheOldLinksAndOpensNewOnesForTheSameGuardians() {
        GuardianLink m = current(mother, Relationship.MOTHER, "60.00", true, true);
        GuardianLink f = current(father, Relationship.FATHER, "40.00", false, false);

        LinkReplacement result = replace(List.of(m, f),
                draft(mother, Relationship.MOTHER, "50", true, true), draft(father, Relationship.FATHER, "50", false, false));

        assertThat(result.closed()).containsExactlyInAnyOrder(m, f);
        assertThat(result.kept()).isEmpty();
        assertThat(result.opened()).extracting(GuardianLink::guardianId).containsExactlyInAnyOrder(mother, father);
        assertThat(result.opened()).extracting(GuardianLink::id).doesNotContain(m.id(), f.id());
        assertThat(result.opened()).extracting(GuardianLink::percent)
                .allSatisfy(percent -> assertThat(percent).isEqualByComparingTo("50"));
    }

    @Test
    void anIdenticalSetChangesNothingEvenWhenThePercentageIsWrittenWithAnotherScale() {
        GuardianLink m = current(mother, Relationship.MOTHER, "60.00", true, false);
        GuardianLink f = current(father, Relationship.FATHER, "40.00", false, true);

        LinkReplacement result = replace(List.of(m, f),
                draft(mother, Relationship.MOTHER, "60", true, false), draft(father, Relationship.FATHER, "40.0", false, true));

        assertThat(result.changed()).isFalse();
        assertThat(result.closed()).isEmpty();
        assertThat(result.opened()).isEmpty();
        assertThat(result.kept()).containsExactlyInAnyOrder(m, f);
    }

    @Test
    void aChangedRelationshipOrFlagAloneClosesAndOpens() {
        GuardianLink m = current(mother, Relationship.MOTHER, "100", false, false);

        assertThat(replace(List.of(m), draft(mother, Relationship.LEGAL_GUARDIAN, "100", false, false)).changed())
                .isTrue();
        assertThat(replace(List.of(m), draft(mother, Relationship.MOTHER, "100", true, false)).changed()).isTrue();
        assertThat(replace(List.of(m), draft(mother, Relationship.MOTHER, "100", false, true)).changed()).isTrue();
    }

    @Test
    void aGuardianAbsentFromTheSetIsClosedAndNeverReopenedByItsAbsence() {
        GuardianLink m = current(mother, Relationship.MOTHER, "60", false, false);
        GuardianLink f = current(father, Relationship.FATHER, "40", false, false);

        LinkReplacement result = replace(List.of(m, f), draft(mother, Relationship.MOTHER, "100", false, false));

        assertThat(result.closed()).containsExactlyInAnyOrder(m, f);
        assertThat(result.opened()).singleElement().satisfies(link -> assertThat(link.guardianId()).isEqualTo(mother));
    }

    @Test
    void anEmptySetClosesEveryCurrentLinkAndOpensNone() {
        GuardianLink m = current(mother, Relationship.MOTHER, "100", false, false);

        LinkReplacement result = replace(List.of(m));

        assertThat(result.closed()).containsExactly(m);
        assertThat(result.opened()).isEmpty();
        assertThat(result.changed()).isTrue();
    }

    @Test
    void aNewGuardianNextToAnIdenticalOneKeepsTheIdenticalLink() {
        GuardianLink m = current(mother, Relationship.MOTHER, "100", false, false);

        // 100 -> 100 is impossible next to a new guardian, so the mother changes too: only the identical case here.
        LinkReplacement result = replace(List.of(m), draft(mother, Relationship.MOTHER, "100", false, false));
        assertThat(result.kept()).containsExactly(m);

        LinkReplacement withAunt = replace(List.of(m), draft(mother, Relationship.MOTHER, "70", false, false),
                draft(aunt, Relationship.OTHER, "30", false, false));
        assertThat(withAunt.closed()).containsExactly(m);
        assertThat(withAunt.opened()).hasSize(2);
    }

    @Test
    void aClosedLinkCanNeverBeBuiltBeforeItStarted() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new GuardianLink(UUID.randomUUID(), mother, student,
                Relationship.MOTHER, new BigDecimal("100"), false, false, TODAY, TODAY.minusDays(1), actor, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        GuardianLink sameDay = new GuardianLink(UUID.randomUUID(), mother, student, Relationship.MOTHER,
                new BigDecimal("100"), false, false, TODAY, TODAY, actor, NOW);
        assertThat(sameDay.current()).isFalse();
    }

    @Test
    void todayIsTheDayItIsInHonduras() {
        // 03:00 UTC is still the previous evening in Tegucigalpa (UTC-6).
        assertThat(LinkReplacement.dayOf(Instant.parse("2026-03-10T03:00:00Z"))).isEqualTo(LocalDate.of(2026, 3, 9));
        assertThat(LinkReplacement.dayOf(Instant.parse("2026-03-10T06:00:00Z"))).isEqualTo(LocalDate.of(2026, 3, 10));
    }

    @Test
    void theToStringOfALinkNamesOnlyItsIdentifier() {
        GuardianLink link = current(mother, Relationship.MOTHER, "60", false, false);

        assertThat(link.toString()).isEqualTo("GuardianLink[id=" + link.id() + "]");
    }
}

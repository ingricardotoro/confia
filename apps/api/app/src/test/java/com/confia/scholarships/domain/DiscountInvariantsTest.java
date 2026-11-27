package com.confia.scholarships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a discount, an assignment, a decision and an approval state refuse to be, whatever builds them: the shapes that a database
 * row could carry if it were wrong are not turned into objects of the domain (docs/cortes/scholarships-discounts.md).
 */
class DiscountInvariantsTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final LocalDate FROM = LocalDate.parse("2027-02-01");
    private static final LocalDate TO = LocalDate.parse("2027-06-30");
    private static final UUID CONCEPT = UUID.randomUUID();
    private static final UUID PERSON = UUID.randomUUID();

    private static Discount discount(DiscountKind kind, long version, ApprovalStatus approval, DiscountProposal proposal) {
        return new Discount(UUID.randomUUID(), "CONV-10", "Convenio", kind, Benefit.percentage("10"), FROM, TO, Set.of(CONCEPT), true,
                true, version, PERSON, NOW, NOW, approval, proposal);
    }

    private static DiscountProposal proposal() {
        return new DiscountProposal("Convenio", Benefit.percentage("20"), FROM, TO, true, false, Set.of(CONCEPT), PERSON, NOW);
    }

    private static Discount withDays(LocalDate from, LocalDate to) {
        return new Discount(UUID.randomUUID(), "CONV-10", "Convenio", DiscountKind.AGREEMENT, Benefit.percentage("10"), from, to,
                Set.of(CONCEPT), true, true, 0, PERSON, NOW, NOW, ApprovalStatus.APPROVED, null);
    }

    private static Discount withConcepts(Set<UUID> concepts) {
        return new Discount(UUID.randomUUID(), "C", "N", DiscountKind.AGREEMENT, Benefit.percentage("10"), FROM, TO, concepts, true,
                true, 0, PERSON, NOW, NOW, ApprovalStatus.APPROVED, null);
    }

    private static DiscountAssignment assignmentOn(LocalDate from, LocalDate to) {
        return new DiscountAssignment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), from, to, AwardStatus.PENDING, PERSON,
                NOW, null, null);
    }

    @Test
    void aDiscountNeverHasANegativeVersionAnAgreementIsAlwaysApprovedWithoutProposalAndAProposalNeedsAnApprovedOne() {
        assertThat(discount(DiscountKind.CAMPAIGN, 0, ApprovalStatus.PENDING, null).awaitingApproval()).isTrue();
        assertThat(discount(DiscountKind.CAMPAIGN, 3, ApprovalStatus.APPROVED, proposal()).requestedBy()).isEqualTo(PERSON);
        assertThat(discount(DiscountKind.CAMPAIGN, 3, ApprovalStatus.REJECTED, null).awaitingApproval()).isFalse();

        assertThatThrownBy(() -> discount(DiscountKind.CAMPAIGN, -1, ApprovalStatus.APPROVED, null))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> discount(DiscountKind.AGREEMENT, 0, ApprovalStatus.PENDING, null))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> discount(DiscountKind.AGREEMENT, 0, ApprovalStatus.REJECTED, null))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> discount(DiscountKind.AGREEMENT, 0, ApprovalStatus.APPROVED, proposal()))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> discount(DiscountKind.CAMPAIGN, 0, ApprovalStatus.PENDING, proposal()))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aValidityNeedsBothEndsInsideTheWindowAndInOrder() {
        assertThatThrownBy(() -> withDays(null, TO)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> withDays(LocalDate.parse("1999-12-31"), TO)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> withDays(LocalDate.parse("2101-01-01"), LocalDate.parse("2101-01-02")))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> withDays(FROM, null)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> withDays(FROM, FROM.minusDays(1))).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> withDays(FROM, LocalDate.parse("2101-01-01"))).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThat(withDays(LocalDate.parse("2000-01-01"), LocalDate.parse("2100-12-31")).validTo())
                .isEqualTo(LocalDate.parse("2100-12-31"));
    }

    @Test
    void theDaysOfAnAssignmentNeedBothEndsInsideTheValidityOfTheDiscount() {
        Discount discount = withDays(FROM, TO);

        assertThatThrownBy(() -> discount.requireAssignable(null, TO)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> discount.requireAssignable(TO.plusDays(1), TO.plusDays(2)))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> discount.requireAssignable(FROM, null)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> discount.requireAssignable(FROM.minusDays(2), FROM.minusDays(1)))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        discount.requireAssignable(TO, TO);
    }

    @Test
    void theConceptsOfADiscountAreOneOrMoreAndNeverNull() {
        Set<UUID> withNull = new HashSet<>();
        withNull.add(null);

        assertThatThrownBy(() -> withConcepts(null)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> withConcepts(Set.of())).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> withConcepts(withNull)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void anApprovalStateIsFoundByItsCodeAndAnUnknownOneIsAbsent() {
        for (ApprovalStatus status : ApprovalStatus.values()) {
            assertThat(ApprovalStatus.ofCode(status.code())).contains(status);
        }
        assertThat(ApprovalStatus.ofCode("approvedish")).isEqualTo(Optional.empty());
    }

    @Test
    void aDecisionIsOfApprovalOrRejectionWithItsReasonOnlyWhenItRejectsAndNeverOfAVersionBelowZero() {
        String hash = "a".repeat(64);
        assertThat(new DiscountDecision(UUID.randomUUID(), UUID.randomUUID(), 0, DecisionKind.APPROVED, null, PERSON, NOW, "k", hash)
                .answers(hash)).isTrue();
        assertThat(new DiscountDecision(UUID.randomUUID(), UUID.randomUUID(), 0, DecisionKind.REJECTED, "El motivo del rechazo",
                PERSON, NOW, "k", hash).toString()).doesNotContain("motivo");

        assertThatThrownBy(() -> new DiscountDecision(UUID.randomUUID(), UUID.randomUUID(), 0, DecisionKind.REVOKED,
                "El motivo de la revocacion", PERSON, NOW, "k", hash)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new DiscountDecision(UUID.randomUUID(), UUID.randomUUID(), -1, DecisionKind.APPROVED, null, PERSON,
                NOW, "k", hash)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new DiscountDecision(UUID.randomUUID(), UUID.randomUUID(), 0, DecisionKind.APPROVED, "motivo", PERSON,
                NOW, "k", hash)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new DiscountDecision(UUID.randomUUID(), UUID.randomUUID(), 0, DecisionKind.REJECTED, null, PERSON,
                NOW, "k", hash)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aBenefitWidensOnlyWhenItGivesMoreOfTheSameTypeOrIsOfAnotherType() {
        assertThat(Benefit.fixed("10.01").widens(Benefit.fixed("10.00"))).isTrue();
        assertThat(Benefit.fixed("10.00").widens(Benefit.fixed("10.00"))).isFalse();
        assertThat(Benefit.fixed("9.99").widens(Benefit.fixed("10.00"))).isFalse();
        assertThat(Benefit.percentage("10.01").widens(Benefit.percentage("10"))).isTrue();
        assertThat(Benefit.percentage("10").widens(Benefit.percentage("10"))).isFalse();
        assertThat(Benefit.percentage("9").widens(Benefit.percentage("10"))).isFalse();
        assertThat(Benefit.percentage("1").widens(Benefit.fixed("100.00"))).isTrue();
        assertThat(Benefit.fixed("1.00").widens(Benefit.percentage("100"))).isTrue();
        assertThat(Benefit.fixed("1.00").sameAs(Benefit.fixed("1.00"))).isTrue();
        assertThat(Benefit.fixed("1.00").sameAs(Benefit.fixed("2.00"))).isFalse();
        assertThat(Benefit.fixed("1.00").sameAs(Benefit.percentage("1"))).isFalse();
    }

    @Test
    void theTermsOfAnAssignmentAreMissingOrChangedWhetherTheVersionTheBenefitOrNeitherIsAbsent() {
        Discount discount = withDays(FROM, TO);
        DiscountAssignment noVersion = new DiscountAssignment(UUID.randomUUID(), discount.id(), UUID.randomUUID(), FROM, TO,
                AwardStatus.PENDING, PERSON, NOW, null, discount.benefit());
        DiscountAssignment noBenefit = new DiscountAssignment(UUID.randomUUID(), discount.id(), UUID.randomUUID(), FROM, TO,
                AwardStatus.PENDING, PERSON, NOW, discount.version(), null);
        DiscountAssignment otherBenefit = new DiscountAssignment(UUID.randomUUID(), discount.id(), UUID.randomUUID(), FROM, TO,
                AwardStatus.PENDING, PERSON, NOW, discount.version(), Benefit.percentage("11"));
        DiscountAssignment current = new DiscountAssignment(UUID.randomUUID(), discount.id(), UUID.randomUUID(), FROM, TO,
                AwardStatus.PENDING, PERSON, NOW, discount.version(), discount.benefit());

        assertThatThrownBy(() -> noVersion.requireTermsOf(discount)).isInstanceOf(DiscountTermsChangedException.class);
        assertThatThrownBy(() -> noBenefit.requireTermsOf(discount)).isInstanceOf(DiscountTermsChangedException.class);
        assertThatThrownBy(() -> otherBenefit.requireTermsOf(discount)).isInstanceOf(DiscountTermsChangedException.class);
        current.requireTermsOf(discount);
    }

    @Test
    void theDaysOfAnAssignmentAreInsideTheWindowAndInOrder() {
        assertThatThrownBy(() -> assignmentOn(null, TO)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> assignmentOn(LocalDate.parse("1999-12-31"), TO)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> assignmentOn(LocalDate.parse("2101-01-01"), LocalDate.parse("2101-01-02")))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> assignmentOn(FROM, null)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> assignmentOn(FROM, FROM.minusDays(1))).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> assignmentOn(FROM, LocalDate.parse("2101-01-01"))).isInstanceOf(InvalidScholarshipsValueException.class);
    }
}

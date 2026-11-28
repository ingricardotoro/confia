package com.confia.scholarships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The life of a discount assignment and its decisions (docs/cortes/scholarships-discounts.md, "Clases de descuento"). */
class DiscountAssignmentTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final String KEY = "key-1";
    private static final String HASH = "a".repeat(64);
    private static final LocalDate FROM = LocalDate.parse("2027-02-01");
    private static final LocalDate TO = LocalDate.parse("2027-06-30");
    private static final DecisionReason REASON = DecisionReason.of("El convenio ya no esta vigente para el estudiante");

    private static Discount agreement() {
        return Discount.create(UUID.randomUUID(), "CONV", "Convenio", DiscountKind.AGREEMENT, Benefit.percentage("10"), FROM, TO,
                Set.of(UUID.randomUUID()), true, UUID.randomUUID(), NOW);
    }

    private static DiscountAssignment pending() {
        return DiscountAssignment.request(UUID.randomUUID(), agreement(), UUID.randomUUID(), FROM, TO, REQUESTER, NOW);
    }

    private static DiscountAssignment approved() {
        DiscountAssignment assignment = pending();
        return assignment.decided(assignment.approve(UUID.randomUUID(), APPROVER, NOW, KEY, HASH));
    }

    // --- Birth ---

    @Test
    void anAssignmentIsBornPending() {
        assertThat(pending().status()).isEqualTo(AwardStatus.PENDING);
    }

    @Test
    void anAssignmentOfACampaignDiscountIsRefused() {
        Discount campaign = Discount.create(UUID.randomUUID(), "CAMP", "Campana", DiscountKind.CAMPAIGN, Benefit.percentage("5"),
                FROM, TO, Set.of(UUID.randomUUID()), true, UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> DiscountAssignment.request(UUID.randomUUID(), campaign, UUID.randomUUID(), FROM, TO, REQUESTER,
                NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void anAssignmentOfAnInactiveDiscountIsRefused() {
        Discount inactive = agreement().withActive(false, NOW);

        assertThatThrownBy(() -> DiscountAssignment.request(UUID.randomUUID(), inactive, UUID.randomUUID(), FROM, TO, REQUESTER,
                NOW)).isInstanceOf(InactiveReferenceException.class);
    }

    @Test
    void theValidityOfAnAssignmentIsInsideTheOneOfTheDiscountBothEndsIncluded() {
        Discount discount = agreement();
        UUID student = UUID.randomUUID();

        assertThat(DiscountAssignment.request(UUID.randomUUID(), discount, student, FROM, FROM, REQUESTER, NOW).validTo())
                .isEqualTo(FROM);
        assertThat(DiscountAssignment.request(UUID.randomUUID(), discount, student, TO, TO, REQUESTER, NOW).validFrom())
                .isEqualTo(TO);
        assertThatThrownBy(() -> DiscountAssignment.request(UUID.randomUUID(), discount, student, FROM.minusDays(1), TO,
                REQUESTER, NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> DiscountAssignment.request(UUID.randomUUID(), discount, student, FROM, TO.plusDays(1),
                REQUESTER, NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aValidityThatEndsBeforeItStartsOrIsMissingIsRefused() {
        Discount discount = agreement();
        UUID student = UUID.randomUUID();

        assertThatThrownBy(() -> DiscountAssignment.request(UUID.randomUUID(), discount, student, TO, FROM, REQUESTER, NOW))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> DiscountAssignment.request(UUID.randomUUID(), discount, student, null, TO, REQUESTER, NOW))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> DiscountAssignment.request(UUID.randomUUID(), discount, student, FROM, null, REQUESTER, NOW))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    // --- Decisions ---

    @Test
    void anotherPersonApprovesAndTheAssignmentIsApproved() {
        DiscountAssignment assignment = pending();

        AssignmentDecision decision = assignment.approve(UUID.randomUUID(), APPROVER, NOW, KEY, HASH);

        assertThat(decision.kind()).isEqualTo(DecisionKind.APPROVED);
        assertThat(decision.reason()).isNull();
        assertThat(decision.assignmentId()).isEqualTo(assignment.id());
        assertThat(assignment.decided(decision).status()).isEqualTo(AwardStatus.APPROVED);
    }

    @Test
    void theRequesterNeitherApprovesNorRejects() {
        DiscountAssignment assignment = pending();

        assertThatThrownBy(() -> assignment.approve(UUID.randomUUID(), REQUESTER, NOW, KEY, HASH))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> assignment.reject(UUID.randomUUID(), REQUESTER, REASON, NOW, KEY, HASH))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }

    @Test
    void aRejectionCarriesItsReasonAndLeadsToRejected() {
        DiscountAssignment assignment = pending();

        AssignmentDecision decision = assignment.reject(UUID.randomUUID(), APPROVER, REASON, NOW, KEY, HASH);

        assertThat(decision.reason()).isEqualTo(REASON.text());
        assertThat(assignment.decided(decision).status()).isEqualTo(AwardStatus.REJECTED);
    }

    @Test
    void onlyAPendingAssignmentIsApprovedOrRejected() {
        DiscountAssignment approved = approved();
        DiscountAssignment rejected = pending();
        rejected = rejected.decided(rejected.reject(UUID.randomUUID(), APPROVER, REASON, NOW, "k2", HASH));
        for (DiscountAssignment decided : new DiscountAssignment[] {approved, rejected}) {
            assertThatThrownBy(() -> decided.approve(UUID.randomUUID(), APPROVER, NOW, KEY, HASH))
                    .isInstanceOf(InvalidAssignmentStateException.class);
            assertThatThrownBy(() -> decided.reject(UUID.randomUUID(), APPROVER, REASON, NOW, KEY, HASH))
                    .isInstanceOf(InvalidAssignmentStateException.class);
        }
    }

    @Test
    void onlyAnApprovedAssignmentIsRevokedAndTheRequesterMayRevoke() {
        DiscountAssignment approved = approved();

        AssignmentDecision decision = approved.revoke(UUID.randomUUID(), REQUESTER, REASON, NOW, "k3", HASH);

        assertThat(decision.kind()).isEqualTo(DecisionKind.REVOKED);
        assertThat(approved.decided(decision).status()).isEqualTo(AwardStatus.REVOKED);
        assertThatThrownBy(() -> pending().revoke(UUID.randomUUID(), APPROVER, REASON, NOW, KEY, HASH))
                .isInstanceOf(InvalidAssignmentStateException.class);
        DiscountAssignment revoked = approved.decided(decision);
        assertThatThrownBy(() -> revoked.revoke(UUID.randomUUID(), APPROVER, REASON, NOW, KEY, HASH))
                .isInstanceOf(InvalidAssignmentStateException.class);
    }

    @Test
    void aReasonOutOfItsBoundsIsRefusedBeforeAnyDecision() {
        assertThatThrownBy(() -> DecisionReason.of("corto")).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> DecisionReason.of("x".repeat(501))).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThat(DecisionReason.of("x".repeat(20)).length()).isEqualTo(20);
        assertThat(DecisionReason.of("x".repeat(500)).length()).isEqualTo(500);
    }

    @Test
    void aDecisionWithTheWrongReasonShapeIsRefused() {
        assertThatThrownBy(() -> new AssignmentDecision(UUID.randomUUID(), UUID.randomUUID(), DecisionKind.APPROVED, "why",
                APPROVER, NOW, KEY, HASH)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new AssignmentDecision(UUID.randomUUID(), UUID.randomUUID(), DecisionKind.REJECTED, null,
                APPROVER, NOW, KEY, HASH)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aDecisionAnswersOnlyTheRequestWithTheSameDigestAndNeverShowsItsReason() {
        DiscountAssignment assignment = pending();
        AssignmentDecision decision = assignment.reject(UUID.randomUUID(), APPROVER, REASON, NOW, KEY, HASH);

        assertThat(decision.answers(HASH)).isTrue();
        assertThat(decision.answers("b".repeat(64))).isFalse();
        assertThat(decision.toString()).doesNotContain(REASON.text());
    }

    // --- In force ---

    @Test
    void onlyAnApprovedAssignmentAppliesOnItsDaysBothEndsIncluded() {
        DiscountAssignment approved = approved();

        assertThat(approved.appliesOn(FROM)).isTrue();
        assertThat(approved.appliesOn(TO)).isTrue();
        assertThat(approved.appliesOn(FROM.minusDays(1))).isFalse();
        assertThat(approved.appliesOn(TO.plusDays(1))).isFalse();
        assertThat(pending().appliesOn(FROM)).isFalse();
        DiscountAssignment rejected = pending();
        rejected = rejected.decided(rejected.reject(UUID.randomUUID(), APPROVER, REASON, NOW, KEY, HASH));
        assertThat(rejected.appliesOn(FROM)).isFalse();
        DiscountAssignment revoked = approved.decided(approved.revoke(UUID.randomUUID(), APPROVER, REASON, NOW, "k4", HASH));
        assertThat(revoked.appliesOn(FROM)).isFalse();
    }
}

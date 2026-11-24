package com.confia.scholarships.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The life of an award and its decisions (docs/cortes/scholarships-awards.md, "Ciclo del otorgamiento" and "Vigencia"). */
class ScholarshipAwardTest {

    private static final Instant NOW = Instant.parse("2027-02-10T18:00:00Z");
    private static final UUID REQUESTER = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final String KEY = "key-1";
    private static final String HASH = "a".repeat(64);
    private static final String REASON = "La familia ya no cumple los requisitos";

    private static ScholarshipAward pending() {
        return ScholarshipAward.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2027-02-01"), LocalDate.parse("2027-06-30"), REQUESTER, NOW);
    }

    private static ScholarshipAward approved() {
        ScholarshipAward award = pending();
        return award.decided(award.approve(UUID.randomUUID(), APPROVER, NOW, KEY, HASH));
    }

    // --- Birth and validity ---

    @Test
    void anAwardIsBornPending() {
        assertThat(pending().status()).isEqualTo(AwardStatus.PENDING);
    }

    @Test
    void aValidityThatEndsBeforeItStartsIsRefusedAndOneDayIsAccepted() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> ScholarshipAward.request(id, id, id, id, LocalDate.parse("2027-03-02"),
                LocalDate.parse("2027-03-01"), REQUESTER, NOW)).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("validTo");
        ScholarshipAward oneDay = ScholarshipAward.request(id, id, id, id, LocalDate.parse("2027-03-01"),
                LocalDate.parse("2027-03-01"), REQUESTER, NOW);
        assertThat(oneDay.validFrom()).isEqualTo(oneDay.validTo());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1999-12-31", "2101-01-01"})
    void aValidityOutsideTheWindowOfTheDatabaseIsRefused(String day) {
        UUID id = UUID.randomUUID();
        LocalDate bad = LocalDate.parse(day);
        assertThatThrownBy(() -> ScholarshipAward.request(id, id, id, id, bad, bad, REQUESTER, NOW))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void aMissingDayIsARefusedValidity() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> ScholarshipAward.request(id, id, id, id, null, LocalDate.parse("2027-03-01"), REQUESTER,
                NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> ScholarshipAward.request(id, id, id, id, LocalDate.parse("2027-03-01"), null, REQUESTER,
                NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> ScholarshipAward.request(id, id, id, id, LocalDate.parse("2027-03-01"),
                LocalDate.parse("2101-01-01"), REQUESTER, NOW)).isInstanceOf(InvalidScholarshipsValueException.class);
    }

    // --- Approve ---

    @Test
    void anotherPersonApprovesAPendingAward() {
        ScholarshipAward award = pending();

        AwardDecision decision = award.approve(UUID.randomUUID(), APPROVER, NOW, KEY, HASH);
        ScholarshipAward after = award.decided(decision);

        assertThat(decision.kind()).isEqualTo(DecisionKind.APPROVED);
        assertThat(decision.reason()).isNull();
        assertThat(decision.decidedBy()).isEqualTo(APPROVER);
        assertThat(decision.answers(HASH)).isTrue();
        assertThat(decision.answers("b".repeat(64))).isFalse();
        assertThat(after.status()).isEqualTo(AwardStatus.APPROVED);
    }

    @Test
    void whoRequestedAnAwardNeitherApprovesNorRejectsIt() {
        ScholarshipAward award = pending();

        assertThatThrownBy(() -> award.approve(UUID.randomUUID(), REQUESTER, NOW, KEY, HASH))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        assertThatThrownBy(() -> award.reject(UUID.randomUUID(), REQUESTER, DecisionReason.of(REASON), NOW, KEY, HASH))
                .isInstanceOf(SelfApprovalForbiddenException.class);
    }

    @Test
    void onlyAPendingAwardIsApprovedOrRejected() {
        ScholarshipAward approved = approved();
        ScholarshipAward rejected = pending();
        rejected = rejected.decided(rejected.reject(UUID.randomUUID(), APPROVER, DecisionReason.of(REASON), NOW, KEY, HASH));
        ScholarshipAward revoked = approved.decided(approved.revoke(UUID.randomUUID(), APPROVER, DecisionReason.of(REASON),
                NOW, KEY, HASH));

        for (ScholarshipAward award : new ScholarshipAward[] {approved, rejected, revoked}) {
            assertThatThrownBy(() -> award.approve(UUID.randomUUID(), APPROVER, NOW, KEY, HASH))
                    .isInstanceOf(InvalidAwardStateException.class);
            assertThatThrownBy(() -> award.reject(UUID.randomUUID(), APPROVER, DecisionReason.of(REASON), NOW, KEY, HASH))
                    .isInstanceOf(InvalidAwardStateException.class);
        }
    }

    @Test
    void theStateIsReportedBeforeTheSelfApproval() {
        ScholarshipAward approved = approved();

        assertThatThrownBy(() -> approved.approve(UUID.randomUUID(), REQUESTER, NOW, KEY, HASH))
                .isInstanceOf(InvalidAwardStateException.class);
    }

    // --- Reject ---

    @Test
    void rejectingKeepsTheReasonInTheDecision() {
        ScholarshipAward award = pending();

        AwardDecision decision = award.reject(UUID.randomUUID(), APPROVER, DecisionReason.of("  " + REASON + "  "), NOW, KEY,
                HASH);

        assertThat(decision.kind()).isEqualTo(DecisionKind.REJECTED);
        assertThat(decision.reason()).isEqualTo(REASON);
        assertThat(award.decided(decision).status()).isEqualTo(AwardStatus.REJECTED);
        assertThat(decision.toString()).doesNotContain(REASON);
    }

    // --- Revoke ---

    @Test
    void anApprovedAwardIsRevokedAndTheRequesterMayDoIt() {
        ScholarshipAward award = approved();

        AwardDecision decision = award.revoke(UUID.randomUUID(), REQUESTER, DecisionReason.of(REASON), NOW, "key-2", HASH);

        assertThat(decision.kind()).isEqualTo(DecisionKind.REVOKED);
        assertThat(award.decided(decision).status()).isEqualTo(AwardStatus.REVOKED);
        assertThat(award.decided(decision).appliesOn(LocalDate.parse("2027-03-01"))).isFalse();
    }

    @Test
    void aPendingRejectedOrRevokedAwardIsNotRevoked() {
        ScholarshipAward pending = pending();
        ScholarshipAward revoked = approved();
        revoked = revoked.decided(revoked.revoke(UUID.randomUUID(), APPROVER, DecisionReason.of(REASON), NOW, KEY, HASH));
        ScholarshipAward rejected = pending();
        rejected = rejected.decided(rejected.reject(UUID.randomUUID(), APPROVER, DecisionReason.of(REASON), NOW, KEY, HASH));

        for (ScholarshipAward award : new ScholarshipAward[] {pending, revoked, rejected}) {
            assertThatThrownBy(() -> award.revoke(UUID.randomUUID(), APPROVER, DecisionReason.of(REASON), NOW, KEY, HASH))
                    .isInstanceOf(InvalidAwardStateException.class);
        }
    }

    // --- The reason ---

    @Test
    void theReasonIsBetweenTwentyAndFiveHundredCharacters() {
        assertThat(DecisionReason.of("x".repeat(20)).length()).isEqualTo(20);
        assertThat(DecisionReason.of("x".repeat(500)).length()).isEqualTo(500);
        assertThat(DecisionReason.of("  " + "x".repeat(20) + "  ").text()).hasSize(20);
        assertThatThrownBy(() -> DecisionReason.of("x".repeat(19))).isInstanceOf(InvalidScholarshipsValueException.class)
                .extracting(e -> ((InvalidScholarshipsValueException) e).field()).isEqualTo("reason");
        assertThatThrownBy(() -> DecisionReason.of("x".repeat(501))).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> DecisionReason.of(" ".repeat(30))).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> DecisionReason.of(null)).isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> DecisionReason.of("x".repeat(25) + "\n" + "y".repeat(5)))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    @Test
    void theReasonCountsCodePointsAndNeverShowsItself() {
        // Twenty emoji are twenty code points (forty chars).
        assertThat(DecisionReason.of("😀".repeat(20)).length()).isEqualTo(20);
        assertThat(DecisionReason.of(REASON).toString()).doesNotContain("familia").contains("length=");
    }

    @Test
    void aDecisionCarriesAReasonOnlyWhenItIsARejectionOrARevocation() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> new AwardDecision(id, id, DecisionKind.APPROVED, REASON, APPROVER, NOW, KEY, HASH))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new AwardDecision(id, id, DecisionKind.REJECTED, null, APPROVER, NOW, KEY, HASH))
                .isInstanceOf(InvalidScholarshipsValueException.class);
        assertThatThrownBy(() -> new AwardDecision(id, id, DecisionKind.REVOKED, null, APPROVER, NOW, KEY, HASH))
                .isInstanceOf(InvalidScholarshipsValueException.class);
    }

    // --- INV-16: when the engine applies an award ---

    @Test
    void onlyAnApprovedAwardAppliesAndOnlyInsideItsValidityBothEndsIncluded() {
        ScholarshipAward approved = approved();

        assertThat(approved.appliesOn(LocalDate.parse("2027-01-31"))).isFalse();
        assertThat(approved.appliesOn(LocalDate.parse("2027-02-01"))).isTrue();
        assertThat(approved.appliesOn(LocalDate.parse("2027-03-15"))).isTrue();
        assertThat(approved.appliesOn(LocalDate.parse("2027-06-30"))).isTrue();
        assertThat(approved.appliesOn(LocalDate.parse("2027-07-01"))).isFalse();
    }

    @Test
    void aPendingOrARejectedAwardNeverApplies() {
        ScholarshipAward pending = pending();
        ScholarshipAward rejected = pending.decided(pending.reject(UUID.randomUUID(), APPROVER, DecisionReason.of(REASON), NOW,
                KEY, HASH));

        assertThat(pending.appliesOn(LocalDate.parse("2027-03-15"))).isFalse();
        assertThat(rejected.appliesOn(LocalDate.parse("2027-03-15"))).isFalse();
    }

    // --- Codes ---

    @Test
    void theStatusesAndDecisionsHaveStableCodes() {
        for (AwardStatus status : AwardStatus.values()) {
            assertThat(AwardStatus.ofCode(status.code())).contains(status);
        }
        assertThat(AwardStatus.ofCode("nope")).isEmpty();
        for (DecisionKind kind : DecisionKind.values()) {
            assertThat(DecisionKind.ofCode(kind.code())).isEqualTo(kind);
        }
        assertThatThrownBy(() -> DecisionKind.ofCode("nope")).isInstanceOf(IllegalArgumentException.class);
        assertThat(DecisionKind.APPROVED.needsReason()).isFalse();
        assertThat(DecisionKind.REJECTED.needsReason()).isTrue();
        assertThat(DecisionKind.REVOKED.needsReason()).isTrue();
    }

    @Test
    void everyExceptionPublishesItsCode() {
        assertThat(new ResourceNotFoundException().code()).isEqualTo("resource-not-found");
        assertThat(new StaleVersionException().code()).isEqualTo("stale-version");
        assertThat(new InactiveReferenceException().code()).isEqualTo("inactive-reference");
        assertThat(new IdempotencyKeyRequiredException().code()).isEqualTo("idempotency-key-missing");
        assertThat(new SelfApprovalForbiddenException().code()).isEqualTo("self-approval-forbidden");
        assertThat(new DuplicateScholarshipCodeException().code()).isEqualTo("duplicate-scholarship-code");
        assertThat(new ScholarshipBenefitLockedException().code()).isEqualTo("scholarship-benefit-locked");
        assertThat(new AwardOverlapException().code()).isEqualTo("scholarship-award-overlap");
        assertThat(new InvalidAwardStateException().code()).isEqualTo("invalid-award-state");
        assertThat(new InvalidScholarshipsValueException().code()).isEqualTo("validation-failed");
    }
}

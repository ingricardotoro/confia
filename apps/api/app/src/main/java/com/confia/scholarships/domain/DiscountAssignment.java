package com.confia.scholarships.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The assignment of an agreement discount to a student, with a validity (both ends included) inside the one of the discount. It is
 * born {@code pending}; a person authorized who is not the one who requested it approves or rejects it (segregation of duties), and
 * an approved one can be revoked. Rejecting and revoking carry a reason. It never goes back and nothing is deleted. A student has at
 * most one approved assignment of a discount on any day; the database enforces it with an exclusion constraint, and enforces that the
 * validity lies inside the one of the discount, which it owns.
 *
 * <p>The accrual engine applies an assignment only when it is approved and in force on the issue date of the charge ({@link
 * #appliesOn}), and so only to the charges that are issued after the approval: an issued charge never changes. The life of an
 * assignment uses the statuses of an award ({@link AwardStatus}) and the decisions of {@link DecisionKind}: they are the same cycle.
 */
public record DiscountAssignment(UUID id, UUID discountId, UUID studentId, LocalDate validFrom, LocalDate validTo,
        AwardStatus status, UUID requestedBy, Instant requestedAt) {

    private static final LocalDate WINDOW_START = LocalDate.of(2000, 1, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2100, 12, 31);

    public DiscountAssignment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(discountId, "discountId");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (validFrom == null || validFrom.isBefore(WINDOW_START) || validFrom.isAfter(WINDOW_END)) {
            throw new InvalidScholarshipsValueException("validFrom", "invalid");
        }
        if (validTo == null || validTo.isBefore(validFrom) || validTo.isAfter(WINDOW_END)) {
            throw new InvalidScholarshipsValueException("validTo", "invalid");
        }
    }

    /**
     * The request of an assignment of {@code discount}: born pending.
     *
     * @throws InvalidScholarshipsValueException when the discount is not of agreement kind or the days are outside its validity
     * @throws InactiveReferenceException when the discount is inactive
     */
    public static DiscountAssignment request(UUID id, Discount discount, UUID studentId, LocalDate validFrom, LocalDate validTo,
            UUID requestedBy, Instant now) {
        Objects.requireNonNull(discount, "discount");
        if (validFrom == null || validTo == null || validTo.isBefore(validFrom)) {
            throw new InvalidScholarshipsValueException("validTo", "invalid");
        }
        discount.requireAssignable(validFrom, validTo);
        return new DiscountAssignment(id, discount.id(), studentId, validFrom, validTo, AwardStatus.PENDING, requestedBy, now);
    }

    /**
     * The approval by {@code by}.
     *
     * @throws InvalidAssignmentStateException when the assignment is not pending
     * @throws SelfApprovalForbiddenException when {@code by} requested the assignment
     */
    public AssignmentDecision approve(UUID decisionId, UUID by, Instant now, String requestKey, String requestHash) {
        requireDecidableBy(by);
        return new AssignmentDecision(decisionId, id, DecisionKind.APPROVED, null, by, now, requestKey, requestHash);
    }

    /**
     * The rejection by {@code by}, with the reason.
     *
     * @throws InvalidAssignmentStateException when the assignment is not pending
     * @throws SelfApprovalForbiddenException when {@code by} requested the assignment
     */
    public AssignmentDecision reject(UUID decisionId, UUID by, DecisionReason reason, Instant now, String requestKey,
            String requestHash) {
        requireDecidableBy(by);
        return new AssignmentDecision(decisionId, id, DecisionKind.REJECTED, reason.text(), by, now, requestKey, requestHash);
    }

    /**
     * The revocation by {@code by}, with the reason. It affects only the charges that are issued afterwards. Whoever requested the
     * assignment may revoke it: the segregation applies to granting, not to taking back.
     *
     * @throws InvalidAssignmentStateException when the assignment is not approved
     */
    public AssignmentDecision revoke(UUID decisionId, UUID by, DecisionReason reason, Instant now, String requestKey,
            String requestHash) {
        Objects.requireNonNull(by, "by");
        if (status != AwardStatus.APPROVED) {
            throw new InvalidAssignmentStateException();
        }
        return new AssignmentDecision(decisionId, id, DecisionKind.REVOKED, reason.text(), by, now, requestKey, requestHash);
    }

    /** This assignment once the decision is recorded. */
    public DiscountAssignment decided(AssignmentDecision decision) {
        return new DiscountAssignment(id, discountId, studentId, validFrom, validTo, decision.kind().leadsTo(), requestedBy,
                requestedAt);
    }

    /**
     * Checks that {@code by} may approve or reject this assignment now, before anything is written.
     *
     * @throws InvalidAssignmentStateException when the assignment is not pending
     * @throws SelfApprovalForbiddenException when {@code by} requested the assignment
     */
    public void requireDecidableBy(UUID by) {
        Objects.requireNonNull(by, "by");
        if (status != AwardStatus.PENDING) {
            throw new InvalidAssignmentStateException();
        }
        if (requestedBy.equals(by)) {
            throw new SelfApprovalForbiddenException();
        }
    }

    /** Whether the assignment is approved and its validity includes the day, both ends included. */
    public boolean appliesOn(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return status == AwardStatus.APPROVED && !day.isBefore(validFrom) && !day.isAfter(validTo);
    }

    @Override
    public String toString() {
        return "DiscountAssignment[id=" + id + ", discountId=" + discountId + ", status=" + status + "]";
    }
}

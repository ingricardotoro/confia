package com.confia.scholarships.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The award of a scholarship to a student for an academic year, with a validity inside that year. It is born {@code pending}; a
 * person authorized who is not the one who requested it approves or rejects it (segregation of duties), and an approved one can be
 * revoked. Rejecting and revoking carry a reason. It never goes back and nothing is deleted. A student has at most one approved
 * award of a scholarship on any day; the database enforces it with an exclusion constraint. That an award lies inside its academic
 * year is checked by the database, which owns the dates of the year.
 *
 * <p>INV-16: the accrual engine applies an award only when it is approved and in force on the issue date of the charge
 * ({@link #appliesOn}), and so only to the charges that are issued after the approval: an issued charge never changes.
 */
public record ScholarshipAward(UUID id, UUID scholarshipId, UUID studentId, UUID academicYearId, LocalDate validFrom,
        LocalDate validTo, AwardStatus status, UUID requestedBy, Instant requestedAt) {

    private static final LocalDate WINDOW_START = LocalDate.of(2000, 1, 1);
    private static final LocalDate WINDOW_END = LocalDate.of(2100, 12, 31);

    public ScholarshipAward {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(scholarshipId, "scholarshipId");
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(academicYearId, "academicYearId");
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

    /** The request of an award: born pending. */
    public static ScholarshipAward request(UUID id, UUID scholarshipId, UUID studentId, UUID academicYearId, LocalDate validFrom,
            LocalDate validTo, UUID requestedBy, Instant now) {
        return new ScholarshipAward(id, scholarshipId, studentId, academicYearId, validFrom, validTo, AwardStatus.PENDING,
                requestedBy, now);
    }

    /**
     * The approval by {@code by}.
     *
     * @throws InvalidAwardStateException when the award is not pending
     * @throws SelfApprovalForbiddenException when {@code by} requested the award
     */
    public AwardDecision approve(UUID decisionId, UUID by, Instant now, String requestKey, String requestHash) {
        requireDecidableBy(by);
        return new AwardDecision(decisionId, id, DecisionKind.APPROVED, null, by, now, requestKey, requestHash);
    }

    /**
     * The rejection by {@code by}, with the reason.
     *
     * @throws InvalidAwardStateException when the award is not pending
     * @throws SelfApprovalForbiddenException when {@code by} requested the award
     */
    public AwardDecision reject(UUID decisionId, UUID by, DecisionReason reason, Instant now, String requestKey,
            String requestHash) {
        requireDecidableBy(by);
        return new AwardDecision(decisionId, id, DecisionKind.REJECTED, reason.text(), by, now, requestKey, requestHash);
    }

    /**
     * The revocation by {@code by}, with the reason. It affects only the charges that are issued afterwards. Whoever requested the
     * award may revoke it: the segregation applies to granting, not to taking back.
     *
     * @throws InvalidAwardStateException when the award is not approved
     */
    public AwardDecision revoke(UUID decisionId, UUID by, DecisionReason reason, Instant now, String requestKey,
            String requestHash) {
        Objects.requireNonNull(by, "by");
        if (status != AwardStatus.APPROVED) {
            throw new InvalidAwardStateException();
        }
        return new AwardDecision(decisionId, id, DecisionKind.REVOKED, reason.text(), by, now, requestKey, requestHash);
    }

    /** This award once the decision is recorded. */
    public ScholarshipAward decided(AwardDecision decision) {
        return new ScholarshipAward(id, scholarshipId, studentId, academicYearId, validFrom, validTo,
                decision.kind().leadsTo(), requestedBy, requestedAt);
    }

    /**
     * Checks that {@code by} may approve or reject this award now, before anything is written.
     *
     * @throws InvalidAwardStateException when the award is not pending
     * @throws SelfApprovalForbiddenException when {@code by} requested the award
     */
    public void requireDecidableBy(UUID by) {
        Objects.requireNonNull(by, "by");
        if (status != AwardStatus.PENDING) {
            throw new InvalidAwardStateException();
        }
        if (requestedBy.equals(by)) {
            throw new SelfApprovalForbiddenException();
        }
    }

    /** Whether the award is approved and its validity includes the day, both ends included (INV-16). */
    public boolean appliesOn(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return status == AwardStatus.APPROVED && !day.isBefore(validFrom) && !day.isAfter(validTo);
    }

    @Override
    public String toString() {
        return "ScholarshipAward[id=" + id + ", scholarshipId=" + scholarshipId + ", status=" + status + "]";
    }
}

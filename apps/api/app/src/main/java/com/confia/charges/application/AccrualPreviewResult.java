package com.confia.charges.application;

import com.confia.charges.domain.AccrualErrorCode;
import com.confia.charges.domain.AccrualRejection;
import com.confia.charges.domain.AppliedBenefit;
import com.confia.kernel.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an accrual would do, without writing anything: the counts by outcome, the totals by currency (exact sums), the errors
 * by code, the first {@link #MAX_ITEMS} items, the preconditions that would make a run refuse, and the digest of the whole
 * calculation. The digest travels back with the confirmation: if the calculation changed in between, the run is rejected.
 */
public record AccrualPreviewResult(UUID planId, LocalDate periodStart, int studentsEvaluated, int toCreate,
        int alreadyAccrued, int alreadyAccruedOtherPlan, int errors, List<Total> totals,
        Map<AccrualErrorCode, Integer> errorsByCode, List<Item> items, boolean itemsTruncated,
        List<AccrualRejection> blockers, String previewDigest) {

    public static final int MAX_ITEMS = 500;

    public AccrualPreviewResult {
        totals = List.copyOf(totals);
        errorsByCode = Map.copyOf(errorsByCode);
        items = List.copyOf(items);
        blockers = List.copyOf(blockers);
    }

    /** The errors by code as text, sorted by code. */
    public Map<String, Integer> errorCounts() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        errorsByCode.forEach((code, count) -> counts.put(code.name(), count));
        return counts;
    }

    /** The codes of what would make a run refuse, as text. */
    public List<String> blockerCodes() {
        return blockers.stream().map(AccrualRejection::code).toList();
    }

    /** What the charges to create add up to in one currency: the benefit and the net base are the scholarships' share. */
    public record Total(String currency, int charges, Money base, Money benefit, Money netBase, Money tax, Money total) {
    }

    /**
     * One student and concept of the preview; the amounts are present only for a charge to create. The benefit is zero and the
     * award and the scholarship code are absent when no scholarship reduces the charge. The {@code benefits} are every benefit that
     * reduced the charge (the scholarship and the discounts), in the order they were applied.
     */
    public record Item(UUID studentId, UUID paymentConceptId, AccrualOutcome outcome, AccrualErrorCode errorCode,
            Money base, Money benefit, Money netBase, Money tax, Money total, UUID awardId, String scholarshipCode,
            UUID existingChargeId, List<AppliedBenefit> benefits) {

        public Item {
            benefits = benefits == null ? List.of() : List.copyOf(benefits);
        }

        /** The code of the error as text, or {@code null} when there is none. */
        public String error() {
            return errorCode == null ? null : errorCode.name();
        }
    }
}

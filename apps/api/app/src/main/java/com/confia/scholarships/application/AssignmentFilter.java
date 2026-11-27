package com.confia.scholarships.application;

import java.util.Set;
import java.util.UUID;

/**
 * The filters of the list of discount assignments: the student, the status code ({@code pending}, {@code approved}, {@code
 * rejected} or {@code revoked}) and the discount. Every member may be null.
 */
public record AssignmentFilter(UUID studentId, String status, UUID discountId) {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("requestedAt", "validFrom", "validTo");
}

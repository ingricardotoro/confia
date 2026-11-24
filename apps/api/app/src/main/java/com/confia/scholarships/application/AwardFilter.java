package com.confia.scholarships.application;

import java.util.Set;
import java.util.UUID;

/**
 * The filters of the list of awards: the student, the status code ({@code pending}, {@code approved}, {@code rejected} or {@code
 * revoked}), the scholarship and the academic year. Every member may be null.
 */
public record AwardFilter(UUID studentId, String status, UUID scholarshipId, UUID academicYearId) {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("requestedAt", "validFrom", "validTo");
}

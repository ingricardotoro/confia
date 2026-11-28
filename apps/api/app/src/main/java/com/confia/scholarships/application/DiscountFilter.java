package com.confia.scholarships.application;

import java.util.Set;

/**
 * The filters of the list of discounts: active or inactive, the kind code ({@code campaign} or {@code agreement}) and the type of
 * benefit ({@code PERCENTAGE} or {@code FIXED_AMOUNT}). Every member may be null.
 */
public record DiscountFilter(Boolean active, String kind, String benefitType) {

    /** The fields a list may be sorted by. */
    public static final Set<String> SORT_FIELDS = Set.of("code", "name", "validFrom", "createdAt");
}

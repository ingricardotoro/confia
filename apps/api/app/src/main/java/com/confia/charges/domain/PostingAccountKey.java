package com.confia.charges.domain;

import java.util.Set;

/**
 * What a row of the posting map answers: a purpose and, for the revenue only, the category of concept (none is the default
 * account of the revenue). The categories are the ones of a payment concept, and the database refuses any other.
 */
public record PostingAccountKey(PostingPurpose purpose, String conceptCategory) {

    /** The categories of a payment concept, as the table of the map accepts them. */
    public static final Set<String> CATEGORIES = Set.of("TUITION", "ENROLLMENT", "TRANSPORT", "MATERIAL", "FINE", "OTHER");

    public PostingAccountKey {
        if (purpose == null || (conceptCategory != null
                && (purpose != PostingPurpose.REVENUE || !CATEGORIES.contains(conceptCategory)))) {
            throw new InvalidChargesValueException();
        }
    }

    /** The key named by the raw purpose and category of a request; a blank category is no category. */
    public static PostingAccountKey of(String purpose, String conceptCategory) {
        return new PostingAccountKey(PostingPurpose.parse(purpose),
                conceptCategory == null || conceptCategory.isBlank() ? null : conceptCategory);
    }
}

package com.confia.catalog.domain;

/** The closed set of categories of a payment concept. */
public enum PaymentCategory {
    TUITION, ENROLLMENT, TRANSPORT, MATERIAL, FINE, OTHER;

    /** The category named by the text, or a domain error when the text names none (case matters, as in the API). */
    public static PaymentCategory parse(String value) {
        if (value != null) {
            for (PaymentCategory category : values()) {
                if (category.name().equals(value)) {
                    return category;
                }
            }
        }
        throw new InvalidCatalogValueException();
    }
}

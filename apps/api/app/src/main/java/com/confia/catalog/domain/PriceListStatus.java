package com.confia.catalog.domain;

/** The state of a price list: it takes prices while {@code ACTIVE} and none once {@code ARCHIVED}. */
public enum PriceListStatus {
    ACTIVE, ARCHIVED;

    /** The status named by the text, or a domain error when it names none (case matters, as in the API). */
    public static PriceListStatus parse(String value) {
        if (value != null) {
            for (PriceListStatus status : values()) {
                if (status.name().equals(value)) {
                    return status;
                }
            }
        }
        throw new InvalidCatalogValueException();
    }
}

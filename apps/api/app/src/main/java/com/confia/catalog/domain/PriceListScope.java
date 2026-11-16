package com.confia.catalog.domain;

/** What a price list applies to: the whole institution, one modality or one grade. */
public enum PriceListScope {
    INSTITUTION, MODALITY, GRADE;

    /** The scope named by the text, or a domain error when it names none (case matters, as in the API). */
    public static PriceListScope parse(String value) {
        if (value != null) {
            for (PriceListScope scope : values()) {
                if (scope.name().equals(value)) {
                    return scope;
                }
            }
        }
        throw new InvalidCatalogValueException();
    }
}

package com.confia.charges.domain;

/** What a charge plan applies to: one modality, one grade or one section. */
public enum PlanTargetScope {
    MODALITY, GRADE, SECTION;

    /** The scope named by the text, or a domain error when it names none (case matters, as in the API). */
    public static PlanTargetScope parse(String value) {
        if (value != null) {
            for (PlanTargetScope scope : values()) {
                if (scope.name().equals(value)) {
                    return scope;
                }
            }
        }
        throw new InvalidChargesValueException();
    }
}

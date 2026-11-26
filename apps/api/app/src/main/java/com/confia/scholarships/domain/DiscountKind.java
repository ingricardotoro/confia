package com.confia.scholarships.domain;

import java.util.Optional;

/**
 * The two kinds of discount. A {@code campaign} discount applies by itself to every charge of a covered concept issued inside its
 * validity and is assigned to nobody. An {@code agreement} discount applies only to the students that have an approved assignment in
 * force.
 */
public enum DiscountKind {
    CAMPAIGN("campaign"), AGREEMENT("agreement");

    private final String code;

    DiscountKind(String code) {
        this.code = code;
    }

    /** The stable text of the API and of the database. */
    public String code() {
        return code;
    }

    public static Optional<DiscountKind> ofCode(String code) {
        for (DiscountKind kind : values()) {
            if (kind.code.equals(code)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}

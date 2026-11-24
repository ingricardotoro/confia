package com.confia.scholarships.domain;

import java.util.Optional;

/**
 * The life of an award: it is born {@code pending}, moves to {@code approved} or {@code rejected}, and an approved one can move to
 * {@code revoked}. It never goes back.
 */
public enum AwardStatus {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected"), REVOKED("revoked");

    private final String code;

    AwardStatus(String code) {
        this.code = code;
    }

    /** The stable text of the API and of the database. */
    public String code() {
        return code;
    }

    public static Optional<AwardStatus> ofCode(String code) {
        for (AwardStatus status : values()) {
            if (status.code.equals(code)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}

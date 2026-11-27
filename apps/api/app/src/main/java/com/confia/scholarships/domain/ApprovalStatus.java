package com.confia.scholarships.domain;

import java.util.Optional;

/**
 * Whether the terms of a campaign discount were approved by a person other than the one who requested them: it is born {@code
 * pending}, a different person approves it or rejects it, and only an approved discount applies to charges. An agreement discount is
 * always approved (its assignments carry the second person).
 */
public enum ApprovalStatus {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected");

    private final String code;

    ApprovalStatus(String code) {
        this.code = code;
    }

    /** The stable text of the API and of the database. */
    public String code() {
        return code;
    }

    public static Optional<ApprovalStatus> ofCode(String code) {
        for (ApprovalStatus status : values()) {
            if (status.code.equals(code)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}

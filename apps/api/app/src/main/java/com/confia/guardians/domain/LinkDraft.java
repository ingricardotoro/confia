package com.confia.guardians.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One link as the staff asks for it inside the set that replaces the links of a student: the guardian, the
 * relationship, the percentage of the financial responsibility and the two flags. The percentage is exact decimal
 * arithmetic from here on (never a binary floating-point number): between 0.01 and 100 with at most two decimals,
 * and it is normalised to scale 2, so {@code 60}, {@code 60.0} and {@code 60.00} are the same link.
 */
public record LinkDraft(UUID guardianId, Relationship relationship, BigDecimal percent, boolean primaryPayer,
        boolean billingRecipient) {

    public static final BigDecimal MIN_PERCENT = new BigDecimal("0.01");
    public static final BigDecimal MAX_PERCENT = new BigDecimal("100.00");
    static final int SCALE = 2;

    public LinkDraft {
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(relationship, "relationship");
        percent = normalized(percent);
    }

    /** A draft from the raw members of the request; a malformed value is an {@code InvalidGuardianException}. */
    public static LinkDraft of(UUID guardianId, String relationship, BigDecimal percent, boolean primaryPayer,
            boolean billingRecipient) {
        if (guardianId == null) {
            throw new InvalidGuardianException();
        }
        return new LinkDraft(guardianId, Relationship.parse(relationship), percent, primaryPayer, billingRecipient);
    }

    private static BigDecimal normalized(BigDecimal percent) {
        if (percent == null || percent.stripTrailingZeros().scale() > SCALE) {
            throw new InvalidGuardianException();
        }
        BigDecimal exact = percent.setScale(SCALE);
        if (exact.compareTo(MIN_PERCENT) < 0 || exact.compareTo(MAX_PERCENT) > 0) {
            throw new InvalidGuardianException();
        }
        return exact;
    }
}

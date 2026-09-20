package com.confia.organization.domain;

import com.confia.kernel.DomainException;

/**
 * {@link Institution}'s construction invariant causes (design.md, decision 6; ADR-0019). Every
 * factory deliberately takes no raw input string, which structurally guarantees the message never
 * repeats the rejected entry (CLAUDE.md, rule 11): the caller already has it.
 *
 * <p>Package-private factories, like {@code kernel}'s {@code InvalidMoneyAmountException}: only
 * {@link Institution}, in this same package, constructs them.
 */
public final class InvalidInstitutionException extends DomainException {

    public static final String LEGAL_NAME_BLANK = "institution-legal-name-blank";
    public static final String LEGAL_NAME_TOO_LONG = "institution-legal-name-too-long";
    public static final String TRADE_NAME_BLANK = "institution-trade-name-blank";
    public static final String TRADE_NAME_TOO_LONG = "institution-trade-name-too-long";

    private InvalidInstitutionException(String code, String message) {
        super(code, message);
    }

    /** {@code legalName} is empty, or made only of whitespace, after stripping border spaces. */
    static InvalidInstitutionException legalNameBlank() {
        return new InvalidInstitutionException(LEGAL_NAME_BLANK,
                "legal name must not be blank");
    }

    /** {@code legalName} exceeds {@link Institution#MAX_NAME_LENGTH} code points. */
    static InvalidInstitutionException legalNameTooLong() {
        return new InvalidInstitutionException(LEGAL_NAME_TOO_LONG,
                "legal name exceeds " + Institution.MAX_NAME_LENGTH + " characters");
    }

    /**
     * {@code tradeName} was provided (non-null) but is empty, or made only of whitespace, after
     * stripping border spaces. A null {@code tradeName} means "no trade name" and is accepted.
     */
    static InvalidInstitutionException tradeNameBlank() {
        return new InvalidInstitutionException(TRADE_NAME_BLANK,
                "trade name must not be blank when provided");
    }

    /** {@code tradeName} exceeds {@link Institution#MAX_NAME_LENGTH} code points. */
    static InvalidInstitutionException tradeNameTooLong() {
        return new InvalidInstitutionException(TRADE_NAME_TOO_LONG,
                "trade name exceeds " + Institution.MAX_NAME_LENGTH + " characters");
    }
}

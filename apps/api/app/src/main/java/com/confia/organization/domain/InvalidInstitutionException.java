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
    public static final String RTN_INVALID = "institution-rtn-invalid";
    public static final String ADDRESS_BLANK = "institution-address-blank";
    public static final String ADDRESS_TOO_LONG = "institution-address-too-long";
    public static final String LOCALE_INVALID = "institution-locale-invalid";
    public static final String TIMEZONE_INVALID = "institution-timezone-invalid";

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

    /**
     * {@code rtn} is not a sequence of 1 to {@link Institution#MAX_RTN_DIGITS} ASCII digits: empty,
     * containing a separator or a non-digit character, or exceeding the technical guard length.
     * This does not validate the exact SAR tax identification number format, which is not
     * confirmed against a primary source (design.md, decision 7).
     */
    static InvalidInstitutionException rtnInvalid() {
        return new InvalidInstitutionException(RTN_INVALID,
                "rtn must be between 1 and " + Institution.MAX_RTN_DIGITS
                        + " ASCII digits, with no separators");
    }

    /** {@code address} is empty, or made only of whitespace, after stripping border spaces. */
    static InvalidInstitutionException addressBlank() {
        return new InvalidInstitutionException(ADDRESS_BLANK, "address must not be blank");
    }

    /** {@code address} exceeds {@link Institution#MAX_ADDRESS_LENGTH} code points. */
    static InvalidInstitutionException addressTooLong() {
        return new InvalidInstitutionException(ADDRESS_TOO_LONG,
                "address exceeds " + Institution.MAX_ADDRESS_LENGTH + " characters");
    }

    /**
     * {@code locale} carries no language (for example {@link java.util.Locale#ROOT}, which is what
     * {@link java.util.Locale#forLanguageTag} returns for an invalid language tag).
     */
    static InvalidInstitutionException localeInvalid() {
        return new InvalidInstitutionException(LOCALE_INVALID, "locale must carry a language");
    }

    /**
     * {@code timezone} is a fixed offset ({@link java.time.ZoneOffset}) instead of a region
     * identifier; a fixed offset does not track the region's historical and future daylight-saving
     * rules (ADR-0011, point 5).
     */
    static InvalidInstitutionException timezoneInvalid() {
        return new InvalidInstitutionException(TIMEZONE_INVALID,
                "timezone must be a region identifier, not a fixed offset");
    }
}

package com.confia.guardians.domain;

/**
 * What a consent is for. Only the purposes that need a consent exist here: the transactional purpose (billing,
 * arrears, invoices, security) rests on the contract or a legitimate interest, so it is not recorded nor withdrawn as
 * a consent and is refused as {@code not-consentable}.
 */
public enum ConsentPurpose {
    INSTITUTIONAL, MARKETING;

    private static final String TRANSACTIONAL = "TRANSACTIONAL";

    public static ConsentPurpose parse(String value) {
        if (value == null) {
            throw new InvalidConsentException("purpose", InvalidConsentException.REQUIRED);
        }
        for (ConsentPurpose purpose : values()) {
            if (purpose.name().equals(value)) {
                return purpose;
            }
        }
        if (TRANSACTIONAL.equalsIgnoreCase(value.strip())) {
            throw new InvalidConsentException("purpose", InvalidConsentException.NOT_CONSENTABLE);
        }
        throw new InvalidConsentException("purpose", InvalidConsentException.INVALID);
    }
}

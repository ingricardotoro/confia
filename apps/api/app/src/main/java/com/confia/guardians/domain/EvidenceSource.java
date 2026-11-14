package com.confia.guardians.domain;

/**
 * Where the proof of a consent comes from. {@link #PORTAL_FORM} belongs to the portal, which records it itself: the
 * administrative API refuses it ({@link #parseForStaff}), while the database and the events it reads accept it.
 */
public enum EvidenceSource {
    SIGNED_PAPER_FORM, ADMIN_REGISTERED, PORTAL_FORM;

    /** The evidence a member of the staff may declare: a missing value is {@code required}, the portal's is {@code not-allowed}. */
    public static EvidenceSource parseForStaff(String value) {
        if (value == null) {
            throw new InvalidConsentException("evidenceSource", InvalidConsentException.REQUIRED);
        }
        for (EvidenceSource source : values()) {
            if (source.name().equals(value)) {
                if (source == PORTAL_FORM) {
                    throw new InvalidConsentException("evidenceSource", InvalidConsentException.NOT_ALLOWED);
                }
                return source;
            }
        }
        throw new InvalidConsentException("evidenceSource", InvalidConsentException.INVALID);
    }
}

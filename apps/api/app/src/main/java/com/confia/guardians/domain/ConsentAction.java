package com.confia.guardians.domain;

/** What an event does to the consent of a combination. */
public enum ConsentAction {
    GRANT, WITHDRAW;

    public static ConsentAction parse(String value) {
        if (value == null) {
            throw new InvalidConsentException("action", InvalidConsentException.REQUIRED);
        }
        for (ConsentAction action : values()) {
            if (action.name().equals(value)) {
                return action;
            }
        }
        throw new InvalidConsentException("action", InvalidConsentException.INVALID);
    }

    /** The state the combination is in after this action. */
    public ConsentState resultingState() {
        return this == GRANT ? ConsentState.GRANTED : ConsentState.WITHDRAWN;
    }
}

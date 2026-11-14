package com.confia.guardians.domain;

/** A channel a consent is given for. Each channel is independent: there is no grouped consent. */
public enum ConsentChannel {
    EMAIL, SMS, WHATSAPP;

    /** The channel with exactly this name; a missing value is {@code required}, anything else {@code invalid}. */
    public static ConsentChannel parse(String value) {
        if (value == null) {
            throw new InvalidConsentException("channel", InvalidConsentException.REQUIRED);
        }
        for (ConsentChannel channel : values()) {
            if (channel.name().equals(value)) {
                return channel;
            }
        }
        throw new InvalidConsentException("channel", InvalidConsentException.INVALID);
    }

    /** Whether the guardian has the contact data the channel needs: an e-mail, or a phone for SMS and WhatsApp. */
    public boolean hasContactDataIn(Guardian guardian) {
        return switch (this) {
            case EMAIL -> guardian.email() != null;
            case SMS, WHATSAPP -> guardian.phone() != null;
        };
    }
}

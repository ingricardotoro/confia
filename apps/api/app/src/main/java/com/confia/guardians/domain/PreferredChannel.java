package com.confia.guardians.domain;

/** How a guardian prefers to be contacted; {@code NONE} until the staff records a preference. */
public enum PreferredChannel {
    EMAIL, SMS, WHATSAPP, NONE;

    /** The channel with exactly this name; a missing value means {@link #NONE}, anything else is refused. */
    public static PreferredChannel parseOrNone(String value) {
        if (value == null) {
            return NONE;
        }
        for (PreferredChannel channel : values()) {
            if (channel.name().equals(value)) {
                return channel;
            }
        }
        throw new InvalidGuardianException();
    }
}

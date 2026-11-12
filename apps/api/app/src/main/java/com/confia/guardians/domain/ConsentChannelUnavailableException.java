package com.confia.guardians.domain;

import com.confia.kernel.DomainException;

/**
 * A consent cannot be granted for a channel the guardian has no contact data for (an e-mail for {@code EMAIL}, a phone
 * for {@code SMS} and {@code WHATSAPP}). Published to clients as the Problem Details code
 * {@code consent-channel-unavailable}.
 */
public final class ConsentChannelUnavailableException extends DomainException {

    public static final String CODE = "consent-channel-unavailable";

    public ConsentChannelUnavailableException() {
        super(CODE, "the guardian has no contact data for the channel of the consent");
    }
}

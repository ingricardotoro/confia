package com.confia.guardians.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The current standing of one combination of channel and purpose, derived from its latest event. A combination
 * without events is {@link ConsentState#NEVER_GRANTED} and has no instant, version or evidence; a withdrawn one has
 * the instant and the evidence of the withdrawal and no version.
 */
public record ConsentStanding(ConsentChannel channel, ConsentPurpose purpose, ConsentState state, Instant since,
        String policyVersion, EvidenceSource evidenceSource) {

    public ConsentStanding {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(state, "state");
    }

    static ConsentStanding never(ConsentChannel channel, ConsentPurpose purpose) {
        return new ConsentStanding(channel, purpose, ConsentState.NEVER_GRANTED, null, null, null);
    }

    static ConsentStanding of(ConsentEvent latest) {
        return new ConsentStanding(latest.channel(), latest.purpose(), latest.resultingState(), latest.occurredAt(),
                latest.policyVersion(), latest.evidenceSource());
    }
}

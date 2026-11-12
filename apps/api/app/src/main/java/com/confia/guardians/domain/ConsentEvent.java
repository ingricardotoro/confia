package com.confia.guardians.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One recorded fact about the consent of a guardian: immutable, never edited or deleted. It carries no contact data.
 * The version of the policy is present exactly when the action is a grant. {@code recordedBy} is the staff account
 * that recorded it.
 */
public record ConsentEvent(UUID id, UUID guardianId, ConsentChannel channel, ConsentPurpose purpose,
        ConsentAction action, EvidenceSource evidenceSource, String policyVersion, Instant occurredAt,
        UUID recordedBy) {

    public ConsentEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(guardianId, "guardianId");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordedBy, "recordedBy");
        if ((action == ConsentAction.GRANT) != (policyVersion != null)) {
            throw new IllegalArgumentException("the policy version is present exactly when the consent is granted");
        }
    }

    /** The state of the combination after this event. */
    public ConsentState resultingState() {
        return action.resultingState();
    }
}

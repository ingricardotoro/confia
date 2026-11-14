package com.confia.guardians.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The consents of one guardian, derived from the events and never stored: the standing of each of the six
 * combinations (three channels times two purposes) is the one its latest event leaves, and a combination with no
 * events is {@link ConsentState#NEVER_GRANTED}. The events are given oldest first, so the last one of a combination
 * wins.
 *
 * <p>{@link #decide} is the state machine. A grant needs an active guardian and the contact data of the channel; a
 * withdrawal is always allowed, even for an inactive guardian or one without the data, and takes effect at once.
 * Repeating the action that already is the current state changes nothing and yields no event, except a grant of an
 * already granted combination under a different policy version or evidence, which is a real change and is recorded
 * (only an identical repeat is a no-op); a withdrawal of a
 * combination that was never granted is a real fact (the guardian declined in advance) and is recorded. Each
 * combination is independent of the others.
 */
public final class GuardianConsents {

    private final Map<ConsentChannel, Map<ConsentPurpose, ConsentEvent>> latest = new EnumMap<>(ConsentChannel.class);

    private GuardianConsents(List<ConsentEvent> oldestFirst) {
        for (ConsentEvent event : oldestFirst) {
            latest.computeIfAbsent(event.channel(), channel -> new EnumMap<>(ConsentPurpose.class))
                    .put(event.purpose(), event);
        }
    }

    /** The consents the events leave, given oldest first. */
    public static GuardianConsents of(List<ConsentEvent> oldestFirst) {
        return new GuardianConsents(List.copyOf(Objects.requireNonNull(oldestFirst, "oldestFirst")));
    }

    /** The standing of one combination. */
    public ConsentStanding standing(ConsentChannel channel, ConsentPurpose purpose) {
        ConsentEvent event = latest.getOrDefault(channel, Map.of()).get(purpose);
        return event == null ? ConsentStanding.never(channel, purpose) : ConsentStanding.of(event);
    }

    /** The six combinations, channel by channel and, inside each, institutional before marketing. */
    public List<ConsentStanding> matrix() {
        List<ConsentStanding> standings = new ArrayList<>();
        for (ConsentChannel channel : ConsentChannel.values()) {
            for (ConsentPurpose purpose : ConsentPurpose.values()) {
                standings.add(standing(channel, purpose));
            }
        }
        return standings;
    }

    /** A grant of a combination already granted under another policy version or evidence is a new fact. */
    private static boolean changesTheGrant(ConsentStanding current, ConsentChange change) {
        return change.action() == ConsentAction.GRANT
                && (!Objects.equals(current.policyVersion(), change.policyVersion())
                        || current.evidenceSource() != change.evidenceSource());
    }

    /**
     * The event that records {@code change} for {@code guardian}, or empty when the combination already is in the
     * state the action leads to.
     *
     * @throws GuardianNotActiveException a grant for an inactive guardian
     * @throws ConsentChannelUnavailableException a grant for a channel the guardian has no contact data for
     */
    public Optional<ConsentEvent> decide(Guardian guardian, ConsentChange change, UUID eventId, UUID recordedBy,
            Instant now) {
        Objects.requireNonNull(guardian, "guardian");
        Objects.requireNonNull(change, "change");
        if (change.action() == ConsentAction.GRANT) {
            if (!guardian.active()) {
                throw new GuardianNotActiveException();
            }
            if (!change.channel().hasContactDataIn(guardian)) {
                throw new ConsentChannelUnavailableException();
            }
        }
        ConsentStanding current = standing(change.channel(), change.purpose());
        if (current.state() == change.action().resultingState() && !changesTheGrant(current, change)) {
            return Optional.empty();
        }
        return Optional.of(new ConsentEvent(eventId, guardian.id(), change.channel(), change.purpose(),
                change.action(), change.evidenceSource(), change.policyVersion(), now, recordedBy));
    }
}

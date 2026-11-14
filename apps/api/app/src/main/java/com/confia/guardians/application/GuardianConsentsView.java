package com.confia.guardians.application;

import com.confia.guardians.domain.ConsentEvent;
import com.confia.guardians.domain.ConsentStanding;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The consents of a guardian as the web layer may see them: the standing of the six combinations and the newest events
 * first. It carries no contact data, no name and no staff account.
 *
 * @param states the six combinations, channel by channel and, inside each, institutional before marketing
 * @param history the newest events first, at most {@link ManageGuardianConsents#HISTORY_LIMIT}
 */
public record GuardianConsentsView(UUID guardianId, List<Standing> states, List<Event> history) {

    /** One combination: {@code since}, {@code policyVersion} and {@code evidenceSource} are null when never granted. */
    public record Standing(String channel, String purpose, String state, Instant since, String policyVersion,
            String evidenceSource) {

        static Standing of(ConsentStanding standing) {
            return new Standing(standing.channel().name(), standing.purpose().name(), standing.state().name(),
                    standing.since(), standing.policyVersion(),
                    standing.evidenceSource() == null ? null : standing.evidenceSource().name());
        }
    }

    /** One recorded event; {@code policyVersion} is null for a withdrawal. */
    public record Event(UUID id, String channel, String purpose, String action, String evidenceSource,
            String policyVersion, Instant occurredAt) {

        static Event of(ConsentEvent event) {
            return new Event(event.id(), event.channel().name(), event.purpose().name(), event.action().name(),
                    event.evidenceSource().name(), event.policyVersion(), event.occurredAt());
        }
    }
}

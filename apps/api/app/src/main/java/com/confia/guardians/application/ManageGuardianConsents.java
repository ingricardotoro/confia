package com.confia.guardians.application;

import com.confia.guardians.domain.ConsentChange;
import com.confia.guardians.domain.ConsentEvent;
import com.confia.guardians.domain.ConsentStanding;
import com.confia.guardians.domain.Guardian;
import com.confia.guardians.domain.GuardianConsents;
import com.confia.guardians.domain.InvalidConsentException;
import com.confia.guardians.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Records and reads the consent of a guardian to receive communications, by channel and by purpose, with its evidence
 * (docs/cortes/guardians-consent.md; docs/08-datos-privacidad-y-retencion.md section 8). Every grant and every
 * withdrawal is an immutable event; the state of a combination is derived from its latest event and nothing is
 * stored as a mutable flag nor pre-marked.
 *
 * <p>Race safety. A change locks the row of the guardian ({@code FOR UPDATE}) before it reads the current state and
 * appends, so two simultaneous changes of the same guardian take turns and the second decides against what the first
 * committed: the events keep a coherent order and a repeat is recognised as a repeat. The check of the request (closed
 * sets, shape of the version, the transactional purpose, the portal's evidence) happens before any lock is taken.
 * A repeat that changes nothing (the same action, and for a grant the same policy version and evidence) writes no
 * event and no audit entry and answers the current state; a grant of a different version or evidence is a change.
 */
public final class ManageGuardianConsents {

    /** The most events the history of a guardian answers. */
    public static final int HISTORY_LIMIT = 100;

    private final TransactionRunner transactionRunner;
    private final GuardianRepository guardians;
    private final GuardianConsentRepository consents;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public ManageGuardianConsents(TransactionRunner transactionRunner, GuardianRepository guardians,
            GuardianConsentRepository consents, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.guardians = Objects.requireNonNull(guardians, "guardians");
        this.consents = Objects.requireNonNull(consents, "consents");
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The six combinations with their standing and the newest events first. */
    public GuardianConsentsView of(AuthenticatedActor actor, String requestId, UUID guardianId) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(auditRequestId.toString()), () -> {
            guardians.findById(institution, guardianId).orElseThrow(ResourceNotFoundException::new);
            GuardianConsents current = GuardianConsents.of(consents.latestOfGuardian(institution, guardianId));
            List<GuardianConsentsView.Event> history = consents.recentOfGuardian(institution, guardianId,
                    HISTORY_LIMIT).stream().map(GuardianConsentsView.Event::of).toList();
            return new GuardianConsentsView(guardianId,
                    current.matrix().stream().map(GuardianConsentsView.Standing::of).toList(), history);
        });
    }

    /**
     * Records the change, or answers the current standing when it is a repeat.
     *
     * @throws RejectedConsentException a member of the request breaks a rule (the field and the keyword)
     * @throws com.confia.guardians.domain.GuardianNotActiveException a grant for an inactive guardian
     * @throws com.confia.guardians.domain.ConsentChannelUnavailableException a grant for a channel without data
     */
    public ConsentChangeView record(AuthenticatedActor actor, String requestId, UUID guardianId, String channel,
            String purpose, String action, String evidenceSource, String policyVersion) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = GuardiansAudit.requestId(Objects.requireNonNull(requestId, "requestId"));
        InstitutionId institution = new InstitutionId(actor.institutionId());
        ConsentChange change = parse(channel, purpose, action, evidenceSource, policyVersion);
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Guardian guardian = guardians.lockById(institution, guardianId).orElseThrow(ResourceNotFoundException::new);
            GuardianConsents current = GuardianConsents.of(consents.latestOfGuardian(institution, guardianId));
            ConsentStanding before = current.standing(change.channel(), change.purpose());
            Optional<ConsentEvent> event = current.decide(guardian, change, UUID.randomUUID(), actor.accountId(),
                    now());
            if (event.isEmpty()) {
                return new ConsentChangeView(GuardianConsentsView.Standing.of(before), false);
            }
            consents.append(institution, event.get(), auditRequestId);
            auditLogWriter.append(GuardiansAudit.consentRecorded(actor, auditRequestId, event.get(),
                    guardian.guardianCode(), before.state()));
            return new ConsentChangeView(GuardianConsentsView.Standing.of(
                    GuardianConsents.of(List.of(event.get())).standing(change.channel(), change.purpose())), true);
        });
    }

    private static ConsentChange parse(String channel, String purpose, String action, String evidenceSource,
            String policyVersion) {
        try {
            return ConsentChange.parse(channel, purpose, action, evidenceSource, policyVersion);
        } catch (InvalidConsentException e) {
            throw new RejectedConsentException(e.field(), e.reason());
        }
    }

    /** The instant of the write, to the microsecond the column keeps, so an answer equals what a read returns. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

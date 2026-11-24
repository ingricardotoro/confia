package com.confia.scholarships.application;

import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.AwardDecision;
import com.confia.scholarships.domain.DecisionKind;
import com.confia.scholarships.domain.DecisionReason;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.scholarships.domain.ScholarshipAward;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The decisions on an award (docs/cortes/scholarships-awards.md): a person authorized who is not the one who requested it approves
 * or rejects it (segregation of duties), and a person authorized revokes an approved one. Each is ONE database transaction: the
 * decision is inserted with the reason (rejection and revocation) and the award moves to its new status. An approval, a rejection
 * and a revocation change no charge that was already issued: the engine applies an award only to the charges issued afterwards. The
 * reason may name a person: it is stored only in the decision and never copied to the audit trail (only its length is).
 *
 * <p>Order: (1) the key of the request is looked up; (2) the award is read, so what is not in the institution of the actor is
 * absent; (3) the award row is locked {@code FOR UPDATE} and read again, so two decisions on the same award take turns and exactly
 * one wins; (4) the key is looked up again under the lock; (5) the rules of the domain; (6) the decision and the new status are
 * written, and the database refuses an approval that would overlap another approved award of the scholarship for the student; (7)
 * the audit entry is written.
 *
 * <p>The {@code Idempotency-Key} is stored permanently on the decision with the hash of what it asked, which includes the actor.
 */
public final class DecideAward {

    private final TransactionRunner transactionRunner;
    private final AwardRepository awards;
    private final AwardDecisionRepository decisions;
    private final ScholarshipRepository scholarships;
    private final AwardViews views;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DecideAward(TransactionRunner transactionRunner, ScholarshipRepository scholarships, AwardRepository awards,
            AwardDecisionRepository decisions, StudentDirectory students, AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.scholarships = Objects.requireNonNull(scholarships, "scholarships");
        this.awards = Objects.requireNonNull(awards, "awards");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.views = new AwardViews(scholarships, decisions, students);
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the domain does to the award: makes the decision. */
    private interface Deciding {

        AwardDecision on(ScholarshipAward award, UUID decisionId, Instant now, String key, String hash);
    }

    /**
     * Approves the award.
     *
     * @throws com.confia.scholarships.domain.IdempotencyKeyRequiredException when the key is missing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws ResourceNotFoundException when the award is not in the institution of the actor
     * @throws com.confia.scholarships.domain.InvalidAwardStateException when it is not pending
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the actor requested it
     * @throws com.confia.scholarships.domain.InactiveReferenceException when its scholarship is inactive
     * @throws com.confia.scholarships.domain.AwardOverlapException when the student has another approved award of the scholarship
     *         in those days
     */
    public AwardView approve(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID awardId) {
        Objects.requireNonNull(awardId, "awardId");
        return decide(actor, requestId, idempotencyKey, awardId, null, "approve",
                (award, id, now, key, hash) -> award.approve(id, actor.accountId(), now, key, hash));
    }

    /**
     * Rejects the award with the reason, from 20 to 500 characters.
     *
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the reason is out of its bounds
     * @throws com.confia.scholarships.domain.InvalidAwardStateException when it is not pending
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the actor requested it
     */
    public AwardView reject(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID awardId, String reason) {
        Objects.requireNonNull(awardId, "awardId");
        DecisionReason because = DecisionReason.of(reason);
        return decide(actor, requestId, idempotencyKey, awardId, because, "reject",
                (award, id, now, key, hash) -> award.reject(id, actor.accountId(), because, now, key, hash));
    }

    /**
     * Revokes the approved award with the reason, from 20 to 500 characters. It affects only the charges issued afterwards.
     *
     * @throws com.confia.scholarships.domain.InvalidScholarshipsValueException when the reason is out of its bounds
     * @throws com.confia.scholarships.domain.InvalidAwardStateException when it is not approved
     */
    public AwardView revoke(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID awardId, String reason) {
        Objects.requireNonNull(awardId, "awardId");
        DecisionReason because = DecisionReason.of(reason);
        return decide(actor, requestId, idempotencyKey, awardId, because, "revoke",
                (award, id, now, key, hash) -> award.revoke(id, actor.accountId(), because, now, key, hash));
    }

    private AwardView decide(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID awardId,
            DecisionReason reason, String operation, Deciding deciding) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = ScholarshipRequests.key(idempotencyKey);
        String hash = reason == null ? ScholarshipRequests.hash(operation, actor.accountId(), awardId)
                : ScholarshipRequests.hash(operation, actor.accountId(), awardId, reason.text());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, awardId, reason, hash, deciding);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, awardId, reason, hash, deciding);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private AwardView attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key, UUID awardId,
            DecisionReason reason, String hash, Deciding deciding) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<AwardDecision> replay = decisions.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(institution, replay.get(), hash);
            }
            awards.findById(institution, awardId).orElseThrow(ResourceNotFoundException::new);
            // Locked and read again: a decision that committed while this waited is seen now.
            ScholarshipAward award = awards.findForUpdate(institution, awardId).orElseThrow(ResourceNotFoundException::new);
            Optional<AwardDecision> underLock = decisions.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(institution, underLock.get(), hash);
            }
            AwardDecision decision = deciding.on(award, UUID.randomUUID(), clock.instant(), key, hash);
            if (decision.kind() == DecisionKind.APPROVED) {
                scholarships.findById(institution, award.scholarshipId()).orElseThrow(ResourceNotFoundException::new)
                        .requireActive();
            }
            decisions.insert(institution, decision);
            awards.advanceStatus(institution, award.id(), decision.kind().leadsTo());
            auditLogWriter.append(ScholarshipsAudit.awardDecided(actor, auditRequestId, award, decision, reason));
            return views.detail(institution, award.decided(decision));
        });
    }

    private AwardView answerReplay(InstitutionId institution, AwardDecision existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        ScholarshipAward award = awards.findById(institution, existing.awardId()).orElseThrow(ResourceNotFoundException::new);
        return views.detail(institution, award);
    }
}

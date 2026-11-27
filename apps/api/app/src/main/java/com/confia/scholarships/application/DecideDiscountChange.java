package com.confia.scholarships.application;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PaymentConceptRef;
import com.confia.kernel.InstitutionId;
import com.confia.scholarships.domain.DecisionKind;
import com.confia.scholarships.domain.DecisionReason;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountDecision;
import com.confia.scholarships.domain.InactiveReferenceException;
import com.confia.scholarships.domain.InvalidScholarshipsValueException;
import com.confia.scholarships.domain.ResourceNotFoundException;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.IdempotencyPayloadMismatchException;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The decisions on a campaign discount (docs/cortes/scholarships-discounts.md, owner decision of 2026-11-26, approval by another
 * person): a person authorized who is not the one who asked approves or rejects what waits, which is the creation of the discount or
 * a proposal to widen or reactivate it. An approval makes the creation or the proposal the terms in force, from then on the engine
 * applies them to the charges issued afterwards; a rejection drops the proposal or rejects the creation for good. Each is ONE
 * database transaction: the decision is inserted with the version of the discount the decider saw (and the reason of a rejection) and
 * the discount takes its new state. The reason may name a person: it is stored only in the decision and never copied to the audit
 * trail (only its SHA-256 and its length are).
 *
 * <p>Order, as for the assignments: (1) the key of the request is looked up; (2) the discount is locked {@code FOR UPDATE} and read,
 * so what is not in the institution of the actor is absent and two decisions take turns; (3) the key is looked up again under the
 * lock; (4) the rules of the domain, including the version the decider saw; (5) the decision and the new state are written, and the
 * database refuses a decision by whoever asked; (6) the audit entry is written.
 *
 * <p>The {@code Idempotency-Key} is stored permanently on the decision with the hash of what it asked, which includes the actor and
 * the version.
 */
public final class DecideDiscountChange {

    private final TransactionRunner transactionRunner;
    private final DiscountRepository discounts;
    private final DiscountDecisionRepository decisions;
    private final PaymentConceptDirectory concepts;
    private final DiscountViews views;
    private final AuditLogWriter auditLogWriter;
    private final Clock clock;

    public DecideDiscountChange(TransactionRunner transactionRunner, DiscountRepository discounts,
            DiscountDecisionRepository decisions, DiscountAssignmentRepository assignments, PaymentConceptDirectory concepts,
            AuditLogWriter auditLogWriter, Clock clock) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.discounts = Objects.requireNonNull(discounts, "discounts");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.concepts = Objects.requireNonNull(concepts, "concepts");
        this.views = new DiscountViews(concepts, Objects.requireNonNull(assignments, "assignments"));
        this.auditLogWriter = Objects.requireNonNull(auditLogWriter, "auditLogWriter");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** What the domain does to the discount: makes the decision. */
    private interface Deciding {

        DiscountDecision on(Discount discount, UUID decisionId, Instant now, String key, String hash);
    }

    /**
     * Approves what waits on the discount at the version the decider saw.
     *
     * @throws com.confia.scholarships.domain.IdempotencyKeyRequiredException when the key is missing
     * @throws IdempotencyPayloadMismatchException when the key was used for another request
     * @throws ResourceNotFoundException when the discount is not in the institution of the actor
     * @throws com.confia.scholarships.domain.InvalidDiscountChangeStateException when nothing waits for a decision
     * @throws com.confia.scholarships.domain.SelfApprovalForbiddenException when the actor asked for what waits
     * @throws com.confia.scholarships.domain.StaleVersionException when the version is not the current one
     * @throws InactiveReferenceException when a concept the proposal adds is inactive
     */
    public DiscountView approve(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID discountId, long version) {
        Objects.requireNonNull(discountId, "discountId");
        return decide(actor, requestId, idempotencyKey, discountId, version, null, "approve-discount-change",
                (discount, id, now, key, hash) -> discount.approveChange(id, actor.accountId(), version, now, key, hash));
    }

    /**
     * Rejects what waits on the discount with the reason, from 20 to 500 characters.
     *
     * @throws InvalidScholarshipsValueException when the reason is out of its bounds
     */
    public DiscountView reject(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID discountId, long version,
            String reason) {
        Objects.requireNonNull(discountId, "discountId");
        return ScholarshipRequests.naming(() -> {
            DecisionReason because = DecisionReason.of(reason);
            return decide(actor, requestId, idempotencyKey, discountId, version, because, "reject-discount-change",
                    (discount, id, now, key, hash) -> discount.rejectChange(id, actor.accountId(), version, because, now, key,
                            hash));
        });
    }

    private DiscountView decide(AuthenticatedActor actor, String requestId, String idempotencyKey, UUID discountId, long version,
            DecisionReason reason, String operation, Deciding deciding) {
        Objects.requireNonNull(actor, "actor");
        UUID auditRequestId = ScholarshipRequests.requestId(Objects.requireNonNull(requestId, "requestId"));
        String key = ScholarshipRequests.key(idempotencyKey);
        String hash = reason == null ? ScholarshipRequests.hash(operation, actor.accountId(), discountId, version)
                : ScholarshipRequests.hash(operation, actor.accountId(), discountId, version, reason.text());
        InstitutionId institution = new InstitutionId(actor.institutionId());
        try {
            return attempt(actor, auditRequestId, institution, key, discountId, reason, hash, deciding);
        } catch (RequestKeyTakenException lostTheRace) {
            try {
                return attempt(actor, auditRequestId, institution, key, discountId, reason, hash, deciding);
            } catch (RequestKeyTakenException again) {
                throw new IdempotencyPayloadMismatchException();
            }
        }
    }

    private DiscountView attempt(AuthenticatedActor actor, UUID auditRequestId, InstitutionId institution, String key,
            UUID discountId, DecisionReason reason, String hash, Deciding deciding) {
        return transactionRunner.execute(actor.securityContext(auditRequestId.toString()), () -> {
            Optional<DiscountDecision> replay = decisions.findByRequestKey(institution, key);
            if (replay.isPresent()) {
                return answerReplay(institution, replay.get(), hash);
            }
            // Locked and read: what is not in the institution is absent, and a decision that committed while this waited is seen now.
            Discount before = discounts.lockById(institution, discountId).orElseThrow(ResourceNotFoundException::new);
            Optional<DiscountDecision> underLock = decisions.findByRequestKey(institution, key);
            if (underLock.isPresent()) {
                return answerReplay(institution, underLock.get(), hash);
            }
            Instant now = clock.instant();
            DiscountDecision decision = deciding.on(before, UUID.randomUUID(), now, key, hash);
            Discount after = before.decided(decision, now);
            if (decision.kind() == DecisionKind.APPROVED && before.proposal() != null) {
                requireCoverable(institution, before.proposal().concepts(), before.concepts());
            }
            decisions.insert(institution, decision);
            discounts.replace(institution, after, before.version());
            auditLogWriter.append(DiscountsAudit.discountDecided(actor, auditRequestId, before, after, decision, reason));
            return views.one(institution, after);
        });
    }

    private DiscountView answerReplay(InstitutionId institution, DiscountDecision existing, String hash) {
        if (!existing.answers(hash)) {
            throw new IdempotencyPayloadMismatchException();
        }
        Discount discount = discounts.findById(institution, existing.discountId()).orElseThrow(ResourceNotFoundException::new);
        return views.one(institution, discount);
    }

    /** Every concept that an approved proposal newly covers still exists in the institution and is active. */
    private void requireCoverable(InstitutionId institution, Set<UUID> wanted, Set<UUID> alreadyCovered) {
        for (UUID conceptId : wanted) {
            if (alreadyCovered.contains(conceptId)) {
                continue;
            }
            PaymentConceptRef ref = concepts.lockShare(institution, conceptId)
                    .orElseThrow(() -> new InvalidScholarshipsValueException("conceptIds", "not-found"));
            if (!ref.active()) {
                throw new InactiveReferenceException();
            }
        }
    }
}

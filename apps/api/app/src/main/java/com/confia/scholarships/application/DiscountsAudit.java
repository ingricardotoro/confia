package com.confia.scholarships.application;

import com.confia.scholarships.domain.AssignmentDecision;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.DecisionKind;
import com.confia.scholarships.domain.DecisionReason;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountAssignment;
import com.confia.scholarships.domain.DiscountDecision;
import com.confia.scholarships.domain.DiscountProposal;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The entries of the discounts: {@code scholarships.discount.created|updated|deactivated|reactivated} (with the state before and
 * after when something changed) and {@code scholarships.discount_assignment.requested|approved|rejected|revoked}. They are built with
 * {@link AuditJson} and carry identifiers, codes, the benefit, the validity, the status and dates. They never carry the text of the
 * reason of a rejection or of a revocation: the free text may name a person. The {@code reason} of the entry holds the SHA-256 of that
 * text in lowercase hexadecimal (owner decision of 2026-11-24), so the entry can be matched with the decision record, where the text
 * lives, without copying it; its length is kept too. The name of the student is never recorded, only its identifier.
 */
final class DiscountsAudit {

    static final String DISCOUNT = "scholarships.discount";
    static final String ASSIGNMENT = "scholarships.discount_assignment";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private DiscountsAudit() {
    }

    static AuditEntry discountCreated(AuthenticatedActor actor, UUID requestId, Discount created) {
        return entry(actor, requestId, DISCOUNT + ".created", DISCOUNT, created.id(), null, json(created), null, null);
    }

    static AuditEntry discountUpdated(AuthenticatedActor actor, UUID requestId, Discount before, Discount after) {
        return entry(actor, requestId, DISCOUNT + ".updated", DISCOUNT, after.id(), json(before), json(after), null, null);
    }

    /** The deactivation or the reactivation, according to the state the discount ends in. */
    static AuditEntry discountActivation(AuthenticatedActor actor, UUID requestId, Discount before, Discount after) {
        String action = after.active() ? ".reactivated" : ".deactivated";
        return entry(actor, requestId, DISCOUNT + action, DISCOUNT, after.id(), json(before), json(after), null, null);
    }

    /** A proposal to widen or reactivate a campaign discount, which another person decides: the terms in force and the ones proposed. */
    static AuditEntry discountChangeRequested(AuthenticatedActor actor, UUID requestId, Discount before, Discount after) {
        return entry(actor, requestId, DISCOUNT + ".change_requested", DISCOUNT, after.id(), json(before), json(after), null, null);
    }

    /**
     * The approval or the rejection of the creation of a campaign discount or of a proposal on it: the second person is recorded as the
     * approver of an approval. A rejection holds the SHA-256 of its reason and its length, never its text.
     */
    static AuditEntry discountDecided(AuthenticatedActor actor, UUID requestId, Discount before, Discount after,
            DiscountDecision decision, DecisionReason reason) {
        boolean approved = decision.kind() == DecisionKind.APPROVED;
        return entry(actor, requestId, DISCOUNT + "." + decision.kind().code(), DISCOUNT, after.id(), json(before), json(after),
                reason == null ? null : ScholarshipsAudit.sha256Hex(reason.text()), approved ? decision.decidedBy() : null);
    }

    static AuditEntry assignmentRequested(AuthenticatedActor actor, UUID requestId, DiscountAssignment assignment) {
        return entry(actor, requestId, ASSIGNMENT + ".requested", ASSIGNMENT, assignment.id(), null, json(assignment), null, null);
    }

    /** The approval, the rejection or the revocation: the second person who approves or rejects is recorded as the approver. */
    static AuditEntry assignmentDecided(AuthenticatedActor actor, UUID requestId, DiscountAssignment assignment,
            AssignmentDecision decision, DecisionReason reason) {
        AuditJson.Builder after = AuditJson.object().text("status", decision.kind().leadsTo().code())
                .text("decisionId", decision.id().toString()).text("discountId", assignment.discountId().toString())
                .text("studentId", assignment.studentId().toString()).text("validFrom", assignment.validFrom().toString())
                .text("validTo", assignment.validTo().toString());
        terms(after, assignment);
        if (reason != null) {
            after.number("reasonLength", reason.length());
        }
        boolean revoked = decision.kind().leadsTo() == AwardStatus.REVOKED;
        AwardStatus from = revoked ? AwardStatus.APPROVED : AwardStatus.PENDING;
        UUID approver = revoked ? null : decision.decidedBy();
        String before = AuditJson.object().text("status", from.code()).text("requestedBy", assignment.requestedBy().toString())
                .toJson();
        return entry(actor, requestId, ASSIGNMENT + "." + decision.kind().code(), ASSIGNMENT, assignment.id(), before,
                after.toJson(), reason == null ? null : ScholarshipsAudit.sha256Hex(reason.text()), approver);
    }

    private static String json(Discount discount) {
        AuditJson.Builder json = AuditJson.object().text("code", discount.code()).text("name", discount.name())
                .text("kind", discount.kind().code());
        benefit(json, "", discount.benefit());
        json.text("currency", Benefit.CURRENCY.name()).text("validFrom", discount.validFrom().toString())
                .text("validTo", discount.validTo().toString()).bool("stackable", discount.stackable())
                .text("conceptIds", conceptIds(discount.concepts())).bool("active", discount.active())
                .number("version", discount.version()).text("approvalStatus", discount.approval().code());
        DiscountProposal proposal = discount.proposal();
        if (proposal != null) {
            json.text("proposedName", proposal.name());
            benefit(json, "proposed", proposal.benefit());
            json.text("proposedValidFrom", proposal.validFrom().toString()).text("proposedValidTo", proposal.validTo().toString())
                    .bool("proposedStackable", proposal.stackable()).bool("proposedReactivate", proposal.reactivate())
                    .text("proposedConceptIds", conceptIds(proposal.concepts()))
                    .text("proposedBy", proposal.requestedBy().toString());
        }
        return json.toJson();
    }

    /** The type and the value of a benefit, with the prefix of the proposal ("proposed") or none. */
    private static void benefit(AuditJson.Builder json, String prefix, Benefit benefit) {
        json.text(key(prefix, "benefitType"), benefit.type().name());
        if (benefit.percent() != null) {
            json.text(key(prefix, "percent"), benefit.percent().toPlainString());
        } else {
            json.text(key(prefix, "fixedAmount"), benefit.fixedAmount().toPlainString());
        }
    }

    private static String key(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static String conceptIds(java.util.Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().collect(Collectors.joining(","));
    }

    private static String json(DiscountAssignment assignment) {
        AuditJson.Builder json = AuditJson.object().text("discountId", assignment.discountId().toString())
                .text("studentId", assignment.studentId().toString()).text("validFrom", assignment.validFrom().toString())
                .text("validTo", assignment.validTo().toString()).text("status", assignment.status().code());
        terms(json, assignment);
        return json.toJson();
    }

    /** The terms the assignment was requested for: the version of the discount and its benefit. */
    private static void terms(AuditJson.Builder json, DiscountAssignment assignment) {
        if (assignment.discountVersion() != null) {
            json.number("discountVersion", assignment.discountVersion());
            benefit(json, "", assignment.benefit());
        }
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, String entityType, UUID entityId,
            String before, String after, String reason, UUID approverId) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF, actor.accountId().toString(), null, null,
                requestId, null, action, entityType, entityId.toString(), OUTCOME_SUCCESS, before, after, reason, approverId);
    }
}

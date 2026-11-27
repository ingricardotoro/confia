package com.confia.scholarships.application;

import com.confia.scholarships.domain.AssignmentDecision;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.DecisionReason;
import com.confia.scholarships.domain.Discount;
import com.confia.scholarships.domain.DiscountAssignment;
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
                .text("kind", discount.kind().code()).text("benefitType", discount.benefit().type().name());
        if (discount.benefit().percent() != null) {
            json.text("percent", discount.benefit().percent().toPlainString());
        } else {
            json.text("fixedAmount", discount.benefit().fixedAmount().toPlainString());
        }
        return json.text("currency", Benefit.CURRENCY.name()).text("validFrom", discount.validFrom().toString())
                .text("validTo", discount.validTo().toString()).bool("stackable", discount.stackable())
                .text("conceptIds", discount.concepts().stream().map(UUID::toString).sorted().collect(Collectors.joining(",")))
                .bool("active", discount.active()).number("version", discount.version()).toJson();
    }

    private static String json(DiscountAssignment assignment) {
        return AuditJson.object().text("discountId", assignment.discountId().toString())
                .text("studentId", assignment.studentId().toString()).text("validFrom", assignment.validFrom().toString())
                .text("validTo", assignment.validTo().toString()).text("status", assignment.status().code()).toJson();
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, String entityType, UUID entityId,
            String before, String after, String reason, UUID approverId) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF, actor.accountId().toString(), null, null,
                requestId, null, action, entityType, entityId.toString(), OUTCOME_SUCCESS, before, after, reason, approverId);
    }
}

package com.confia.scholarships.application;

import com.confia.scholarships.domain.AwardDecision;
import com.confia.scholarships.domain.AwardStatus;
import com.confia.scholarships.domain.Benefit;
import com.confia.scholarships.domain.DecisionReason;
import com.confia.scholarships.domain.Scholarship;
import com.confia.scholarships.domain.ScholarshipAward;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The entries of the scholarships: {@code scholarships.scholarship.created|updated|deactivated|reactivated} (with the state before
 * and after when something changed) and {@code scholarships.award.requested|approved|rejected|revoked}. They are built with {@link
 * AuditJson} and carry identifiers, codes, the benefit, the status and dates. They never carry the reason of a rejection or of a
 * revocation (only its length): the free text may name a person. The name of the student is never recorded, only its identifier.
 */
final class ScholarshipsAudit {

    static final String SCHOLARSHIP = "scholarships.scholarship";
    static final String AWARD = "scholarships.award";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private ScholarshipsAudit() {
    }

    static AuditEntry scholarshipCreated(AuthenticatedActor actor, UUID requestId, Scholarship created) {
        return entry(actor, requestId, SCHOLARSHIP + ".created", SCHOLARSHIP, created.id(), null, json(created), null);
    }

    static AuditEntry scholarshipUpdated(AuthenticatedActor actor, UUID requestId, Scholarship before, Scholarship after) {
        return entry(actor, requestId, SCHOLARSHIP + ".updated", SCHOLARSHIP, after.id(), json(before), json(after), null);
    }

    /** The deactivation or the reactivation, according to the state the scholarship ends in. */
    static AuditEntry scholarshipActivation(AuthenticatedActor actor, UUID requestId, Scholarship before, Scholarship after) {
        String action = after.active() ? ".reactivated" : ".deactivated";
        return entry(actor, requestId, SCHOLARSHIP + action, SCHOLARSHIP, after.id(), json(before), json(after), null);
    }

    static AuditEntry awardRequested(AuthenticatedActor actor, UUID requestId, ScholarshipAward award) {
        return entry(actor, requestId, AWARD + ".requested", AWARD, award.id(), null, json(award), null);
    }

    /** The approval or the rejection: the second person who decides is recorded as the approver. */
    static AuditEntry awardDecided(AuthenticatedActor actor, UUID requestId, ScholarshipAward award, AwardDecision decision,
            DecisionReason reason) {
        AuditJson.Builder after = AuditJson.object().text("status", decision.kind().leadsTo().code())
                .text("decisionId", decision.id().toString()).text("scholarshipId", award.scholarshipId().toString())
                .text("studentId", award.studentId().toString()).text("validFrom", award.validFrom().toString())
                .text("validTo", award.validTo().toString());
        if (reason != null) {
            after.number("reasonLength", reason.length());
        }
        AwardStatus from = decision.kind().leadsTo() == AwardStatus.REVOKED ? AwardStatus.APPROVED : AwardStatus.PENDING;
        UUID approver = decision.kind().leadsTo() == AwardStatus.REVOKED ? null : decision.decidedBy();
        return entry(actor, requestId, AWARD + "." + decision.kind().code(), AWARD, award.id(),
                AuditJson.object().text("status", from.code()).toJson(), after.toJson(), approver);
    }

    private static String json(Scholarship scholarship) {
        AuditJson.Builder json = AuditJson.object().text("code", scholarship.code()).text("name", scholarship.name())
                .text("benefitType", scholarship.benefit().type().name());
        if (scholarship.benefit().percent() != null) {
            json.text("percent", scholarship.benefit().percent().toPlainString());
        } else {
            json.text("fixedAmount", scholarship.benefit().fixedAmount().toPlainString());
        }
        if (scholarship.periodCap() != null) {
            json.text("periodCap", scholarship.periodCap().toPlainString());
        }
        return json.text("currency", Benefit.CURRENCY.name())
                .text("conceptIds", scholarship.concepts().stream().map(UUID::toString).sorted().collect(Collectors.joining(",")))
                .bool("active", scholarship.active()).number("version", scholarship.version()).toJson();
    }

    private static String json(ScholarshipAward award) {
        return AuditJson.object().text("scholarshipId", award.scholarshipId().toString())
                .text("studentId", award.studentId().toString()).text("academicYearId", award.academicYearId().toString())
                .text("validFrom", award.validFrom().toString()).text("validTo", award.validTo().toString())
                .text("status", award.status().code()).toJson();
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, String entityType, UUID entityId,
            String before, String after, UUID approverId) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF, actor.accountId().toString(), null, null,
                requestId, null, action, entityType, entityId.toString(), OUTCOME_SUCCESS, before, after, null, approverId);
    }
}

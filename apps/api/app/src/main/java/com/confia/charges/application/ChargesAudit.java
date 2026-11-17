package com.confia.charges.application;

import com.confia.charges.domain.ChargePlan;
import com.confia.charges.domain.ChargePlanItem;
import com.confia.charges.domain.PlanStatus;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.UUID;

/**
 * The entries of the charge plans: {@code charges.plan.created|updated|activated|archived} (an edit that moves the
 * status to active or to archived is recorded as that transition, with the state before and after) and {@code
 * charges.plan_item.added|removed}. They are built with {@link AuditJson} and never by concatenation, and carry only
 * names, identifiers, the frequency, the due day, the number of periods and dates: no personal datum exists in this module.
 */
final class ChargesAudit {

    static final String PLAN = "charges.plan";
    static final String PLAN_ITEM = "charges.plan_item";

    private static final String ACTOR_KIND_STAFF = "staff";
    private static final String OUTCOME_SUCCESS = "success";

    private ChargesAudit() {
    }

    /** The identifier of the request for the entry; a blank one gets a fresh identifier. */
    static UUID requestId(String requestId) {
        return requestId.isBlank() ? UUID.randomUUID() : UUID.fromString(requestId);
    }

    static AuditEntry planCreated(AuthenticatedActor actor, UUID requestId, ChargePlan plan) {
        return entry(actor, requestId, PLAN + ".created", PLAN, plan.id(), null, json(plan));
    }

    /** The entry of an edit: {@code activated} or {@code archived} when the status moved there, else {@code updated}. */
    static AuditEntry planUpdated(AuthenticatedActor actor, UUID requestId, ChargePlan before, ChargePlan after) {
        String verb = after.status() == before.status() ? "updated"
                : after.status() == PlanStatus.ACTIVE ? "activated" : "archived";
        return entry(actor, requestId, PLAN + "." + verb, PLAN, after.id(), json(before), json(after));
    }

    static AuditEntry itemAdded(AuthenticatedActor actor, UUID requestId, ChargePlanItem item) {
        return entry(actor, requestId, PLAN_ITEM + ".added", PLAN_ITEM, item.id(), null, json(item));
    }

    static AuditEntry itemRemoved(AuthenticatedActor actor, UUID requestId, ChargePlanItem item) {
        return entry(actor, requestId, PLAN_ITEM + ".removed", PLAN_ITEM, item.id(), json(item), null);
    }

    private static AuditEntry entry(AuthenticatedActor actor, UUID requestId, String action, String entityType,
            UUID entityId, String before, String after) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), ACTOR_KIND_STAFF,
                actor.accountId().toString(), null, null, requestId, null, action, entityType,
                entityId.toString(), OUTCOME_SUCCESS, before, after, null, null);
    }

    static String json(ChargePlan plan) {
        return AuditJson.object().text("name", plan.name()).text("academicYearId", plan.academicYearId().toString())
                .text("targetScope", plan.targetScope().name()).text("targetRefId", plan.targetRefId().toString())
                .text("frequency", plan.frequency().name()).number("dueDayOfMonth", plan.dueDayOfMonth())
                .text("status", plan.status().name()).number("version", plan.version()).toJson();
    }

    static String json(ChargePlanItem item) {
        return AuditJson.object().text("planId", item.planId().toString())
                .text("paymentConceptId", item.paymentConceptId().toString())
                .number("periodsCount", item.periodsCount()).text("firstPeriodOn", item.firstPeriodOn().toString())
                .toJson();
    }
}

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
 * charges.plan_item.added|removed} and {@code charges.posting_account.updated}. They are built with {@link AuditJson} and never by concatenation, and carry only
 * names, identifiers, the frequency, the due day, the number of periods and dates: no personal datum exists in this module.
 */
final class ChargesAudit {

    static final String PLAN = "charges.plan";
    static final String PLAN_ITEM = "charges.plan_item";
    static final String POSTING_ACCOUNT = "charges.posting_account";
    static final String STATEMENT = "charges.statement";
    static final String CHARGE = "charges.charge";
    static final String STUDENT_CHARGES = "charges.student_charges";
    static final String INTEGRITY_RUN = "charges.integrity_run";

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

    /** The setting of one entry of the posting map: {@code before} is null when the entry was first set. */
    static AuditEntry postingAccountSet(AuthenticatedActor actor, UUID requestId, PostingAccountRow before,
            PostingAccountRow after) {
        return entry(actor, requestId, POSTING_ACCOUNT + ".updated", POSTING_ACCOUNT, after.id(),
                before == null ? null : json(before), json(after));
    }

    /**
     * The reading of the statement of a student: access to the financial data of a minor is audited on every request. It
     * carries the student identifier as the entity, and the filters asked for (currency, dates, page); never an amount or a
     * name.
     */
    static AuditEntry statementViewed(AuthenticatedActor actor, UUID requestId, UUID studentId,
            com.confia.kernel.CurrencyCode currency, java.time.LocalDate from, java.time.LocalDate to, int page,
            int size) {
        AuditJson.Builder json = AuditJson.object();
        if (currency != null) {
            json.text("currency", currency.name());
        }
        return entry(actor, requestId, STATEMENT + ".viewed", STATEMENT, studentId, null,
                json.text("from", from.toString()).text("to", to.toString()).number("page", page)
                        .number("size", size).toJson());
    }

    /**
     * The void of a charge: who, which charge, for which student and with which ledger reversal. The reason is free text that
     * may name a person, so only its length is recorded here; the text lives in the void record.
     */
    static AuditEntry chargeVoided(AuthenticatedActor actor, UUID requestId, UUID studentId,
            com.confia.charges.domain.ChargeVoid charge) {
        return entry(actor, requestId, CHARGE + ".voided", CHARGE, charge.chargeId(), null,
                AuditJson.object().text("voidId", charge.id().toString()).text("studentId", studentId.toString())
                        .text("reversalTransactionId", charge.reversalTransactionId().toString())
                        .number("reasonLength", charge.reason().length()).toJson());
    }

    /** The reading of the charges of a student: the filters asked for, never an amount or a name. */
    static AuditEntry studentChargesViewed(AuthenticatedActor actor, UUID requestId, UUID studentId, ChargeFilter filter,
            PageRequest page) {
        AuditJson.Builder json = AuditJson.object();
        if (filter.status() != null) {
            json.text("status", filter.status());
        }
        if (filter.periodFrom() != null) {
            json.text("periodFrom", filter.periodFrom().toString());
        }
        if (filter.periodTo() != null) {
            json.text("periodTo", filter.periodTo().toString());
        }
        return entry(actor, requestId, STUDENT_CHARGES + ".viewed", STUDENT_CHARGES, studentId, null,
                json.number("page", page.page()).number("size", page.size()).toJson());
    }

    /**
     * The result of one nightly integrity check, written by the {@code system} actor of the worker. The outcome is {@code
     * error} when the check confirmed discrepancies, which is how a reader of the trail sees a failed night. Counts only.
     */
    static AuditEntry integrityRunCompleted(UUID institutionId, UUID runId, com.confia.charges.domain.IntegrityRun run,
            java.util.Map<com.confia.charges.domain.IntegrityKind, Integer> byKind) {
        AuditJson.Builder json = AuditJson.object().text("status", run.status())
                .number("chargesChecked", run.chargesChecked()).number("postingsChecked", run.postingsChecked())
                .number("discrepancyCount", run.discrepancyCount());
        byKind.forEach((kind, count) -> json.number(memberName(kind), count));
        return new AuditEntry(institutionId, null, "system", "charges-integrity", null, null, runId, null,
                INTEGRITY_RUN + ".completed", INTEGRITY_RUN, runId.toString(),
                run.discrepancyCount() == 0 ? OUTCOME_SUCCESS : "error", null, json.toJson(), null, null);
    }

    /** {@code ORPHAN_POSTING} as the audit member {@code orphanPosting}: members are camel case. */
    private static String memberName(com.confia.charges.domain.IntegrityKind kind) {
        StringBuilder name = new StringBuilder();
        for (String word : kind.name().toLowerCase(java.util.Locale.ROOT).split("_")) {
            name.append(name.length() == 0 ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return name.toString();
    }

    static String json(PostingAccountRow row) {
        AuditJson.Builder json = AuditJson.object().text("purpose", row.key().purpose().name());
        if (row.key().conceptCategory() != null) {
            json.text("conceptCategory", row.key().conceptCategory());
        }
        return json.text("ledgerAccountId", row.ledgerAccountId().toString()).number("version", row.version()).toJson();
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

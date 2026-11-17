package com.confia.charges.application;

import com.confia.charges.domain.AccrualRun;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditJson;
import com.confia.shared.security.AuthenticatedActor;
import java.util.List;
import java.util.UUID;

/**
 * The entries of the accrual runs: {@code charges.accrual_run.requested|started|finished}. The plan, the period, the status,
 * the counters and the totals by currency: identifiers, dates, counts and amounts as exact text, never a name or any
 * personal datum. {@code requested} is written by the staff member who asked, in the transaction of the request; {@code
 * started} and {@code finished} by the {@code system} actor of the worker, in the transaction that changes the run.
 */
final class AccrualAudit {

    static final String RUN = "charges.accrual_run";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String ACTOR_LABEL = "charges-accrual";

    private AccrualAudit() {
    }

    static AuditEntry requested(AuthenticatedActor actor, UUID requestId, AccrualRun run) {
        return new AuditEntry(actor.institutionId(), actor.accountId(), "staff", actor.accountId().toString(), null, null,
                requestId, null, RUN + ".requested", RUN, run.id().toString(), OUTCOME_SUCCESS, null,
                state(run, List.of()), null, null);
    }

    static AuditEntry started(UUID institutionId, UUID requestId, AccrualRun run) {
        return system(institutionId, requestId, RUN + ".started", run, List.of());
    }

    static AuditEntry finished(UUID institutionId, UUID requestId, AccrualRun run,
            List<ChargeRepository.CurrencyTotals> totals) {
        return system(institutionId, requestId, RUN + ".finished", run, totals);
    }

    private static AuditEntry system(UUID institutionId, UUID requestId, String action, AccrualRun run,
            List<ChargeRepository.CurrencyTotals> totals) {
        return new AuditEntry(institutionId, null, "system", ACTOR_LABEL, null, null, requestId, null, action, RUN,
                run.id().toString(), OUTCOME_SUCCESS, null, state(run, totals), null, null);
    }

    private static String state(AccrualRun run, List<ChargeRepository.CurrencyTotals> totals) {
        AuditJson.Builder json = AuditJson.object().text("planId", run.planId().toString())
                .text("periodStart", run.periodStart().toString()).text("status", run.status().name())
                .bool("previewChecked", run.previewDigest() != null)
                .number("studentsEvaluated", run.counters().studentsEvaluated())
                .number("chargesCreated", run.counters().chargesCreated())
                .number("chargesExisting", run.counters().chargesExisting())
                .number("chargesFailed", run.counters().chargesFailed());
        if (run.failureCode() != null) {
            json.text("failureCode", run.failureCode());
        }
        for (ChargeRepository.CurrencyTotals total : totals) {
            String currency = total.currency().name();
            json.text("base" + currency, total.base().toPlainString()).text("tax" + currency, total.tax().toPlainString())
                    .text("total" + currency, total.total().toPlainString());
        }
        return json.toJson();
    }
}

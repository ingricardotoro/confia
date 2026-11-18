package com.confia.charges.application;

import com.confia.charges.domain.IntegrityRun;
import com.confia.charges.domain.ResourceNotFoundException;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.TransactionRunner;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The reading of the results of the nightly integrity check of the institution of the actor. They hold identifiers, counts and
 * amounts, no personal datum, so the reading is not audited.
 */
public final class ViewIntegrityRuns {

    private final TransactionRunner transactionRunner;
    private final IntegrityRunRepository runs;

    public ViewIntegrityRuns(TransactionRunner transactionRunner, IntegrityRunRepository runs) {
        this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner");
        this.runs = Objects.requireNonNull(runs, "runs");
    }

    /** A run with what it found. */
    public record Detail(IntegrityRunView run, List<IntegrityFindingView> findings) {

        public Detail {
            findings = List.copyOf(findings);
        }
    }

    public Page<IntegrityRunView> list(AuthenticatedActor actor, String requestId, int page, int size) {
        Objects.requireNonNull(actor, "actor");
        PageRequest request = new PageRequest(page, size, "finishedAt", false);
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(ChargesAudit.requestId(requestId).toString()),
                () -> runs.list(institution, request.page(), request.size()).map(IntegrityRunView::of));
    }

    /** The most recent run; {@code resource-not-found} when the check has not run for the institution yet. */
    public Detail latest(AuthenticatedActor actor, String requestId) {
        Objects.requireNonNull(actor, "actor");
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(ChargesAudit.requestId(requestId).toString()),
                () -> {
                    IntegrityRun run = runs.latest(institution).orElseThrow(ResourceNotFoundException::new);
                    return detail(institution, run);
                });
    }

    public Detail get(AuthenticatedActor actor, String requestId, UUID runId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(runId, "runId");
        InstitutionId institution = new InstitutionId(actor.institutionId());
        return transactionRunner.executeReadOnly(actor.securityContext(ChargesAudit.requestId(requestId).toString()),
                () -> {
                    IntegrityRun run = runs.findById(institution, runId).orElseThrow(ResourceNotFoundException::new);
                    return detail(institution, run);
                });
    }

    private Detail detail(InstitutionId institution, IntegrityRun run) {
        return new Detail(IntegrityRunView.of(run),
                runs.findings(institution, run.id()).stream().map(IntegrityFindingView::of).toList());
    }
}

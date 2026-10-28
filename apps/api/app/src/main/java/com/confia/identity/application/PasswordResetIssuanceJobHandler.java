package com.confia.identity.application;

import com.confia.identity.domain.StaffAccountId;
import com.confia.shared.jobs.JobContext;
import com.confia.shared.jobs.JobHandler;
import java.util.Objects;

/**
 * Runs one execution of the password-reset issuance job (background-jobs-with-db-scheduler design.md
 * decision 18). It issues the token as the {@code system} actor in the institution of the task, and
 * the use case revalidates the account against the database, so the task data is never the source
 * of truth. Every decision of the use case completes the job: an account that is gone or in another
 * institution ends as "not found", and a fourth issuance within the hour is skipped; only a thrown
 * failure makes the job retry.
 *
 * <p>Running it twice with the same data leaves one open token: the second issuance supersedes the
 * first (ADR-0016 compliance 5).
 */
public final class PasswordResetIssuanceJobHandler implements JobHandler<PasswordResetIssuanceJob> {

    private final IssuePasswordResetToken issuance;

    public PasswordResetIssuanceJobHandler(IssuePasswordResetToken issuance) {
        this.issuance = Objects.requireNonNull(issuance, "issuance");
    }

    @Override
    public void handle(PasswordResetIssuanceJob payload, JobContext context) {
        issuance.execute(context.systemContext(), new StaffAccountId(payload.accountId()));
    }
}

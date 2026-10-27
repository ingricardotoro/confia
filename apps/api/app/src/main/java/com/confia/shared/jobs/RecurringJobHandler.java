package com.confia.shared.jobs;

import com.confia.kernel.InstitutionId;

/**
 * The code a module supplies to run one occurrence of a {@link RecurringJob} for one institution
 * (background-jobs-with-db-scheduler design.md decisions 7 and 10). The worker calls it once per
 * configured institution, and a failure for one institution does not stop the others.
 */
@FunctionalInterface
public interface RecurringJobHandler {

    void runFor(InstitutionId institution, RecurringRun run);
}

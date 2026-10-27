package com.confia.shared.jobs.infrastructure.wiring;

import com.confia.shared.jobs.JobScheduler;
import com.confia.shared.jobs.infrastructure.DbSchedulerJobScheduler;
import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import com.confia.shared.security.TransactionalDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The job scheduling client of the processes that schedule but never execute: the administrative
 * process and, from the next slice, the portal (background-jobs-with-db-scheduler design.md decision
 * 14; ADR-0016 rule 1). It declares the {@link JobScheduler} port and the strict serializer of the
 * task data, and nothing else. The library's {@code SchedulerClient} is built inside {@link
 * DbSchedulerJobScheduler} and is not a bean, so no bean of {@code com.github.kagkarlsson} exists in
 * these processes and none of them holds a {@code Scheduler}.
 *
 * <p>The entry point that imports this class also provides the one {@link TransactionalDataSource}
 * the client is built over, through {@code TransactionRunnerConfiguration}. The worker does not
 * import it: it has its own executor and its own serializer bean.
 */
@Configuration(proxyBeanMethods = false)
public class JobSchedulingConfiguration {

    @Bean
    TracedJobSerializer tracedJobSerializer() {
        return new TracedJobSerializer();
    }

    @Bean
    JobScheduler jobScheduler(TransactionalDataSource transactionalDataSource, TracedJobSerializer serializer) {
        return new DbSchedulerJobScheduler(transactionalDataSource, serializer);
    }
}

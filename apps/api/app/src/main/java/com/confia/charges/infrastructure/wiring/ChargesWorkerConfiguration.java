package com.confia.charges.infrastructure.wiring;

import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.charges.application.AccrualRunItemRepository;
import com.confia.charges.application.AccrualRunJob;
import com.confia.charges.application.AccrualRunJobHandler;
import com.confia.charges.application.AccrualRunJobs;
import com.confia.charges.application.AccrualRunRepository;
import com.confia.charges.application.ChargePlanItemRepository;
import com.confia.charges.application.ChargePlanRepository;
import com.confia.charges.application.ChargeRepository;
import com.confia.charges.application.IntegrityRunRepository;
import com.confia.charges.application.RunChargesIntegrityCheck;
import com.confia.charges.infrastructure.JooqIntegrityRunRepository;
import com.confia.ledger.application.api.LedgerIntegrityApi;
import com.confia.shared.observability.events.OperationalEventLog;
import com.confia.charges.application.ExecuteAccrualRun;
import com.confia.charges.application.PostingAccountRepository;
import com.confia.charges.infrastructure.JooqAccrualRunItemRepository;
import com.confia.charges.infrastructure.JooqAccrualRunRepository;
import com.confia.charges.infrastructure.JooqChargePlanItemRepository;
import com.confia.charges.infrastructure.JooqChargePlanRepository;
import com.confia.charges.infrastructure.JooqChargeRepository;
import com.confia.charges.infrastructure.JooqPostingAccountRepository;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.jobs.JobDefinition;
import com.confia.shared.jobs.OneTimeJob;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.EnrollmentRoster;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registration of {@code charges} for the worker process (docs/cortes/charges-accrual.md; ADR-0016): the adapters the
 * accrual job reads and writes with, the use case that executes a run, its handler and the one-time job, and the recurring
 * integrity check. The worker is the
 * only process that executes it; the administrative process only schedules it. Nothing of the web layer, no controller and no
 * use case of the plans comes with it.
 */
@Configuration(proxyBeanMethods = false)
public class ChargesWorkerConfiguration {

    @Bean
    ChargePlanRepository chargePlanRepository(DSLContext dsl) {
        return new JooqChargePlanRepository(dsl);
    }

    @Bean
    ChargePlanItemRepository chargePlanItemRepository(DSLContext dsl) {
        return new JooqChargePlanItemRepository(dsl);
    }

    @Bean
    ChargeRepository chargeRepository(DSLContext dsl) {
        return new JooqChargeRepository(dsl);
    }

    @Bean
    AccrualRunRepository accrualRunRepository(DSLContext dsl) {
        return new JooqAccrualRunRepository(dsl);
    }

    @Bean
    AccrualRunItemRepository accrualRunItemRepository(DSLContext dsl) {
        return new JooqAccrualRunItemRepository(dsl);
    }

    @Bean
    PostingAccountRepository postingAccountRepository(DSLContext dsl) {
        return new JooqPostingAccountRepository(dsl);
    }

    @Bean
    ExecuteAccrualRun executeAccrualRun(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository planItems, AccrualRunRepository runs, AccrualRunItemRepository runItems,
            ChargeRepository charges, PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, PostingAccountRepository postingAccounts, LedgerAccountDirectory ledgerAccounts,
            AwardBenefits awards, LedgerPostingApi ledger, AuditLogWriter auditLogWriter, Clock clock) {
        return new ExecuteAccrualRun(transactionRunner, plans, planItems, runs, runItems, charges, placements, roster,
                students, pricing, postingAccounts, ledgerAccounts, awards, ledger, auditLogWriter, clock);
    }

    @Bean
    IntegrityRunRepository integrityRunRepository(DSLContext dsl) {
        return new JooqIntegrityRunRepository(dsl);
    }

    /** The nightly integrity check of the charges against the ledger (docs/cortes/charges-void-and-integrity.md). */
    @Bean
    RunChargesIntegrityCheck runChargesIntegrityCheck(TransactionRunner transactionRunner, ChargeRepository charges,
            LedgerIntegrityApi ledger, IntegrityRunRepository runs, AuditLogWriter auditLogWriter, Clock clock) {
        return new RunChargesIntegrityCheck(transactionRunner, charges, ledger, runs, auditLogWriter,
                new OperationalEventLog(), clock);
    }

    /** Every day at 02:30 in Tegucigalpa, for each configured institution; only the worker executes it (ADR-0016). */
    @Bean
    JobDefinition chargesIntegrityCheckJob(RunChargesIntegrityCheck check) {
        return check.job();
    }

    @Bean
    AccrualRunJobHandler accrualRunJobHandler(ExecuteAccrualRun execution) {
        return new AccrualRunJobHandler(execution);
    }

    @Bean
    JobDefinition accrualRunJob(AccrualRunJobHandler handler) {
        return new OneTimeJob<AccrualRunJob>(AccrualRunJobs.TYPE, handler);
    }
}

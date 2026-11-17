package com.confia.charges.infrastructure.wiring;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.charges.application.AccrualRunItemRepository;
import com.confia.charges.application.AccrualRunRepository;
import com.confia.charges.application.AccrualRunScheduler;
import com.confia.charges.application.ChargeRepository;
import com.confia.charges.application.ManageAccrual;
import com.confia.charges.application.ManagePostingAccounts;
import com.confia.charges.application.PostingAccountRepository;
import com.confia.charges.infrastructure.JobAccrualRunScheduler;
import com.confia.charges.infrastructure.JooqAccrualRunItemRepository;
import com.confia.charges.infrastructure.JooqAccrualRunRepository;
import com.confia.charges.infrastructure.JooqChargeRepository;
import com.confia.charges.infrastructure.JooqPostingAccountRepository;
import com.confia.charges.infrastructure.PostingAccountUsage;
import com.confia.ledger.application.api.LedgerAccountUsage;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.shared.jobs.JobScheduler;
import com.confia.students.application.directory.EnrollmentRoster;
import com.confia.students.application.directory.StudentDirectory;
import com.confia.charges.application.ChargePlanItemRepository;
import com.confia.charges.application.ChargePlanRepository;
import com.confia.charges.application.ManageChargePlans;
import com.confia.charges.infrastructure.JooqChargePlanItemRepository;
import com.confia.charges.infrastructure.JooqChargePlanRepository;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code charges} for the administrative process (docs/cortes/charges-plans.md; ADR-0024): the
 * jOOQ adapters of the charge plans and their items, and the use case over them. The controller is registered by {@code
 * ChargesWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer and a clock, which
 * only the administrative process has, and the directories that {@code catalog} and {@code organization} publish.
 */
@Configuration(proxyBeanMethods = false)
public class ChargesConfiguration {

    @Bean
    ChargePlanRepository chargePlanRepository(DSLContext dsl) {
        return new JooqChargePlanRepository(dsl);
    }

    @Bean
    ChargePlanItemRepository chargePlanItemRepository(DSLContext dsl) {
        return new JooqChargePlanItemRepository(dsl);
    }

    @Bean
    ManageChargePlans manageChargePlans(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository items, PaymentConceptDirectory concepts, AcademicStructureDirectory structure,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageChargePlans(transactionRunner, plans, items, concepts, structure, auditLogWriter, clock);
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

    /** The map of posting accounts, read and set through the routes of the chart of accounts (docs/cortes/ledger-chart-of-accounts.md). */
    @Bean
    ManagePostingAccounts managePostingAccounts(TransactionRunner transactionRunner, PostingAccountRepository map,
            LedgerAccountDirectory ledgerAccounts, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManagePostingAccounts(transactionRunner, map, ledgerAccounts, auditLogWriter, clock);
    }

    /** Tells the ledger which accounts the map points at, so it refuses to deactivate one of them. */
    @Bean
    LedgerAccountUsage postingAccountUsage(DSLContext dsl) {
        return new PostingAccountUsage(dsl);
    }

    /** Schedules the accrual job in the transaction that creates the run; only the worker executes it (ADR-0016). */
    @Bean
    AccrualRunScheduler accrualRunScheduler(JobScheduler jobs, Clock clock) {
        return new JobAccrualRunScheduler(jobs, clock);
    }

    @Bean
    ManageAccrual manageAccrual(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository items, AccrualRunRepository runs, AccrualRunItemRepository runItems,
            ChargeRepository charges, PlacementDirectory placements, EnrollmentRoster roster, StudentDirectory students,
            PricingDirectory pricing, PostingAccountRepository postingAccounts, LedgerAccountDirectory ledgerAccounts,
            AccrualRunScheduler scheduler, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageAccrual(transactionRunner, plans, items, runs, runItems, charges, placements, roster, students,
                pricing, postingAccounts, ledgerAccounts, scheduler, auditLogWriter, clock);
    }
}

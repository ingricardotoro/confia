package com.confia.charges.infrastructure.wiring;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.charges.application.AccrualRunItemRepository;
import com.confia.charges.application.AccrualRunRepository;
import com.confia.charges.application.AccrualRunScheduler;
import com.confia.charges.application.ChargeRepository;
import com.confia.charges.application.ChargeVoidRepository;
import com.confia.charges.application.PayableChargesService;
import com.confia.charges.application.api.ChargePayments;
import com.confia.charges.application.api.PayableCharges;
import com.confia.charges.application.IntegrityRunRepository;
import com.confia.charges.application.ListStudentCharges;
import com.confia.charges.application.ViewIntegrityRuns;
import com.confia.charges.application.VoidCharge;
import com.confia.charges.infrastructure.JooqChargeVoidRepository;
import com.confia.charges.infrastructure.JooqIntegrityRunRepository;
import com.confia.ledger.application.api.LedgerPostingApi;
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
import com.confia.ledger.application.api.LedgerStatementApi;
import com.confia.charges.application.ViewStudentStatement;
import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.charges.application.SimulateCharges;
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

    @Bean
    ChargeVoidRepository chargeVoidRepository(DSLContext dsl) {
        return new JooqChargeVoidRepository(dsl);
    }

    @Bean
    IntegrityRunRepository integrityRunRepository(DSLContext dsl) {
        return new JooqIntegrityRunRepository(dsl);
    }

    /**
     * The void of a charge: the ledger reversal and the void record in one database transaction (charges:void). It REQUIRES the
     * {@link ChargePayments} that {@code payments} publishes: a process that loads this configuration without the payments fails at
     * startup, instead of voiding charges that have payments with a silent "no payments" fallback.
     */
    @Bean
    VoidCharge voidCharge(TransactionRunner transactionRunner, ChargeRepository charges, ChargeVoidRepository voids,
            LedgerPostingApi ledger, ChargePayments payments, AuditLogWriter auditLogWriter,
            Clock clock) {
        return new VoidCharge(transactionRunner, charges, voids, ledger, payments,
                auditLogWriter, clock);
    }

    /** The charges of a student, to pick one to void. A read, with one audit entry. */
    @Bean
    ListStudentCharges listStudentCharges(TransactionRunner transactionRunner, StudentDirectory students,
            ChargeRepository charges, PaymentConceptDirectory concepts, ChargePayments payments,
            AuditLogWriter auditLogWriter) {
        return new ListStudentCharges(transactionRunner, students, charges, concepts,
                payments, auditLogWriter);
    }

    /** What a payment can be applied to: the receivable account and the active charges of a student (payments). */
    @Bean
    PayableCharges payableCharges(ChargeRepository charges, PostingAccountRepository postingAccounts,
            PaymentConceptDirectory concepts) {
        return new PayableChargesService(charges, postingAccounts, concepts);
    }

    /** The results of the nightly integrity check, which only the worker produces. */
    @Bean
    ViewIntegrityRuns viewIntegrityRuns(TransactionRunner transactionRunner, IntegrityRunRepository runs) {
        return new ViewIntegrityRuns(transactionRunner, runs);
    }

    /** The derived statement of a student (docs/cortes/ledger-account-statement.md): a read, with one audit entry. */
    @Bean
    ViewStudentStatement viewStudentStatement(TransactionRunner transactionRunner, StudentDirectory students,
            LedgerStatementApi ledgerStatements, ChargeRepository charges, PaymentConceptDirectory concepts,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ViewStudentStatement(transactionRunner, students, ledgerStatements, charges, concepts,
                auditLogWriter, clock);
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
            AwardBenefits awards, DiscountBenefits discounts, AccrualRunScheduler scheduler, AuditLogWriter auditLogWriter,
            Clock clock) {
        return new ManageAccrual(transactionRunner, plans, items, runs, runItems, charges, placements, roster, students,
                pricing, postingAccounts, ledgerAccounts, awards, discounts, scheduler, auditLogWriter, clock);
    }

    /**
     * The simulator of the charges of a student: the code of the accrual engine, which applies the approved scholarships, with
     * nothing written but the audit entry of the consultation. It REQUIRES the {@link AwardBenefits} and the {@link DiscountBenefits} that {@code scholarships}
     * publishes: a process that loads this configuration without it fails at startup, instead of simulating without benefits.
     */
    @Bean
    SimulateCharges simulateCharges(TransactionRunner transactionRunner, ChargePlanRepository plans,
            ChargePlanItemRepository items, ChargeRepository charges, PlacementDirectory placements,
            EnrollmentRoster roster, StudentDirectory students, PricingDirectory pricing, AwardBenefits awards,
            DiscountBenefits discounts,
            AuditLogWriter auditLogWriter) {
        return new SimulateCharges(transactionRunner, plans, items, charges, placements, roster, students, pricing, awards, discounts,
                auditLogWriter);
    }
}

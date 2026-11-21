package com.confia.payments.infrastructure.wiring;

import com.confia.cashbox.application.api.CashPayments;
import com.confia.charges.application.api.ChargePayments;
import com.confia.charges.application.api.PayableCharges;
import com.confia.identity.application.directory.OwnRecordPolicy;
import com.confia.ledger.application.api.LedgerAccountDirectory;
import com.confia.ledger.application.api.LedgerAccountUsage;
import com.confia.ledger.application.api.LedgerPostingApi;
import com.confia.payments.application.GetPayment;
import com.confia.payments.application.ListPayments;
import com.confia.payments.application.ManagePostingAccounts;
import com.confia.payments.application.PaymentAllocationRepository;
import com.confia.payments.application.PaymentDecisionRepository;
import com.confia.payments.application.PaymentRepository;
import com.confia.payments.application.PaymentScope;
import com.confia.payments.application.PostingAccountRepository;
import com.confia.payments.application.RegisterPayment;
import com.confia.payments.infrastructure.AllocationChargePayments;
import com.confia.payments.infrastructure.JooqPaymentAllocationRepository;
import com.confia.payments.infrastructure.JooqPaymentDecisionRepository;
import com.confia.payments.infrastructure.JooqPaymentRepository;
import com.confia.payments.infrastructure.JooqPostingAccountRepository;
import com.confia.payments.infrastructure.PostingAccountUsage;
import com.confia.payments.infrastructure.RoleDirectoryPaymentScope;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import com.confia.students.application.directory.StudentDirectory;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code payments} for the administrative process (docs/cortes/payments-register.md; ADR-0024): the
 * jOOQ adapters of the payments, their decisions, their allocations and the map of posting accounts, the use cases over them, and
 * the two ports that other modules consume ({@code ChargePayments}, which tells {@code charges} what has been paid, and {@code
 * LedgerAccountUsage}, which tells the ledger which accounts the map points at). The controllers are registered by {@code
 * PaymentsWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer and a clock, which only
 * the administrative process has, and the public APIs that {@code ledger}, {@code charges}, {@code cashbox}, {@code students}
 * and {@code identity} publish. The portal and the worker never load it.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentsConfiguration {

    @Bean
    PaymentRepository paymentRepository(DSLContext dsl) {
        return new JooqPaymentRepository(dsl);
    }

    @Bean
    PaymentDecisionRepository paymentDecisionRepository(DSLContext dsl) {
        return new JooqPaymentDecisionRepository(dsl);
    }

    @Bean
    PaymentAllocationRepository paymentAllocationRepository(DSLContext dsl) {
        return new JooqPaymentAllocationRepository(dsl);
    }

    @Bean
    PostingAccountRepository paymentsPostingAccountRepository(DSLContext dsl) {
        return new JooqPostingAccountRepository(dsl);
    }

    @Bean
    PaymentScope paymentScope(OwnRecordPolicy scopes) {
        return new RoleDirectoryPaymentScope(scopes);
    }

    /** What has been paid on a charge: charges asks it to show the outstanding amount and to refuse the void of a paid one. */
    @Bean
    ChargePayments chargePayments(PaymentAllocationRepository allocations) {
        return new AllocationChargePayments(allocations);
    }

    /** Tells the ledger which accounts the payments map points at, so it refuses to deactivate one of them. */
    @Bean
    LedgerAccountUsage paymentsPostingAccountUsage(DSLContext dsl) {
        return new PostingAccountUsage(dsl);
    }

    @Bean
    RegisterPayment registerPayment(TransactionRunner transactionRunner, PaymentRepository payments,
            PaymentDecisionRepository decisions, PaymentAllocationRepository allocations,
            PostingAccountRepository postingAccounts, StudentDirectory students, CashPayments cash,
            LedgerPostingApi ledger, LedgerAccountDirectory ledgerAccounts, PayableCharges charges,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new RegisterPayment(transactionRunner, payments, decisions, allocations, postingAccounts, students, cash,
                ledger, ledgerAccounts, charges, auditLogWriter, clock);
    }

    @Bean
    ListPayments listPayments(TransactionRunner transactionRunner, PaymentRepository payments, StudentDirectory students,
            PaymentScope scope) {
        return new ListPayments(transactionRunner, payments, students, scope);
    }

    @Bean
    GetPayment getPayment(TransactionRunner transactionRunner, PaymentRepository payments,
            PaymentDecisionRepository decisions, PaymentAllocationRepository allocations, StudentDirectory students,
            PayableCharges charges, PaymentScope scope) {
        return new GetPayment(transactionRunner, payments, decisions, allocations, students, charges, scope);
    }

    /** The map of the cash and bank accounts, read and set through the routes of the payments map. */
    @Bean
    ManagePostingAccounts managePaymentsPostingAccounts(TransactionRunner transactionRunner,
            PostingAccountRepository postingAccounts, LedgerAccountDirectory ledgerAccounts,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ManagePostingAccounts(transactionRunner, postingAccounts, ledgerAccounts, auditLogWriter, clock);
    }
}

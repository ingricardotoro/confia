package com.confia.ledger.infrastructure.wiring;

import com.confia.ledger.application.LedgerApi;
import com.confia.ledger.application.LedgerAccountRepository;
import com.confia.ledger.application.ManageLedgerAccounts;
import com.confia.ledger.application.api.LedgerAccountUsage;
import com.confia.ledger.application.LedgerPosting;
import com.confia.ledger.application.LedgerQueries;
import com.confia.ledger.application.LedgerRepository;
import com.confia.ledger.infrastructure.JooqLedgerAccountRepository;
import com.confia.ledger.infrastructure.JooqLedgerRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code ledger} for the administrative process (docs/cortes/ledger-core.md): the jOOQ
 * adapter and the two services other modules call, and the chart-of-accounts use case
 * (docs/cortes/ledger-chart-of-accounts.md). The controller is registered by {@code LedgerWebConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {

    @Bean
    LedgerRepository ledgerRepository(DSLContext dsl) {
        return new JooqLedgerRepository(dsl);
    }

    @Bean
    LedgerPosting ledgerPosting(TransactionRunner transactionRunner, LedgerRepository ledger,
            AuditLogWriter auditLogWriter) {
        return new LedgerPosting(transactionRunner, ledger, auditLogWriter);
    }

    /** The public API that {@code charges} posts through (docs/cortes/charges-accrual.md): one bean, two ports. */
    @Bean
    LedgerApi ledgerApi(LedgerPosting ledgerPosting, LedgerRepository ledger) {
        return new LedgerApi(ledgerPosting, ledger);
    }

    @Bean
    LedgerQueries ledgerQueries(TransactionRunner transactionRunner, LedgerRepository ledger) {
        return new LedgerQueries(transactionRunner, ledger);
    }

    @Bean
    LedgerAccountRepository ledgerAccountRepository(DSLContext dsl) {
        return new JooqLedgerAccountRepository(dsl);
    }

    /**
     * The chart of accounts. Every module that points at an account implements {@link LedgerAccountUsage}; the ones of the
     * process are collected here, so the ledger asks them before it deactivates an account and knows none of them.
     */
    @Bean
    ManageLedgerAccounts manageLedgerAccounts(TransactionRunner transactionRunner, LedgerAccountRepository accounts,
            ObjectProvider<LedgerAccountUsage> usages, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageLedgerAccounts(transactionRunner, accounts, usages.orderedStream().toList(), auditLogWriter,
                clock);
    }
}

package com.confia.ledger.infrastructure.wiring;

import com.confia.ledger.application.LedgerPosting;
import com.confia.ledger.application.LedgerQueries;
import com.confia.ledger.application.LedgerRepository;
import com.confia.ledger.infrastructure.JooqLedgerRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code ledger} for the administrative process (docs/cortes/ledger-core.md): the jOOQ
 * adapter and the two services other modules call. No controller exists in cut 1.
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

    @Bean
    LedgerQueries ledgerQueries(TransactionRunner transactionRunner, LedgerRepository ledger) {
        return new LedgerQueries(transactionRunner, ledger);
    }
}

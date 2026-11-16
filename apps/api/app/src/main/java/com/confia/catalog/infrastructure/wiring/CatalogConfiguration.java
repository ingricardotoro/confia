package com.confia.catalog.infrastructure.wiring;

import com.confia.catalog.application.ManagePaymentConcepts;
import com.confia.catalog.application.PaymentConceptRepository;
import com.confia.catalog.infrastructure.JooqPaymentConceptRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code catalog} for the administrative process
 * (docs/cortes/catalog-payment-concepts.md; ADR-0024): the jOOQ adapter of the payment concepts and the use case
 * over it. Its controller is registered by {@code CatalogWebConfiguration}. It needs a {@link DSLContext}, the
 * transactional component, the audit writer and a clock, which only the administrative process has.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

    @Bean
    PaymentConceptRepository paymentConceptRepository(DSLContext dsl) {
        return new JooqPaymentConceptRepository(dsl);
    }

    @Bean
    ManagePaymentConcepts managePaymentConcepts(TransactionRunner transactionRunner,
            PaymentConceptRepository concepts, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManagePaymentConcepts(transactionRunner, concepts, auditLogWriter, clock);
    }
}

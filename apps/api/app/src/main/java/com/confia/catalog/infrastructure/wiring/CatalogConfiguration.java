package com.confia.catalog.infrastructure.wiring;

import com.confia.catalog.application.ManagePaymentConcepts;
import com.confia.catalog.application.ManagePriceLists;
import com.confia.catalog.application.PaymentConceptRepository;
import com.confia.catalog.application.PriceListItemRepository;
import com.confia.catalog.application.PriceListRepository;
import com.confia.catalog.application.ManageTaxRates;
import com.confia.catalog.application.TaxRatePeriodRepository;
import com.confia.catalog.application.TaxRateRepository;
import com.confia.catalog.application.directory.PaymentConceptDirectory;
import com.confia.catalog.infrastructure.JooqPaymentConceptDirectory;
import com.confia.catalog.infrastructure.JooqPaymentConceptRepository;
import com.confia.catalog.infrastructure.JooqPriceListItemRepository;
import com.confia.catalog.infrastructure.JooqPriceListRepository;
import com.confia.catalog.infrastructure.JooqTaxRatePeriodRepository;
import com.confia.catalog.infrastructure.JooqTaxRateRepository;
import com.confia.organization.application.directory.AcademicStructureDirectory;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of {@code catalog} for the administrative process
 * (docs/cortes/catalog-payment-concepts.md, docs/cortes/catalog-price-lists.md, docs/cortes/catalog-tax-rates.md;
 * ADR-0024): the jOOQ adapters of the payment concepts, the price lists and their prices, the tax rates and their
 * periods, and the use cases over them. The controllers are registered by
 * {@code CatalogWebConfiguration}. It needs a {@link DSLContext}, the transactional component, the audit writer and a
 * clock, which only the administrative process has, and the {@link AcademicStructureDirectory} that {@code
 * organization} publishes.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogConfiguration {

    @Bean
    PaymentConceptRepository paymentConceptRepository(DSLContext dsl) {
        return new JooqPaymentConceptRepository(dsl);
    }

    /** The existence and state check of the payment concepts that {@code charges} consumes (docs/cortes/charges-plans.md). */
    @Bean
    PaymentConceptDirectory paymentConceptDirectory(DSLContext dsl) {
        return new JooqPaymentConceptDirectory(dsl);
    }

    @Bean
    ManagePaymentConcepts managePaymentConcepts(TransactionRunner transactionRunner,
            PaymentConceptRepository concepts, TaxRateRepository taxRates, AuditLogWriter auditLogWriter,
            Clock clock) {
        return new ManagePaymentConcepts(transactionRunner, concepts, taxRates, auditLogWriter, clock);
    }

    @Bean
    TaxRateRepository taxRateRepository(DSLContext dsl) {
        return new JooqTaxRateRepository(dsl);
    }

    @Bean
    TaxRatePeriodRepository taxRatePeriodRepository(DSLContext dsl) {
        return new JooqTaxRatePeriodRepository(dsl);
    }

    @Bean
    ManageTaxRates manageTaxRates(TransactionRunner transactionRunner, TaxRateRepository rates,
            TaxRatePeriodRepository periods, AuditLogWriter auditLogWriter, Clock clock) {
        return new ManageTaxRates(transactionRunner, rates, periods, auditLogWriter, clock);
    }

    @Bean
    PriceListRepository priceListRepository(DSLContext dsl) {
        return new JooqPriceListRepository(dsl);
    }

    @Bean
    PriceListItemRepository priceListItemRepository(DSLContext dsl) {
        return new JooqPriceListItemRepository(dsl);
    }

    @Bean
    ManagePriceLists managePriceLists(TransactionRunner transactionRunner, PriceListRepository lists,
            PriceListItemRepository items, PaymentConceptRepository concepts, AcademicStructureDirectory structure,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ManagePriceLists(transactionRunner, lists, items, concepts, structure, auditLogWriter, clock);
    }
}

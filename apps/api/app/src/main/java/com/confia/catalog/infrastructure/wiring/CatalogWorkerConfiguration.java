package com.confia.catalog.infrastructure.wiring;

import com.confia.catalog.application.directory.PricingDirectory;
import com.confia.catalog.infrastructure.JooqPricingDirectory;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registration of {@code catalog} for the worker process (docs/cortes/charges-accrual.md; ADR-0016): the read-only {@link
 * PricingDirectory} that the accrual job prices with, and nothing else.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogWorkerConfiguration {

    @Bean
    PricingDirectory pricingDirectory(DSLContext dsl) {
        return new JooqPricingDirectory(dsl);
    }
}

package com.confia.catalog.web.wiring;

import com.confia.catalog.application.ManagePaymentConcepts;
import com.confia.catalog.application.ManagePriceLists;
import com.confia.catalog.web.PaymentConceptController;
import com.confia.catalog.web.PriceListController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code catalog} for the administrative process (ADR-0024): it
 * registers the controllers of the payment concepts and of the price lists and nothing else. The use cases they take
 * come from {@code CatalogConfiguration}; the controllers are registered here and not there because a controller is a
 * class of the {@code web} layer, and an {@code infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this
 * module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogWebConfiguration {

    @Bean
    PaymentConceptController paymentConceptController(ManagePaymentConcepts concepts) {
        return new PaymentConceptController(concepts);
    }

    @Bean
    PriceListController priceListController(ManagePriceLists priceLists) {
        return new PriceListController(priceLists);
    }
}

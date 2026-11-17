package com.confia.charges.web.wiring;

import com.confia.charges.application.ManageAccrual;
import com.confia.charges.application.ManageChargePlans;
import com.confia.charges.web.AccrualController;
import com.confia.charges.web.ChargePlanController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code charges} for the administrative process (ADR-0024): it registers the
 * controller of the charge plans and nothing else. The use case it takes comes from {@code ChargesConfiguration}; the
 * controller is registered here and not there because a controller is a class of the {@code web} layer, and an {@code
 * infrastructure} package must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this
 * module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class ChargesWebConfiguration {

    @Bean
    ChargePlanController chargePlanController(ManageChargePlans plans) {
        return new ChargePlanController(plans);
    }

    @Bean
    AccrualController accrualController(ManageAccrual accrual) {
        return new AccrualController(accrual);
    }
}

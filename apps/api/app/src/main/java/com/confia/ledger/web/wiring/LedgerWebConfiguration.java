package com.confia.ledger.web.wiring;

import com.confia.ledger.application.ManageLedgerAccounts;
import com.confia.ledger.web.LedgerAccountController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code ledger} for the administrative process (ADR-0024): it registers the
 * controller of the chart of accounts and nothing else. The use case comes from {@code LedgerConfiguration}; the controller is
 * registered here and not there because a controller is a class of the {@code web} layer, and an {@code infrastructure} package
 * must not depend on it.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a controller of this module
 * reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerWebConfiguration {

    @Bean
    LedgerAccountController ledgerAccountController(ManageLedgerAccounts accounts) {
        return new LedgerAccountController(accounts);
    }
}

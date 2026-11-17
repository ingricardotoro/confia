package com.confia.charges.infrastructure.wiring;

import com.confia.catalog.application.directory.PaymentConceptDirectory;
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
}

package com.confia.organization.infrastructure.wiring;

import com.confia.organization.application.directory.PlacementDirectory;
import com.confia.organization.infrastructure.JooqPlacementDirectory;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registration of {@code organization} for the worker process (docs/cortes/charges-accrual.md; ADR-0016): the read-only
 * {@link PlacementDirectory} that the accrual job asks where the students of a plan are, and nothing else. It registers no
 * adapter that reads the institution of a staff token and no use case: the worker has no security chain.
 */
@Configuration(proxyBeanMethods = false)
public class OrganizationWorkerConfiguration {

    @Bean
    PlacementDirectory placementDirectory(DSLContext dsl) {
        return new JooqPlacementDirectory(dsl);
    }
}

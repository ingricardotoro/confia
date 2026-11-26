package com.confia.scholarships.infrastructure.wiring;

import com.confia.scholarships.application.AwardBenefitsService;
import com.confia.scholarships.application.AwardsInForceRepository;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.infrastructure.JooqAwardsInForceRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registration of {@code scholarships} for the worker process (docs/cortes/scholarships-awards.md; ADR-0016): the read-only {@link
 * AwardBenefits} that the accrual job applies the benefits of the approved awards with, and nothing else. The use cases, the
 * controllers and the writes of the scholarships stay administrative.
 */
@Configuration(proxyBeanMethods = false)
public class ScholarshipsWorkerConfiguration {

    @Bean
    AwardsInForceRepository awardsInForceRepository(DSLContext dsl) {
        return new JooqAwardsInForceRepository(dsl);
    }

    @Bean
    AwardBenefits awardBenefits(AwardsInForceRepository awardsInForce) {
        return new AwardBenefitsService(awardsInForce);
    }
}

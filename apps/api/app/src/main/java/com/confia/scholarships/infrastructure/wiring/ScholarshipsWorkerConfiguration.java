package com.confia.scholarships.infrastructure.wiring;

import com.confia.scholarships.application.AwardBenefitsService;
import com.confia.scholarships.application.AwardsInForceRepository;
import com.confia.scholarships.application.DiscountBenefitsService;
import com.confia.scholarships.application.DiscountsInForceRepository;
import com.confia.scholarships.application.api.AwardBenefits;
import com.confia.scholarships.application.api.DiscountBenefits;
import com.confia.scholarships.infrastructure.JooqAwardsInForceRepository;
import com.confia.scholarships.infrastructure.JooqDiscountsInForceRepository;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The registration of {@code scholarships} for the worker process (docs/cortes/scholarships-awards.md; ADR-0016): the read-only {@link
 * AwardBenefits} and {@link DiscountBenefits} that the accrual job applies the benefits of the approved awards and the discounts in force with, and nothing else. The use cases, the
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

    @Bean
    DiscountsInForceRepository discountsInForceRepository(DSLContext dsl) {
        return new JooqDiscountsInForceRepository(dsl);
    }

    @Bean
    DiscountBenefits discountBenefits(DiscountsInForceRepository discountsInForce) {
        return new DiscountBenefitsService(discountsInForce);
    }
}

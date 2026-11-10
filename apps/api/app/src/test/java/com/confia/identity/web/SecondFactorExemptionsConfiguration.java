package com.confia.identity.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Registers {@link SecondFactorExemptions} in the real administrative process a test starts, as an
 * extra source ({@code spring.main.sources}, added by {@link LoginProcess}). It lives in the test
 * tree, so the production artifact has no such class and its only second-factor policy is {@code
 * MfaPolicy.STAFF} (design.md, D14).
 */
@Configuration(proxyBeanMethods = false)
class SecondFactorExemptionsConfiguration {

    @Bean
    @Primary
    SecondFactorExemptions secondFactorExemptions() {
        return new SecondFactorExemptions();
    }
}

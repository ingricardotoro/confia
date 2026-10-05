package com.confia.shared.web.edge;

import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.SecurityHeadersFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The parts of the web edge that the administrative and the portal process share (web-edge-
 * foundations design.md, decisions 7, 8 and 10): the security headers filter and the Problem Details
 * writer with its message catalog. Imported explicitly by {@code AdminApplication} and {@code
 * PortalApplication} (ADR-0024); the worker has no web edge and never loads it.
 *
 * <p>The catalog is {@code i18n/problems.properties}, in UTF-8, the single source of every
 * {@code title} and {@code detail}. Its {@link MessageSource} is named {@code
 * problemMessageSource} and is deliberately not the application's {@code messageSource}, so this
 * catalog is never mixed with the messages of any other use. It is resolved with one fixed locale
 * and never falls back to the system one.
 *
 * <p>Registering a filter as a bean is how Spring Boot adds it to the servlet container, ordered by
 * the {@code Ordered} it implements: {@code SecurityHeadersFilter} is the first filter, ahead of any
 * security filter chain.
 */
@Configuration(proxyBeanMethods = false)
public class WebEdgeConfiguration {

    @Bean
    MessageSource problemMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/problems");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    @Bean
    ProblemResponses problemResponses(@Qualifier("problemMessageSource") MessageSource messages) {
        return new ProblemResponses(messages);
    }

    @Bean
    SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }
}

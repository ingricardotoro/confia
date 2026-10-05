package com.confia.shared.web.edge;

import com.confia.shared.web.problem.ProblemRequestRejectedHandler;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.RequestContextFilter;
import com.confia.shared.web.request.SecurityHeadersFilter;
import com.confia.shared.web.request.SensitiveLogGuard;
import com.confia.shared.web.request.WebEdgeProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * The parts of the web edge that the administrative and the portal process share (web-edge-
 * foundations design.md, decisions 5, 7, 8, 10 and 11): the two request filters, the Problem
 * Details writer with its message catalog, and the handler for the requests the firewall rejects.
 * Imported explicitly by {@code AdminApplication} and {@code PortalApplication} (ADR-0024); the
 * worker has no web edge and never loads it.
 *
 * <p>The catalog is {@code i18n/problems.properties}, in UTF-8, the single source of every
 * {@code title} and {@code detail}. Its {@link MessageSource} is named {@code
 * problemMessageSource} and is deliberately not the application's {@code messageSource}, so this
 * catalog is never mixed with the messages of any other use. It is resolved with one fixed locale
 * and never falls back to the system one.
 *
 * <p>Registering a filter as a bean is how Spring Boot adds it to the servlet container, ordered by
 * the {@code Ordered} each one implements: {@code SecurityHeadersFilter} is the first filter and
 * {@code RequestContextFilter} follows it, both ahead of any security filter chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebEdgeProperties.class)
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

    /**
     * Named so it cannot collide with Spring's own {@code requestContextFilter} bean, which
     * Spring Boot registers for an unrelated purpose.
     */
    @Bean
    RequestContextFilter serverRequestContextFilter(ProblemResponses problems,
            WebEdgeProperties properties) {
        return new RequestContextFilter(problems, properties);
    }

    /**
     * Spring Security's {@code WebSecurity} picks this bean up and hands it to its filter chain
     * proxy, so a request the strict firewall rejects is answered with Problem Details too.
     */
    @Bean
    ProblemRequestRejectedHandler problemRequestRejectedHandler(ProblemResponses problems) {
        return new ProblemRequestRejectedHandler(problems);
    }

    /** Denies the debug and trace logging of raw headers and bodies (decision 22). */
    @Bean
    SensitiveLogGuard sensitiveLogGuard() {
        return new SensitiveLogGuard();
    }

    /**
     * Replaces Tomcat's HTML error report with {@link ProblemErrorReportValve}, so what the
     * container refuses before any filter runs is answered with Problem Details and the base
     * headers too. The context customizer runs when the embedded server is built, and fails the
     * start if the host cannot be reached, rather than leaving the HTML page in place.
     */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> problemErrorReportCustomizer(
            ProblemResponses problems) {
        return factory -> factory.addContextCustomizers(
                context -> ProblemErrorReportValve.install(context.getParent(), problems));
    }
}

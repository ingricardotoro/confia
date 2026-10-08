package com.confia.organization.infrastructure.wiring;

import com.confia.organization.application.CurrentInstitutionProvider;
import com.confia.organization.infrastructure.TokenCurrentInstitutionProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the {@code organization} module for the administrative process
 * (session-tokens-and-web-layer design.md, decision 6; ADR-0024). It registers the adapter that
 * resolves the institution of a request from the authenticated token, and nothing else.
 *
 * <p>{@code ResolveCurrentInstitution} is deliberately not registered: its error codes {@code
 * institution-not-found} and {@code institution-inactive} belong to the first route that resolves
 * the institution against this module.
 */
@Configuration(proxyBeanMethods = false)
public class OrganizationConfiguration {

    @Bean
    CurrentInstitutionProvider currentInstitutionProvider() {
        return new TokenCurrentInstitutionProvider();
    }
}

package com.confia.identity.web.wiring;

import com.confia.identity.application.DescribeCurrentSession;
import com.confia.identity.web.CurrentSessionController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code identity} for the administrative process
 * (session-tokens-and-web-layer design.md decision 13; ADR-0024): it registers the controller of the
 * current session and nothing else. The use case it takes comes from {@code IdentityConfiguration},
 * which owns every use case of the module; the controller is registered here and not there because
 * the controller is a class of the {@code web} layer, and an {@code infrastructure} package that
 * registered it would depend on the web layer it is meant to sit behind.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a
 * controller of this module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityWebConfiguration {

    @Bean
    CurrentSessionController currentSessionController(DescribeCurrentSession describe) {
        return new CurrentSessionController(describe);
    }
}

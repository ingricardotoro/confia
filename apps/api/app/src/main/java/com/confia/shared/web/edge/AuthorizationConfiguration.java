package com.confia.shared.web.edge;

import com.confia.shared.security.PermissionResolver;
import com.confia.shared.web.authorization.AuthorizationMetrics;
import com.confia.shared.web.authorization.PermissionDecider;
import com.confia.shared.web.authorization.PermissionedEndpoints;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The beans of the authorization by permission of the administrative process
 * (rbac-permission-matrix-and-audit-integration design.md D6 and D18): the one closed list of routes
 * with a permission and the decider over the permission resolver of {@code identity}. The list is a
 * single bean on purpose: {@link AdminSecurityConfiguration} gives that same instance to the security
 * chain and to the bearer filter, so there is no second list that can drift from the first. Imported
 * explicitly by {@code AdminApplication} (ADR-0024); the portal forbids the package in its policy and
 * the worker has no web edge.
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationConfiguration {

    @Bean
    PermissionedEndpoints permissionedEndpoints() {
        return PermissionedEndpoints.forAdmin();
    }

    @Bean
    PermissionDecider permissionDecider(PermissionResolver resolver, AuthorizationMetrics metrics) {
        return new PermissionDecider(resolver, metrics);
    }
}

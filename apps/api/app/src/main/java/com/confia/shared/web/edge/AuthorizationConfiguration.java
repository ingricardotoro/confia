package com.confia.shared.web.edge;

import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.PermissionResolver;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.web.authorization.AuthorizationProperties;
import com.confia.shared.web.authorization.DenialWindows;
import com.confia.shared.web.authorization.PermissionDenialAudit;
import com.confia.shared.web.authentication.AuthenticatedEndpoint;
import com.confia.shared.web.authentication.AuthenticatedEndpoints;
import com.confia.shared.web.authentication.SecondFactorEndpoint;
import com.confia.shared.web.authorization.AuthorizationMetrics;
import com.confia.shared.web.authorization.PermissionDecider;
import com.confia.shared.web.authorization.PermissionedEndpoints;
import com.confia.shared.web.authorization.RouteCoverageGuard;
import com.confia.shared.web.authorization.RouteCoverageGuard.Classified;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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
@EnableConfigurationProperties(AuthorizationProperties.class)
public class AuthorizationConfiguration {

    @Bean
    PermissionedEndpoints permissionedEndpoints() {
        return PermissionedEndpoints.forAdmin();
    }

    /**
     * The cap, the window and the queue of summaries of the denials (design.md D10): one instance per
     * process, in memory, bounded by the properties.
     */
    @Bean
    DenialWindows denialWindows(AuthorizationProperties properties, AuthorizationMetrics metrics) {
        return DenialWindows.of(properties, metrics);
    }

    /**
     * The audit of the denials by permission (design.md D9). It depends on the transaction runner, so at
     * shutdown it is destroyed before the data source and can write the summaries still pending.
     */
    @Bean
    PermissionDenialAudit permissionDenialAudit(TransactionRunner runner, AuditLogWriter writer,
            DenialWindows windows, AuthorizationMetrics metrics, Clock clock,
            AuthorizationProperties properties) {
        return new PermissionDenialAudit(runner, writer, windows, metrics, clock, properties.window());
    }

    @Bean
    PermissionDecider permissionDecider(PermissionResolver resolver, AuthorizationMetrics metrics) {
        return new PermissionDecider(resolver, metrics);
    }

    /**
     * The guard of the start (design.md D13): every route of the application is on one of the four lists
     * of this process, built from the same sources the security chain is built from.
     */
    @Bean
    RouteCoverageGuard routeCoverageGuard(Environment environment, PermissionedEndpoints permissioned,
            ListableBeanFactory beans) {
        List<Classified> classified = new ArrayList<>();
        PublicEndpoints.forAdmin(environment).endpoints().forEach(entry -> classified.add(
                new Classified(RouteCoverageGuard.PUBLIC, entry.method(), entry.pattern())));
        AuthenticatedEndpoints authenticated = AuthenticatedEndpoints.forAdmin();
        for (AuthenticatedEndpoint entry : authenticated.endpoints()) {
            classified.add(new Classified(RouteCoverageGuard.AUTHENTICATED, entry.method(),
                    entry.pattern()));
        }
        for (SecondFactorEndpoint entry : authenticated.secondFactorEndpoints()) {
            classified.add(new Classified(RouteCoverageGuard.RESTRICTED, entry.method(),
                    entry.pattern()));
        }
        permissioned.endpoints().forEach(entry -> classified.add(new Classified(
                RouteCoverageGuard.PERMISSIONED, entry.method(), entry.pattern())));
        return new RouteCoverageGuard(classified, beans);
    }
}

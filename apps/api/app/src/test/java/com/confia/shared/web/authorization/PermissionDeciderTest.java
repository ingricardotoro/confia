package com.confia.shared.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.kernel.Permission;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.PermissionResolver;
import com.confia.shared.web.authentication.ActorAuthentication;
import com.confia.shared.web.authorization.access.ResolvedPermissions;
import com.confia.shared.web.harness.RecordingAuthorizationMetrics;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * The decision for one entry of the permission list (rbac-permission-matrix-and-audit-integration
 * design.md D6, D7 and D12; scenarios AZ24 to AZ26, AZ60, AZ61, AZ63): who is anonymous, who has not
 * proved the second factor, who holds the permission, who does not, and what a failing resolver turns
 * into. The decider is built with a counting resolver, so "the resolver is never asked" is a number.
 */
class PermissionDeciderTest {

    private static final PermissionedEndpoint ENDPOINT = new PermissionedEndpoint(HttpMethod.GET,
            "/test/accounts/{id}", Permission.IDENTITY_READ);
    private static final String SENTINEL = "SENTINEL-RESOLVER-6";

    private final AtomicInteger asked = new AtomicInteger();
    private final RecordingAuthorizationMetrics metrics = new RecordingAuthorizationMetrics();

    private AuthorizationManager<RequestAuthorizationContext> deciding(
            PermissionResolver resolver) {
        return new PermissionDecider(actor -> {
            asked.incrementAndGet();
            return resolver.resolve(actor);
        }, metrics).forEndpoint(ENDPOINT);
    }

    private static AuthenticatedActor actor(AuthenticationMethod... methods) {
        return new AuthenticatedActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Set.of(methods), Instant.now(), Instant.now().plusSeconds(600));
    }

    private static RequestAuthorizationContext requestFor(String path) {
        return new RequestAuthorizationContext(new MockHttpServletRequest("GET", path));
    }

    private static AuthenticatedActor fullSession() {
        return actor(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD);
    }

    private static AuthorizationResult decide(AuthorizationManager<RequestAuthorizationContext> manager,
            Authentication authentication, String path) {
        return manager.authorize(() -> authentication, requestFor(path));
    }

    @Test
    void anAnonymousRequestIsDeniedWithoutAskingTheResolver() {
        Authentication anonymous = new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        AuthorizationResult result = decide(deciding(actor -> Set.of(Permission.values())),
                anonymous, "/test/accounts/1");

        assertThat(result.isGranted()).isFalse();
        assertThat(asked).hasValue(0);
    }

    @Test
    void aSessionThatProvedOnlyThePasswordIsDeniedWithoutAskingTheResolver() {
        AuthorizationResult result = decide(deciding(actor -> Set.of(Permission.values())),
                new ActorAuthentication(actor(AuthenticationMethod.PASSWORD)), "/test/accounts/1");

        assertThat(result.isGranted()).isFalse();
        assertThat(asked).hasValue(0);
    }

    @Test
    void aFullSessionThatHoldsThePermissionIsGrantedAfterOneQuestion() {
        AuthorizationResult result = decide(deciding(actor -> Set.of(Permission.IDENTITY_READ)),
                new ActorAuthentication(fullSession()), "/test/accounts/1");

        assertThat(result.isGranted()).isTrue();
        assertThat(asked).hasValue(1);
    }

    @Test
    void aFullSessionThatLacksThePermissionIsDeniedWithTheTypedExceptionOfTheEntry() {
        AuthenticatedActor actor = fullSession();
        AuthorizationManager<RequestAuthorizationContext> manager =
                deciding(who -> Set.of(Permission.PAYMENTS_CREATE));

        assertThatThrownBy(() -> decide(manager, new ActorAuthentication(actor), "/test/accounts/123"))
                .isInstanceOfSatisfying(PermissionDeniedException.class, denied -> {
                    assertThat(denied.permission()).isEqualTo(Permission.IDENTITY_READ);
                    assertThat(denied.routeTemplate())
                            .as("the template of the entry, never the path of the request")
                            .isEqualTo("/test/accounts/{id}").doesNotContain("123");
                    assertThat(denied.actor()).isEqualTo(actor);
                });
    }

    @Test
    void thePermissionsItResolvedTravelOnTheRequestSoNoOneResolvesThemAgain() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test/accounts/1");
        PermissionDecider decider = new PermissionDecider(who -> Set.of(Permission.IDENTITY_READ, Permission.STUDENTS_READ), metrics);
        decider.forEndpoint(ENDPOINT).authorize(() -> new ActorAuthentication(fullSession()),
                new RequestAuthorizationContext(request));
        assertThat(request.getAttribute(ResolvedPermissions.ATTRIBUTE))
                .isEqualTo(Set.of(Permission.IDENTITY_READ, Permission.STUDENTS_READ));
    }

    @Test
    void aRouteThatNeedsTwoPermissionsGrantsOnlyWhoHoldsBothAndNamesTheMissingOne() {
        PermissionedEndpoint both = new PermissionedEndpoint(HttpMethod.POST, "/test/both", Permission.GUARDIANS_CREATE,
                java.util.List.of(Permission.GUARDIANS_UPDATE));
        PermissionDecider decider = new PermissionDecider(actor -> Set.of(Permission.GUARDIANS_CREATE), metrics);
        AuthenticatedActor actor = fullSession();
        assertThatThrownBy(() -> decide(decider.forEndpoint(both), new ActorAuthentication(actor), "/test/both"))
                .isInstanceOfSatisfying(PermissionDeniedException.class,
                        denied -> assertThat(denied.permission()).isEqualTo(Permission.GUARDIANS_UPDATE));
        PermissionDecider holder = new PermissionDecider(
                who -> Set.of(Permission.GUARDIANS_CREATE, Permission.GUARDIANS_UPDATE), metrics);
        assertThat(decide(holder.forEndpoint(both), new ActorAuthentication(actor), "/test/both").isGranted())
                .isTrue();
        PermissionDecider onlyUpdate = new PermissionDecider(who -> Set.of(Permission.GUARDIANS_UPDATE), metrics);
        assertThatThrownBy(() -> decide(onlyUpdate.forEndpoint(both), new ActorAuthentication(actor), "/test/both"))
                .isInstanceOfSatisfying(PermissionDeniedException.class,
                        denied -> assertThat(denied.permission()).isEqualTo(Permission.GUARDIANS_CREATE));
    }

    @Test
    void anAccountWithNoPermissionAtAllIsDeniedLikeAnyOtherLackOfIt() {
        AuthorizationManager<RequestAuthorizationContext> manager = deciding(who -> Set.of());

        assertThatThrownBy(() -> decide(manager, new ActorAuthentication(fullSession()), "/x"))
                .isInstanceOf(PermissionDeniedException.class);
    }

    @Test
    void theActorAuthenticationOfTheChainCarriesNeitherAnAuthorityNorACredential() {
        ActorAuthentication authentication = new ActorAuthentication(fullSession());

        assertThat(authentication.getAuthorities()).isEmpty();
        assertThat(authentication.getCredentials()).isNull();
    }

    @Test
    void aFailingResolverBecomesAnExceptionWithNoCauseAndAFixedMessage() {
        AuthorizationManager<RequestAuthorizationContext> manager = deciding(who -> {
            throw new IllegalStateException(SENTINEL);
        });

        assertThatThrownBy(() -> decide(manager, new ActorAuthentication(fullSession()), "/x"))
                .isExactlyInstanceOf(PermissionResolutionFailedException.class)
                .hasNoCause().hasMessageNotContaining(SENTINEL);
        assertThat(metrics.resolutionFailedCount()).isEqualTo(1);
    }

    @Test
    void theLogOfAFailureHoldsTheClassesAndTheSqlStateAndNeverTheMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(PermissionDecider.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        AuthorizationManager<RequestAuthorizationContext> manager = deciding(who -> {
            throw new DataAccessResourceFailureException(SENTINEL,
                    new SQLException(SENTINEL, "08006"));
        });
        try {
            assertThatThrownBy(() -> decide(manager, new ActorAuthentication(fullSession()), "/x"))
                    .isInstanceOf(PermissionResolutionFailedException.class);
        } finally {
            logger.detachAppender(logged);
        }

        List<ILoggingEvent> errors = logged.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        String line = errors.get(0).getFormattedMessage() + " " + errors.get(0).getThrowableProxy();
        assertThat(line).contains("DataAccessResourceFailureException").contains("08006")
                .doesNotContain(SENTINEL);
    }
}

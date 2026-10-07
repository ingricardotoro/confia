package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * The principal of an authenticated request (session-tokens-and-web-layer design.md, decision 5): an
 * immutable record of what the verified token said, bound for the whole of a request in a {@link
 * ScopedValue} exactly like {@link RequestOrigin}. These are the types only; the filter that binds it
 * belongs to the next task.
 */
class AuthenticatedActorTest {

    private static final UUID ACCOUNT = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");
    private static final UUID INSTITUTION = UUID.fromString("c3a8e9a0-5b1e-4f7e-9d0e-2b6f0a1c4d11");
    private static final UUID SESSION = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5");
    private static final UUID TOKEN_ID = UUID.fromString("9b2e7c4a-1d3f-4a5b-86c7-d8e9f0a1b2c3");
    private static final Instant ISSUED = Instant.parse("2026-10-12T10:00:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-12T10:10:00Z");

    private static AuthenticatedActor actor(UUID account) {
        return new AuthenticatedActor(account, INSTITUTION, SESSION, TOKEN_ID,
                EnumSet.of(AuthenticationMethod.PASSWORD), ISSUED, EXPIRES);
    }

    @Test
    void outsideARequestThereIsNoCurrentActor() {
        assertThat(AuthenticatedActor.current()).isEmpty();
        assertThat(AuthenticatedActor.CURRENT.isBound()).isFalse();
    }

    @Test
    void insideTheScopeTheCurrentActorIsTheBoundOneAndItIsGoneAfterwards() throws Exception {
        AuthenticatedActor bound = actor(ACCOUNT);

        Optional<AuthenticatedActor> inside = ScopedValue.where(AuthenticatedActor.CURRENT, bound)
                .call(AuthenticatedActor::current);

        assertThat(inside).containsSame(bound);
        assertThat(AuthenticatedActor.current()).isEmpty();
    }

    @Test
    void aNestedScopeShadowsTheOuterOneAndTheOuterIsRestoredWhenItEnds() throws Exception {
        AuthenticatedActor outer = actor(ACCOUNT);
        AuthenticatedActor inner = actor(UUID.fromString("00000000-0000-4000-8000-0000000000bb"));

        ScopedValue.where(AuthenticatedActor.CURRENT, outer).call(() -> {
            assertThat(AuthenticatedActor.current()).containsSame(outer);
            ScopedValue.where(AuthenticatedActor.CURRENT, inner).run(
                    () -> assertThat(AuthenticatedActor.current()).containsSame(inner));
            assertThat(AuthenticatedActor.current()).containsSame(outer);
            return null;
        });
    }

    @Test
    void aReusedThreadNeverSeesTheActorOfAnotherRequest() throws Exception {
        ExecutorService single = Executors.newSingleThreadExecutor();
        try {
            single.submit(() -> ScopedValue.where(AuthenticatedActor.CURRENT, actor(ACCOUNT))
                    .run(() -> assertThat(AuthenticatedActor.current()).isPresent())).get();

            assertThat(single.submit(AuthenticatedActor::current).get()).isEmpty();
        } finally {
            single.shutdownNow();
        }
    }

    @Test
    void theSecurityContextIsThatOfAStaffActorWithTheAccountTheInstitutionAndTheGivenRequest() {
        SecurityContext context = actor(ACCOUNT).securityContext("req-123");

        assertThat(context.actorId()).isEqualTo(ACCOUNT.toString());
        assertThat(context.actorKind()).isEqualTo("staff");
        assertThat(context.institutionId()).isEqualTo(INSTITUTION.toString());
        assertThat(context.requestId()).isEqualTo("req-123");
    }

    @Test
    void theMethodsAreAnImmutableCopyOfTheSetTheCallerPassed() {
        Set<AuthenticationMethod> methods = EnumSet.of(AuthenticationMethod.PASSWORD);
        AuthenticatedActor actor = new AuthenticatedActor(ACCOUNT, INSTITUTION, SESSION, TOKEN_ID,
                methods, ISSUED, EXPIRES);

        methods.add(AuthenticationMethod.ONE_TIME_PASSWORD);

        assertThat(actor.methods()).containsExactly(AuthenticationMethod.PASSWORD);
        assertThatThrownBy(() -> actor.methods().add(AuthenticationMethod.ONE_TIME_PASSWORD))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyComponentIsRequired() {
        Set<AuthenticationMethod> methods = EnumSet.of(AuthenticationMethod.PASSWORD);

        assertThatThrownBy(() -> new AuthenticatedActor(null, INSTITUTION, SESSION, TOKEN_ID, methods,
                ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT, null, SESSION, TOKEN_ID, methods,
                ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT, INSTITUTION, null, TOKEN_ID, methods,
                ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT, INSTITUTION, SESSION, null, methods,
                ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT, INSTITUTION, SESSION, TOKEN_ID, null,
                ISSUED, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT, INSTITUTION, SESSION, TOKEN_ID,
                methods, null, EXPIRES)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT, INSTITUTION, SESSION, TOKEN_ID,
                methods, ISSUED, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AuthenticatedActor(ACCOUNT, INSTITUTION, SESSION, TOKEN_ID,
                new HashSet<>(), ISSUED, EXPIRES)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void printingTheActorNeverShowsTheTokenIdentifier() {
        String printed = actor(ACCOUNT).toString();

        assertThat(printed).doesNotContain(TOKEN_ID.toString()).contains(ACCOUNT.toString())
                .contains(INSTITUTION.toString()).contains(SESSION.toString());
    }

    @Test
    void twoActorsBuiltFromTheSameValuesAreEqual() {
        assertThat(actor(ACCOUNT)).isEqualTo(actor(ACCOUNT)).hasSameHashCodeAs(actor(ACCOUNT));
        assertThat(actor(ACCOUNT)).isNotEqualTo(actor(UUID.fromString(
                "00000000-0000-4000-8000-0000000000bb")));
    }

    @Test
    void theMethodsMapToTheirClaimValuesInBothDirections() {
        assertThat(AuthenticationMethod.PASSWORD.claimValue()).isEqualTo("pwd");
        assertThat(AuthenticationMethod.ONE_TIME_PASSWORD.claimValue()).isEqualTo("otp");
        assertThat(AuthenticationMethod.fromClaimValue("pwd")).hasValue(AuthenticationMethod.PASSWORD);
        assertThat(AuthenticationMethod.fromClaimValue("otp"))
                .hasValue(AuthenticationMethod.ONE_TIME_PASSWORD);
        assertThat(AuthenticationMethod.fromClaimValue("PWD")).isEmpty();
        assertThat(AuthenticationMethod.fromClaimValue("mfa")).isEmpty();
        assertThat(AuthenticationMethod.values()).hasSize(2);
    }
}

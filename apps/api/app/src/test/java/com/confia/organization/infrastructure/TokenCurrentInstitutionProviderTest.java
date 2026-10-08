package com.confia.organization.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.SecurityContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * Specs/organization, the production adapter of {@code CurrentInstitutionProvider}
 * (session-tokens-and-web-layer design.md, decision 6; ADR-0009): the institution comes from the
 * authenticated actor and from nothing else, and without an actor the adapter fails closed.
 */
class TokenCurrentInstitutionProviderTest {

    private static final UUID INSTITUTION_A = UUID.fromString("d290f1ee-6c54-4b01-90e6-d701748f0851");
    private static final UUID INSTITUTION_B = UUID.fromString("5a6a5e02-1c2e-4e3a-9d3f-6b2b3a1e9f10");
    private static final UUID SESSION_OF_A = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5");
    private static final int REQUESTS = 50;

    private final TokenCurrentInstitutionProvider provider = new TokenCurrentInstitutionProvider();

    private static AuthenticatedActor actorOf(UUID institution, UUID session) {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        return new AuthenticatedActor(UUID.randomUUID(), institution, session, UUID.randomUUID(),
                Set.of(AuthenticationMethod.PASSWORD), now, now.plusSeconds(600));
    }

    private InstitutionId resolveAs(AuthenticatedActor actor) {
        return ScopedValue.where(AuthenticatedActor.CURRENT, actor)
                .call(provider::currentInstitutionId);
    }

    // --- OR01: the institution is the one of the token ---

    @Test
    void theInstitutionIsTheOneOfTheAuthenticatedActor() {
        assertThat(resolveAs(actorOf(INSTITUTION_A, SESSION_OF_A)))
                .isEqualTo(new InstitutionId(INSTITUTION_A));
        assertThat(resolveAs(actorOf(INSTITUTION_B, SESSION_OF_A)))
                .isEqualTo(new InstitutionId(INSTITUTION_B));
    }

    // --- OR03 and OR11: without an actor it fails closed ---

    @Test
    void withoutAnActorItFailsWithAnInternalErrorAndReturnsNoInstitution() {
        assertThat(AuthenticatedActor.current()).isEmpty();

        assertThatThrownBy(provider::currentInstitutionId)
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void theActorOfOneRequestIsNotResolvedAfterItsScopeEnded() {
        assertThat(resolveAs(actorOf(INSTITUTION_A, SESSION_OF_A)))
                .isEqualTo(new InstitutionId(INSTITUTION_A));

        assertThatThrownBy(provider::currentInstitutionId)
                .isExactlyInstanceOf(IllegalStateException.class);
    }

    @Test
    void theFailureMessageNamesNoInstitutionAndNoClass() {
        assertThatThrownBy(provider::currentInstitutionId)
                .hasMessageNotContaining(INSTITUTION_A.toString())
                .hasMessageNotContaining("TokenCurrentInstitutionProvider");
    }

    // --- OR04: concurrent requests of different institutions never cross ---

    @Test
    void fiftyConcurrentRequestsOfFiftyInstitutionsEachResolveTheirOwn() throws Exception {
        CyclicBarrier allInsideTheirScope = new CyclicBarrier(REQUESTS);
        try (ExecutorService pool = Executors.newFixedThreadPool(REQUESTS)) {
            List<UUID> institutions = new ArrayList<>();
            List<Future<InstitutionId>> resolved = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                UUID institution = UUID.randomUUID();
                institutions.add(institution);
                AuthenticatedActor actor = actorOf(institution, UUID.randomUUID());
                resolved.add(pool.submit(() -> ScopedValue.where(AuthenticatedActor.CURRENT, actor)
                        .call(() -> {
                            // Every request is inside its scope before any of them resolves.
                            allInsideTheirScope.await();
                            return provider.currentInstitutionId();
                        })));
            }
            assertThat(institutions).doesNotHaveDuplicates().hasSize(REQUESTS);
            for (int i = 0; i < REQUESTS; i++) {
                assertThat(resolved.get(i).get()).isEqualTo(new InstitutionId(institutions.get(i)));
            }
        }
    }

    // --- OR05 and OR10: the database context is the one of the token ---

    @Test
    void theDatabaseContextCarriesTheInstitutionOfTheToken() {
        AuthenticatedActor actor = actorOf(INSTITUTION_A, SESSION_OF_A);

        SecurityContext context = actor.securityContext("request-1");

        assertThat(context.institutionId()).isEqualTo(INSTITUTION_A.toString());
        assertThat(context.institutionId()).isEqualTo(resolveAs(actor).value().toString());
        assertThat(context.actorId()).isEqualTo(actor.accountId().toString());
    }

    @Test
    void aTokenOfInstitutionBWithTheSessionOfInstitutionAChecksTheSessionInInstitutionB() {
        // The session check runs with the context of the token, so the family of A, which is not
        // visible from B, does not authenticate the session (the HTTP proof arrives with the
        // current-session route).
        AuthenticatedActor actor = actorOf(INSTITUTION_B, SESSION_OF_A);

        SecurityContext context = actor.securityContext("request-2");

        assertThat(context.institutionId()).isEqualTo(INSTITUTION_B.toString())
                .isNotEqualTo(INSTITUTION_A.toString());
        assertThat(actor.sessionId()).isEqualTo(SESSION_OF_A);
        assertThat(resolveAs(actor)).isEqualTo(new InstitutionId(INSTITUTION_B));
    }
}

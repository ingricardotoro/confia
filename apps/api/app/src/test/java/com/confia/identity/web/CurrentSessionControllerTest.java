package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.DescribeCurrentSession;
import com.confia.identity.application.JdbcStubTransactions;
import com.confia.identity.application.RefreshTokenFamilyRepository;
import com.confia.identity.application.SessionRevocationReason;
import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.web.authentication.AccessTokenRejectedException;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The controller of the current session and its DTO without a container (session-tokens-and-web-layer
 * design.md decision 13): it takes the actor from the principal the filter bound and nothing from
 * the request, hands it to the real use case and copies the view into the DTO; a session that stopped
 * being live is the same {@code 401} as any other rejected credential; and the DTO has exactly the
 * seven approved members. The route, the filter and the database are {@code
 * CurrentSessionEndpointIT}.
 */
class CurrentSessionControllerTest {

    private static final Instant NOON = Instant.parse("2026-10-12T12:00:00Z");
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();

    private static final AuthenticatedActor ACTOR = new AuthenticatedActor(ACCOUNT, INSTITUTION,
            SESSION, UUID.randomUUID(),
            Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD), NOON,
            NOON.plusSeconds(600));

    private final FakeFamilies families = new FakeFamilies();
    private final CurrentSessionController controller = new CurrentSessionController(
            new DescribeCurrentSession(JdbcStubTransactions.runner(new ArrayList<>()), families,
                    Clock.fixed(NOON, ZoneOffset.UTC)));

    private CurrentSessionResponse currentAs(AuthenticatedActor actor) {
        return ScopedValue.where(AuthenticatedActor.CURRENT, actor).call(controller::current);
    }

    @Test
    void theResponseCopiesTheSessionOfTheActorOfTheRequest() {
        families.state = new RefreshTokenFamilyState(SESSION, new StaffAccountId(ACCOUNT), true,
                NOON.minusSeconds(300), NOON.plusSeconds(21_600), null);

        CurrentSessionResponse response = currentAs(ACTOR);

        assertThat(families.asked).as("the family of the token, in the institution of the token")
                .containsExactly(new InstitutionId(INSTITUTION) + "/" + SESSION);
        assertThat(response).isEqualTo(new CurrentSessionResponse(SESSION, ACCOUNT, INSTITUTION,
                List.of("pwd", "otp"), NOON.plusSeconds(600), NOON.plusSeconds(21_600),
                NOON.plusSeconds(1_500)));
    }

    @Test
    void aSessionThatIsNotLiveAnymoreIsTheSameRejectionAsAnyOtherBadCredential() {
        families.state = new RefreshTokenFamilyState(SESSION, new StaffAccountId(ACCOUNT), true,
                NOON.minusSeconds(300), NOON.plusSeconds(21_600), NOON.minusSeconds(1));

        assertThatThrownBy(() -> currentAs(ACTOR)).isInstanceOf(AccessTokenRejectedException.class);
    }

    @Test
    void withoutAnAuthenticatedActorItFailsClosedAndNeverReadsTheStore() {
        assertThatThrownBy(controller::current).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(SESSION.toString());
        assertThat(families.asked).isEmpty();
    }

    @Test
    void theResponseHasExactlyTheSevenApprovedMembersInTheirOrder() {
        assertThat(Arrays.stream(CurrentSessionResponse.class.getRecordComponents())
                .map(RecordComponent::getName).toList())
                .containsExactly("sessionId", "accountId", "institutionId",
                        "authenticationMethods", "accessTokenExpiresAt", "sessionExpiresAt",
                        "idleExpiresAt");
    }

    @Test
    void theResponseKeepsItsOwnCopyOfTheMethods() {
        List<String> methods = new ArrayList<>(List.of("pwd"));
        CurrentSessionResponse response = new CurrentSessionResponse(SESSION, ACCOUNT,
                INSTITUTION, methods, NOON, NOON, NOON);
        methods.add("otp");

        assertThat(response.authenticationMethods()).containsExactly("pwd");
        assertThatThrownBy(() -> response.authenticationMethods().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theResponseNamesNoTypeOfTheDomain() {
        for (RecordComponent component : CurrentSessionResponse.class.getRecordComponents()) {
            assertThat(component.getType().getName()).doesNotContain(".domain.")
                    .doesNotContain("jooq");
        }
    }

    private static final class FakeFamilies implements RefreshTokenFamilyRepository {

        final List<String> asked = new ArrayList<>();
        RefreshTokenFamilyState state;

        @Override
        public Optional<RefreshTokenFamilyState> readById(InstitutionId institutionId,
                UUID familyId) {
            asked.add(institutionId + "/" + familyId);
            return Optional.ofNullable(state);
        }

        @Override
        public Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId,
                UUID familyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(InstitutionId institutionId, RefreshTokenFamily family) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordUse(InstitutionId institutionId, UUID familyId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revokeForReuse(InstitutionId institutionId, UUID familyId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean revoke(InstitutionId institutionId, UUID familyId,
                SessionRevocationReason reason, Instant now) {
            throw new UnsupportedOperationException();
        }
    }
}

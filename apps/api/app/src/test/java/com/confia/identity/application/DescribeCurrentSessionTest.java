package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link DescribeCurrentSession} with the family adapter replaced by a double (testing playbook,
 * section 1; session-tokens-and-web-layer design.md decision 13): what the view holds and where each
 * field comes from, the lookup it asks for, that it reads in a transaction the engine refuses to write
 * in, that a family which stopped being live between the filter and the read is an empty answer and
 * that a failure of the store reaches the caller. The real rows and the real row-level security are
 * {@code CurrentSessionEndpointIT}.
 */
class DescribeCurrentSessionTest {

    private static final Instant NOON = Instant.parse("2026-10-12T12:00:00Z");
    private static final StaffAccountId ANA = new StaffAccountId(UUID.randomUUID());
    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final UUID FAMILY = UUID.randomUUID();
    private static final Instant LAST_USED = NOON.minus(Duration.ofMinutes(5));
    private static final Instant ABSOLUTE_END = NOON.plus(Duration.ofHours(6));

    private final List<String> transaction = new ArrayList<>();
    private final List<String> readOnlyFlags = new ArrayList<>();
    private final FakeFamilies families = new FakeFamilies();
    private final DescribeCurrentSession describe = new DescribeCurrentSession(
            JdbcStubTransactions.runner(transaction, readOnlyFlags), families,
            Clock.fixed(NOON, ZoneOffset.UTC));

    private static AuthenticatedActor actor(Set<AuthenticationMethod> methods) {
        return new AuthenticatedActor(ANA.value(), INSTITUTION, FAMILY, UUID.randomUUID(), methods,
                NOON.minusSeconds(100), NOON.plusSeconds(500));
    }

    private static RefreshTokenFamilyState state(StaffAccountId account, Instant lastUsedAt,
            Instant revokedAt) {
        return new RefreshTokenFamilyState(FAMILY, account, true, lastUsedAt, ABSOLUTE_END,
                revokedAt);
    }

    @Test
    void theViewTakesEachFieldFromItsOwnSource() {
        families.state = state(ANA, LAST_USED, null);
        AuthenticatedActor actor = actor(Set.of(AuthenticationMethod.PASSWORD));

        CurrentSessionView view = describe.execute(actor).orElseThrow();

        assertThat(view.sessionId()).as("the sid of the token").isEqualTo(FAMILY);
        assertThat(view.accountId()).isEqualTo(ANA.value());
        assertThat(view.institutionId()).isEqualTo(INSTITUTION);
        assertThat(view.accessTokenExpiresAt()).as("exp of the token").isEqualTo(actor.expiresAt());
        assertThat(view.sessionExpiresAt()).as("the absolute end of the family")
                .isEqualTo(ABSOLUTE_END);
        assertThat(view.idleExpiresAt()).as("last use plus thirty minutes")
                .isEqualTo(LAST_USED.plus(Duration.ofMinutes(30)));
    }

    @Test
    void theMethodsAreTheAmrValuesInTheOrderOfTheToken() {
        families.state = state(ANA, LAST_USED, null);

        assertThat(describe.execute(actor(Set.of(AuthenticationMethod.PASSWORD))).orElseThrow()
                .authenticationMethods()).containsExactly("pwd");
        assertThat(describe.execute(actor(Set.of(AuthenticationMethod.ONE_TIME_PASSWORD,
                AuthenticationMethod.PASSWORD))).orElseThrow().authenticationMethods())
                .as("password first, whatever the iteration order of the set")
                .containsExactly("pwd", "otp");
    }

    @Test
    void theLookupNamesTheInstitutionAndTheSessionOfTheActorAndNeverTakesTheLock() {
        families.state = state(ANA, LAST_USED, null);

        describe.execute(actor(Set.of(AuthenticationMethod.PASSWORD)));

        assertThat(families.asked).containsExactly(new InstitutionId(INSTITUTION) + "/" + FAMILY);
        assertThat(families.lockCalls).as("it only reads, it never takes the row lock").isZero();
    }

    @Test
    void itReadsInATransactionTheEngineRefusesToWriteIn() {
        families.state = state(ANA, LAST_USED, null);

        describe.execute(actor(Set.of(AuthenticationMethod.PASSWORD)));

        assertThat(readOnlyFlags).contains("true");
        assertThat(transaction).containsExactly("commit");
    }

    @Test
    void aFamilyThatIsNotLiveAnymoreIsAnEmptyAnswer() {
        AuthenticatedActor actor = actor(Set.of(AuthenticationMethod.PASSWORD));

        families.state = null;
        assertThat(describe.execute(actor)).as("unknown").isEmpty();

        families.state = state(ANA, LAST_USED, NOON.minusSeconds(1));
        assertThat(describe.execute(actor)).as("revoked since the filter looked").isEmpty();

        families.state = state(new StaffAccountId(UUID.randomUUID()), LAST_USED, null);
        assertThat(describe.execute(actor)).as("of another account").isEmpty();

        families.state = state(ANA, NOON.minus(Duration.ofMinutes(30)), null);
        assertThat(describe.execute(actor)).as("idle for exactly thirty minutes").isEmpty();

        families.state = state(ANA, LAST_USED, null);
        assertThat(describe.execute(actor)).as("non-vacuous: the live one is described")
                .isPresent();
    }

    @Test
    void aFailureOfTheReadReachesTheCallerAndRollsTheTransactionBack() {
        IllegalStateException failure = new IllegalStateException("the database failed");
        families.failure = failure;

        assertThatThrownBy(() -> describe.execute(actor(Set.of(AuthenticationMethod.PASSWORD))))
                .isSameAs(failure);
        assertThat(transaction).containsExactly("rollback");
    }

    @Test
    void theViewKeepsNothingButItsOwnCopyOfTheMethods() {
        families.state = state(ANA, LAST_USED, null);
        CurrentSessionView view = describe.execute(actor(Set.of(AuthenticationMethod.PASSWORD)))
                .orElseThrow();

        assertThatThrownBy(() -> view.authenticationMethods().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new CurrentSessionView(null, ANA.value(), INSTITUTION, List.of(),
                NOON, NOON, NOON)).isInstanceOf(NullPointerException.class);
    }

    private static final class FakeFamilies implements RefreshTokenFamilyRepository {

        final List<String> asked = new ArrayList<>();
        int lockCalls;
        RefreshTokenFamilyState state;
        RuntimeException failure;

        @Override
        public Optional<RefreshTokenFamilyState> readById(InstitutionId institutionId,
                UUID familyId) {
            asked.add(institutionId + "/" + familyId);
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(state);
        }

        @Override
        public Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId,
                UUID familyId) {
            lockCalls++;
            return Optional.ofNullable(state);
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

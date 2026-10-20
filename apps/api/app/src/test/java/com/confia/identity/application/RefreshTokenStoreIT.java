package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.identity.domain.RefreshTokenState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.kernel.InstitutionId;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;
import org.junit.jupiter.api.Test;

/**
 * The adapters that rotate and read refresh tokens (session-tokens-and-web-layer design.md decision
 * 10), tested on their own against a real PostgreSQL before any use case consumes them: what they
 * read of a stored token and family, the conditional consumption, the emptying of the encrypted
 * successors, the row lock of a family and the forward-only use of a family. The two facts about a
 * token's successor are the ones the grace window acts on, and 4.3 adds the stored encrypted successor,
 * its reading and the emptying by age. Tokens and families are seeded with SQL.
 */
class RefreshTokenStoreIT extends RotationIntegrationTest {

    private static final String EIGHT = "2026-10-12T08:00:00Z";
    private static final String TWENTY = "2026-10-12T20:00:00Z";
    private static final String SIXTEEN = "2026-10-12T16:00:00Z";
    private static final long LIMIT_MILLIS = 20_000;
    private static final String CIPHERTEXT = "v1:AAAA";

    @Test
    void anOpenTokenIsReadWithItsWindowAndNothingConsumed() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        PlainRefreshToken token = seedOpenToken(family, EIGHT, SIXTEEN);

        RefreshTokenState state = findByText(token).orElseThrow();

        assertThat(state.familyId()).isEqualTo(family);
        assertThat(state.issuedAt()).isEqualTo(Instant.parse(EIGHT));
        assertThat(state.expiresAt()).isEqualTo(Instant.parse(SIXTEEN));
        assertThat(state.isConsumed()).isFalse();
        assertThat(state.successorCiphertextPresent()).isFalse();
        assertThat(state.successorConsumed()).isFalse();
    }

    @Test
    void aConsumedTokenSaysWhoPresentedItAndWhetherItsEncryptedSuccessorIsStillThere() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        ConsumedSeed withCiphertext = seedConsumedToken(family, EIGHT, SIXTEEN,
                "2026-10-12T08:05:00Z", AGENT_ONE, true);
        UUID otherFamily = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        ConsumedSeed cleared = seedConsumedToken(otherFamily, EIGHT, SIXTEEN,
                "2026-10-12T08:05:00Z", AGENT_TWO, false);

        RefreshTokenState kept = findByText(withCiphertext.consumed()).orElseThrow();
        RefreshTokenState empty = findByText(cleared.consumed()).orElseThrow();

        assertThat(kept.consumedAt()).isEqualTo(Instant.parse("2026-10-12T08:05:00Z"));
        assertThat(kept.consumedUserAgentFingerprint())
                .isEqualTo(UserAgentFingerprint.of(AGENT_ONE));
        assertThat(kept.successorCiphertextPresent()).isTrue();
        assertThat(kept.successorConsumed()).as("its successor is still open").isFalse();
        assertThat(empty.consumedUserAgentFingerprint())
                .isEqualTo(UserAgentFingerprint.of(AGENT_TWO));
        assertThat(empty.successorCiphertextPresent()).isFalse();
    }

    @Test
    void aTokenWhoseSuccessorWasConsumedInTurnSaysSo() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, "2026-10-12T08:05:00Z", TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(family, EIGHT, SIXTEEN, "2026-10-12T08:05:00Z",
                AGENT_ONE, true);
        UUID successorId = findByText(seed.successor()).orElseThrow().id();
        consumeAndReplace(institutionId, successorId, "2026-10-12T08:10:00Z", family);

        RefreshTokenState state = findByText(seed.consumed()).orElseThrow();

        assertThat(state.successorConsumed()).isTrue();
        assertThat(findByText(seed.successor()).orElseThrow().successorConsumed())
                .as("the newest token has a successor of its own that is open").isFalse();
    }

    @Test
    void aTokenIsFoundByItsIdentifierAndAnUnknownOneIsNot() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        RefreshTokenState byHash = findByText(seedOpenToken(family, EIGHT, SIXTEEN)).orElseThrow();

        Optional<RefreshTokenState> byId = transactionRunner().execute(context(),
                () -> tokens().findById(institutionId, byHash.id()));
        Optional<RefreshTokenState> nobody = transactionRunner().execute(context(),
                () -> tokens().findById(institutionId, UUID.randomUUID()));

        assertThat(byId).contains(byHash);
        assertThat(nobody).isEmpty();
    }

    @Test
    void anotherInstitutionNeitherSeesNorConsumesNorLocksWhatIsNotItsOwn() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        PlainRefreshToken token = seedOpenToken(family, EIGHT, SIXTEEN);
        UUID tokenId = findByText(token).orElseThrow().id();
        InstitutionId other = new InstitutionId(UUID.randomUUID());

        assertThat(transactionRunner().execute(contextOf(other),
                () -> tokens().findByHash(other, RefreshTokenHash.of(token)))).isEmpty();
        assertThat(transactionRunner().execute(contextOf(other),
                () -> tokens().consume(other, tokenId, Instant.parse(EIGHT),
                        UserAgentFingerprint.of(AGENT_ONE), UUID.randomUUID(), CIPHERTEXT))).isFalse();
        assertThat(transactionRunner().execute(contextOf(other),
                () -> families().lockById(other, family))).isEmpty();
        assertThat(findByText(token).orElseThrow().isConsumed()).isFalse();
    }

    @Test
    void aTokenIsConsumedOnceWithWhoPresentedItAndItsSuccessorAndNeverTwice() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        RefreshTokenState open = findByText(seedOpenToken(family, EIGHT, SIXTEEN)).orElseThrow();
        UUID successorId = UUID.randomUUID();

        boolean first = transactionRunner().execute(context(), () -> {
            boolean consumed = tokens().consume(institutionId, open.id(),
                    Instant.parse("2026-10-12T08:05:00Z"), UserAgentFingerprint.of(AGENT_TWO),
                    successorId, CIPHERTEXT);
            tokens().insert(institutionId, rowOf(successorId, family, "2026-10-12T08:05:00Z"));
            return consumed;
        });
        boolean second = transactionRunner().execute(context(), () -> tokens().consume(
                institutionId, open.id(), Instant.parse("2026-10-12T08:06:00Z"),
                UserAgentFingerprint.of(AGENT_ONE), UUID.randomUUID(), CIPHERTEXT));

        assertThat(first).isTrue();
        assertThat(second).as("a consumed token is not consumed again").isFalse();
        Record consumed = tokensOf(family).get(0);
        assertThat(instantOf(consumed, "consumed_at"))
                .isEqualTo(Instant.parse("2026-10-12T08:05:00Z"));
        assertThat(consumed.get("consumed_user_agent_fingerprint", String.class))
                .isEqualTo(UserAgentFingerprint.of(AGENT_TWO).value());
        assertThat(consumed.get("replaced_by", UUID.class)).isEqualTo(successorId);
    }

    /** The encrypted successor is written by the very statement that consumes the token (V8 trigger). */
    @Test
    void consumingATokenStoresItsEncryptedSuccessorInTheSameStatement() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        RefreshTokenState open = findByText(seedOpenToken(family, EIGHT, SIXTEEN)).orElseThrow();
        UUID successorId = UUID.randomUUID();

        transactionRunner().execute(context(), () -> {
            tokens().consume(institutionId, open.id(), Instant.parse("2026-10-12T08:05:00Z"),
                    UserAgentFingerprint.of(AGENT_ONE), successorId, CIPHERTEXT);
            tokens().insert(institutionId, rowOf(successorId, family, "2026-10-12T08:05:00Z"));
            return null;
        });

        assertThat(tokensOf(family).get(0).get("successor_token_ciphertext", String.class))
                .isEqualTo(CIPHERTEXT);
        assertThat(tokensOf(family).get(1).get("successor_token_ciphertext"))
                .as("the successor itself has none until it is consumed").isNull();
        assertThat(findByText(PlainRefreshToken.generate(new SecureRandom()))).isEmpty();
    }

    @Test
    void theEncryptedSuccessorIsReadWithTheExpiryOfTheTokenThatReplacedItWhileItIsStillThere() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(family, EIGHT, SIXTEEN, "2026-10-12T08:05:00Z",
                AGENT_ONE, true);
        UUID consumedId = findByText(seed.consumed()).orElseThrow().id();
        UUID openId = findByText(seed.successor()).orElseThrow().id();
        InstitutionId other = new InstitutionId(UUID.randomUUID());

        Optional<EncryptedSuccessor> read = transactionRunner().execute(context(),
                () -> tokens().findEncryptedSuccessor(institutionId, consumedId));

        assertThat(read).isPresent();
        assertThat(read.get().ciphertext()).isEqualTo(CIPHERTEXT);
        assertThat(read.get().expiresAt()).isEqualTo(Instant.parse(SIXTEEN));
        assertThat(transactionRunner().execute(context(),
                () -> tokens().findEncryptedSuccessor(institutionId, openId)))
                .as("an open token has no successor").isEmpty();
        assertThat(transactionRunner().execute(contextOf(other),
                () -> tokens().findEncryptedSuccessor(other, consumedId)))
                .as("another institution does not see it").isEmpty();
        transactionRunner().execute(context(), () -> {
            tokens().clearSuccessors(institutionId, family);
            return null;
        });
        assertThat(transactionRunner().execute(context(),
                () -> tokens().findEncryptedSuccessor(institutionId, consumedId)))
                .as("once emptied there is nothing to read").isEmpty();
    }

    /** The cutoff is inclusive, as the window is strict: a token consumed exactly at it is outside. */
    @Test
    void emptyingByAgeTouchesOnlyTheSuccessorsConsumedAtOrBeforeTheCutoffOfThatFamily() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        ConsumedSeed seed = seedConsumedToken(family, "2026-10-12T07:00:00Z", SIXTEEN,
                "2026-10-12T08:00:00Z", AGENT_ONE, true);
        consumeAndReplace(institutionId, findByText(seed.successor()).orElseThrow().id(),
                "2026-10-12T08:00:10Z", family);
        UUID bystander = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        seedConsumedToken(bystander, EIGHT, SIXTEEN, "2026-10-12T08:00:00Z", AGENT_ONE, true);

        clearConsumedAtOrBefore(family, "2026-10-11T23:59:59.999Z");
        assertThat(ciphertextsOf(family)).as("nothing is old enough").containsExactly(CIPHERTEXT,
                "v1:BBBB", null);

        clearConsumedAtOrBefore(family, "2026-10-12T08:00:00Z");
        assertThat(ciphertextsOf(family)).as("the border itself is emptied, the later one kept")
                .containsExactly(null, "v1:BBBB", null);

        clearConsumedAtOrBefore(family, "2026-10-12T08:00:10Z");
        assertThat(ciphertextsOf(family)).containsExactly(null, null, null);
        assertThat(ciphertextsOf(bystander)).as("another family is not reached")
                .containsExactlyInAnyOrder(CIPHERTEXT, null);
    }

    @Test
    void emptyingTheSuccessorsReachesOnlyTheTokensOfThatFamily() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID emptied = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        seedConsumedToken(emptied, EIGHT, SIXTEEN, "2026-10-12T08:05:00Z", AGENT_ONE, true);
        UUID kept = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        seedConsumedToken(kept, EIGHT, SIXTEEN, "2026-10-12T08:05:00Z", AGENT_ONE, true);

        transactionRunner().execute(context(), () -> {
            tokens().clearSuccessors(institutionId, emptied);
            return null;
        });

        assertThat(tokensOf(emptied)).hasSize(2).allSatisfy(token ->
                assertThat(token.get("successor_token_ciphertext")).isNull());
        assertThat(tokensOf(kept).get(0).get("successor_token_ciphertext", String.class))
                .isEqualTo("v1:AAAA");
    }

    @Test
    void aFamilyIsReadWithItsStateWhetherLiveOrRevokedAndAnUnknownOneIsNot() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID live = seedFamily(ana, true, EIGHT, "2026-10-12T09:00:00Z", TWENTY, null, null);
        UUID revoked = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, "2026-10-12T08:30:00Z",
                "password_change");

        RefreshTokenFamilyState liveState = lock(live).orElseThrow();
        RefreshTokenFamilyState revokedState = lock(revoked).orElseThrow();

        assertThat(liveState.accountId()).isEqualTo(ana);
        assertThat(liveState.secondFactorVerified()).isTrue();
        assertThat(liveState.lastUsedAt()).isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(liveState.absoluteExpiresAt()).isEqualTo(Instant.parse(TWENTY));
        assertThat(liveState.isRevoked()).isFalse();
        assertThat(revokedState.secondFactorVerified()).isFalse();
        assertThat(revokedState.revokedAt()).isEqualTo(Instant.parse("2026-10-12T08:30:00Z"));
        assertThat(lock(UUID.randomUUID())).isEmpty();
    }

    @Test
    void theUseOfAFamilyMovesForwardAndTheEngineRefusesToMoveItBack() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);

        transactionRunner().execute(context(), () -> {
            families().recordUse(institutionId, family, Instant.parse("2026-10-12T08:20:00Z"));
            return null;
        });

        assertThat(instantOf(familyRow(family), "last_used_at"))
                .isEqualTo(Instant.parse("2026-10-12T08:20:00Z"));
        assertThatThrownBy(() -> transactionRunner().execute(context(), () -> {
            families().recordUse(institutionId, family, Instant.parse("2026-10-12T08:10:00Z"));
            return null;
        })).hasMessageContaining("identity_refresh_token_family_last_used_forward");
    }

    @Test
    void revokingForReuseMarksOnlyThatFamilyOnceAndNeverChangesAnEarlierRevocation() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        UUID bystander = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        UUID alreadyRevoked = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, "2026-10-12T08:30:00Z",
                "password_change");
        String bystanderBefore = familyRow(bystander).formatCSV();

        boolean first = revokeForReuse(family, "2026-10-12T09:00:00Z");
        boolean again = revokeForReuse(family, "2026-10-12T09:05:00Z");
        boolean earlier = revokeForReuse(alreadyRevoked, "2026-10-12T09:10:00Z");

        assertThat(first).isTrue();
        assertThat(again).as("a family is revoked once").isFalse();
        assertThat(earlier).as("an earlier revocation is left as it was").isFalse();
        assertThat(familyRow(family).get("revoked_reason", String.class)).isEqualTo("reuse_detected");
        assertThat(instantOf(familyRow(family), "revoked_at"))
                .isEqualTo(Instant.parse("2026-10-12T09:00:00Z"));
        assertThat(familyRow(alreadyRevoked).get("revoked_reason", String.class))
                .isEqualTo("password_change");
        assertThat(instantOf(familyRow(alreadyRevoked), "revoked_at"))
                .isEqualTo(Instant.parse("2026-10-12T08:30:00Z"));
        assertThat(familyRow(bystander).formatCSV()).isEqualTo(bystanderBefore);
    }

    @Test
    void aFamilyOfAnotherInstitutionIsNotRevokedForReuse() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        InstitutionId other = new InstitutionId(UUID.randomUUID());

        boolean revoked = transactionRunner().execute(contextOf(other),
                () -> families().revokeForReuse(other, family, Instant.parse(EIGHT)));

        assertThat(revoked).isFalse();
        assertThat(familyRow(family).get("revoked_at")).isNull();
    }

    /** The lock is the point of serialization of the family: a second locker waits for the first. */
    @Test
    void lockingAFamilyMakesASecondLockerWaitUntilTheFirstTransactionEnds() throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        boolean waited;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> holder = pool.submit(() -> transactionRunner().execute(context(),
                    () -> {
                        boolean found = families().lockById(institutionId, family).isPresent();
                        locked.countDown();
                        awaitRelease(release);
                        return found;
                    }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Optional<RefreshTokenFamilyState>> second = pool.submit(() -> lock(family));
            waited = holdsWithin(LIMIT_MILLIS, () -> second.isDone()
                    || someBackendWaitsForALockOn("identity_refresh_token_family"))
                    && !second.isDone();
            release.countDown();
            assertThat(holder.get(60, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(60, TimeUnit.SECONDS)).isPresent();
        } finally {
            release.countDown();
        }

        assertThat(waited).as("the second locker waited for the row lock of the first").isTrue();
    }

    @Test
    void aFamilyIsReadWithoutALockWithItsRevocationAndOnlyForItsOwnInstitution() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID live = seedFamily(ana, true, EIGHT, SIXTEEN, TWENTY, null, null);
        UUID revoked = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, SIXTEEN, "password_change");
        InstitutionId other = new InstitutionId(UUID.randomUUID());

        RefreshTokenFamilyState read = peek(live).orElseThrow();
        RefreshTokenFamilyState gone = peek(revoked).orElseThrow();

        assertThat(read.accountId()).isEqualTo(ana);
        assertThat(read.secondFactorVerified()).isTrue();
        assertThat(read.lastUsedAt()).isEqualTo(Instant.parse(SIXTEEN));
        assertThat(read.absoluteExpiresAt()).isEqualTo(Instant.parse(TWENTY));
        assertThat(read.isRevoked()).isFalse();
        assertThat(gone.revokedAt()).as("a revoked family comes back revoked, not missing")
                .isEqualTo(Instant.parse(SIXTEEN));
        assertThat(peek(UUID.randomUUID())).isEmpty();
        assertThat(transactionRunner().execute(contextOf(other),
                () -> families().readById(other, live))).isEmpty();
    }

    /** Asking is not locking: a reader is not kept waiting by the holder of the lock of the family. */
    @Test
    void readingAFamilyDoesNotWaitForTheTransactionThatHoldsItsLock() throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        UUID family = seedFamily(ana, false, EIGHT, EIGHT, TWENTY, null, null);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> holder = pool.submit(() -> transactionRunner().execute(context(), () -> {
                boolean found = families().lockById(institutionId, family).isPresent();
                locked.countDown();
                awaitRelease(release);
                return found;
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            Future<Optional<RefreshTokenFamilyState>> reader = pool.submit(() -> peek(family));

            assertThat(reader.get(LIMIT_MILLIS, TimeUnit.MILLISECONDS))
                    .as("answered while the lock was still held").isPresent();
            release.countDown();
            assertThat(holder.get(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private void clearConsumedAtOrBefore(UUID familyId, String cutoff) {
        transactionRunner().execute(context(), () -> {
            tokens().clearSuccessorsConsumedAtOrBefore(institutionId, familyId,
                    Instant.parse(cutoff));
            return null;
        });
    }

    private java.util.List<String> ciphertextsOf(UUID familyId) {
        return tokensOf(familyId).stream()
                .map(token -> token.get("successor_token_ciphertext", String.class)).toList();
    }

    private JooqRefreshTokenRepository tokens() {
        return new JooqRefreshTokenRepository(dsl);
    }

    private JooqRefreshTokenFamilyRepository families() {
        return new JooqRefreshTokenFamilyRepository(dsl);
    }

    private Optional<RefreshTokenState> findByText(PlainRefreshToken token) {
        return transactionRunner().execute(context(),
                () -> tokens().findByHash(institutionId, RefreshTokenHash.of(token)));
    }

    private Optional<RefreshTokenFamilyState> lock(UUID familyId) {
        return transactionRunner().execute(context(),
                () -> families().lockById(institutionId, familyId));
    }

    private Optional<RefreshTokenFamilyState> peek(UUID familyId) {
        return transactionRunner().execute(context(),
                () -> families().readById(institutionId, familyId));
    }

    private boolean revokeForReuse(UUID familyId, String at) {
        return transactionRunner().execute(context(),
                () -> families().revokeForReuse(institutionId, familyId, Instant.parse(at)));
    }

    private RefreshTokenRow rowOf(UUID tokenId, UUID familyId, String issuedAt) {
        return new RefreshTokenRow(tokenId, familyId,
                RefreshTokenHash.of(PlainRefreshToken.generate(new SecureRandom())),
                Instant.parse(issuedAt), Instant.parse(SIXTEEN));
    }

    /** Consumes {@code tokenId} and inserts the token that replaces it, as one rotation does. */
    private void consumeAndReplace(InstitutionId institution, UUID tokenId, String at,
            UUID familyId) {
        UUID replacement = UUID.randomUUID();
        transactionRunner().execute(context(), () -> {
            tokens().consume(institution, tokenId, Instant.parse(at),
                    UserAgentFingerprint.of(AGENT_ONE), replacement, "v1:BBBB");
            tokens().insert(institution, rowOf(replacement, familyId, at));
            return null;
        });
    }
}

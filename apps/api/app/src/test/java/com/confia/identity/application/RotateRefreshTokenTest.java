package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.application.RefreshOutcome.Rejected;
import com.confia.identity.application.RefreshOutcome.Rotated;
import com.confia.identity.application.RefreshOutcome.RotationKind;
import com.confia.identity.domain.PlainRefreshToken;
import com.confia.identity.domain.RefreshRejection;
import com.confia.identity.domain.RefreshTokenFamily;
import com.confia.identity.domain.RefreshTokenFamilyState;
import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.RefreshTokenRow;
import com.confia.identity.domain.RefreshTokenState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.crypto.DataEncryptionKeyId;
import com.confia.shared.crypto.DataEncryptionKeyMaterial;
import com.confia.shared.crypto.DataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The orchestration of {@link RotateRefreshToken} on its own, with in-memory doubles for every port
 * (testing playbook, section 1): which port calls each {@code RotationDecision} leads to, in which
 * order, and whether the outcome is a returned value or an exception. That a row really changes, and
 * what the lock does, is the job of {@code RotateRefreshTokenIT} and {@code RefreshRotationConcurrencyIT}.
 *
 * <p>The {@code TransactionRunner} is the real one over a JDBC stub that does nothing but remember
 * {@code commit} and {@code rollback}: that is how the tests tell a rejection that is committed (a
 * returned value) from a failure that is rolled back. Every double writes into one shared list, so the
 * order of calls across ports is observable.
 *
 * <p>The encryption service is the real one over an in-memory key that writes {@code encrypt} and
 * {@code decrypt} into the same list, so the tests see when the successor is encrypted and, above all,
 * when it is <b>not</b> decrypted (outside the window, from another agent, after the family advanced).
 *
 * <p>The tests of 4.1 and 4.2 were written against code that already existed, so they started green;
 * the red of the grace window (4.3) is the absence of the new constructor, ports and branch, and the
 * deliberate breaks recorded in apply-progress.md.
 */
class RotateRefreshTokenTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final String NOW = "2026-10-12T09:20:00Z";
    private static final String AGENT = "agente-uno";
    private static final KeyPair KEYS = generatedPair();

    private final List<String> calls = new ArrayList<>();
    private final List<AuditEntry> audited = new ArrayList<>();
    private final FakeFamilies families = new FakeFamilies(calls);
    private final FakeTokens tokens = new FakeTokens(calls);
    private final ColumnEncryptionService encryption = new ColumnEncryptionService(
            new RecordingKeys(calls), new AesGcmCipher());
    private final UUID familyId = UUID.randomUUID();
    private final UUID tokenId = UUID.randomUUID();
    private boolean auditFails;

    @Test
    void anOpenTokenOfALiveFamilyIsRotatedInTheOrderOfTheDecision() {
        families.state = liveFamily();
        tokens.state = openToken();

        RefreshOutcome outcome = execute();

        assertThat(outcome).isInstanceOf(Rotated.class);
        assertThat(((Rotated) outcome).kind()).isEqualTo(RotationKind.FRESH);
        assertThat(((Rotated) outcome).session().sessionId()).isEqualTo(familyId);
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearOld", "encrypt",
                "consume", "insert", "recordUse", "audit:identity.session.refreshed", "commit");
        assertThat(audited).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(entry.entityId()).isEqualTo(familyId.toString());
            assertThat(entry.afterValue()).isEqualTo("{\"rotation\":\"fresh\"}");
        });
    }

    @Test
    void aConditionalUpdateThatMissesUnderTheLockFailsAndRollsBack() {
        families.state = liveFamily();
        tokens.state = openToken();
        tokens.consumeResult = false;

        assertThatThrownBy(this::execute).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be consumed");

        assertThat(calls).doesNotContain("insert", "recordUse").endsWith("rollback");
        assertThat(audited).isEmpty();
    }

    @Test
    void aTextThatCannotBeATokenAndAnUnknownTokenAreRejectedWithoutTakingAnyLock() {
        Rejected malformed = (Rejected) executeWith("not a token");
        tokens.state = null;
        Rejected unknown = (Rejected) execute();

        assertThat(malformed.reason()).isEqualTo(RefreshRejection.UNKNOWN_TOKEN);
        assertThat(unknown.reason()).isEqualTo(RefreshRejection.UNKNOWN_TOKEN);
        assertThat(calls).doesNotContain("lock", "clearOld");
        assertThat(audited).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.action()).isEqualTo("identity.session.refresh_rejected");
            assertThat(entry.entityId()).isEqualTo("unknown-token");
            assertThat(entry.outcome()).isEqualTo("denied");
            assertThat(entry.afterValue()).isEqualTo("{\"reason\":\"unknown_token\"}");
        });
    }

    @Test
    void aRevokedFamilyIsRejectedAsAValueAndNothingIsWritten() {
        families.state = new RefreshTokenFamilyState(familyId, ACCOUNT, false,
                Instant.parse("2026-10-12T09:15:00Z"), Instant.parse("2026-10-12T20:00:00Z"),
                Instant.parse("2026-10-12T09:10:00Z"));
        tokens.state = openToken();

        RefreshOutcome outcome = execute();

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.FAMILY_REVOKED));
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearOld",
                "audit:identity.session.refresh_rejected", "commit");
        assertThat(audited.get(0).afterValue()).isEqualTo("{\"reason\":\"family_revoked\"}");
    }

    @Test
    void anIdleFamilyIsRejectedAndTheEncryptedSuccessorsAreEmptied() {
        families.state = new RefreshTokenFamilyState(familyId, ACCOUNT, false,
                Instant.parse("2026-10-12T08:30:00Z"), Instant.parse("2026-10-12T20:00:00Z"), null);
        tokens.state = openToken();

        RefreshOutcome outcome = execute();

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.FAMILY_EXPIRED));
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearOld",
                "clearSuccessors", "audit:identity.session.refresh_rejected", "commit");
    }

    @Test
    void aTokenPastItsOwnExpiryInALiveFamilyIsRejectedWithoutChangingAnything() {
        families.state = liveFamily();
        tokens.state = new RefreshTokenState(tokenId, familyId,
                Instant.parse("2026-10-12T01:00:00Z"), Instant.parse("2026-10-12T09:00:00Z"), null,
                null, false, false);

        RefreshOutcome outcome = execute();

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.TOKEN_EXPIRED));
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearOld",
                "audit:identity.session.refresh_rejected", "commit");
    }

    @Test
    void aReuseRevokesThenEmptiesTheSuccessorsThenAuditsAndIsReturnedAsAValue() {
        families.state = liveFamily();
        tokens.state = consumedToken(UserAgentFingerprint.of(AGENT));

        RefreshOutcome outcome = executeWithAgent("agente-dos");

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearOld",
                "revokeForReuse", "clearSuccessors", "audit:identity.session.reuse_detected",
                "commit");
        assertThat(calls).doesNotContain("rollback", "consume", "insert", "recordUse");
        assertThat(audited).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo("denied");
            assertThat(entry.entityType()).isEqualTo("identity.refresh_token_family");
            assertThat(entry.entityId()).isEqualTo(familyId.toString());
            assertThat(entry.afterValue()).isEqualTo("{\"familyRevoked\":true}");
        });
        assertThat(families.revokedAt).isEqualTo(Instant.parse(NOW));
    }

    @Test
    void aFamilyThatCannotBeRevokedUnderItsOwnLockFailsBeforeAnythingIsAudited() {
        families.state = liveFamily();
        tokens.state = consumedToken(UserAgentFingerprint.of(AGENT));
        families.revokeResult = false;

        assertThatThrownBy(() -> executeWithAgent("agente-dos"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not be revoked");

        assertThat(calls).doesNotContain("clearSuccessors").endsWith("rollback");
        assertThat(audited).isEmpty();
    }

    @Test
    void anAuditFailureAfterTheRevocationPropagatesAndRollsEverythingBack() {
        families.state = liveFamily();
        tokens.state = consumedToken(UserAgentFingerprint.of(AGENT));
        auditFails = true;

        assertThatThrownBy(() -> executeWithAgent("agente-dos"))
                .isInstanceOf(IllegalStateException.class).hasMessage("the audit could not be written");

        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearOld",
                "revokeForReuse", "clearSuccessors", "audit:identity.session.reuse_detected",
                "rollback");
        assertThat(calls).doesNotContain("commit");
    }

    /** Scenarios I96, I141 and I103: the same successor, handed back decrypted, with nothing written but the use. */
    @Test
    void aRetryInsideTheWindowFromTheSameAgentReturnsTheStoredSuccessorAndWritesNoToken() {
        families.state = liveFamily();
        PlainRefreshToken successor = PlainRefreshToken.generate(new SecureRandom());
        tokens.state = consumedAt("2026-10-12T09:19:55Z", UserAgentFingerprint.of(AGENT), true);
        tokens.encryptedSuccessor = new EncryptedSuccessor(storedSuccessor(successor, tokenId),
                Instant.parse("2026-10-12T17:15:00Z"));
        calls.clear();

        RefreshOutcome outcome = execute();

        assertThat(outcome).isInstanceOf(Rotated.class);
        Rotated rotated = (Rotated) outcome;
        assertThat(rotated.kind()).isEqualTo(RotationKind.GRACE);
        assertThat(rotated.session().refreshToken()).isEqualTo(successor);
        assertThat(rotated.session().refreshExpiresAt())
                .as("the expiry of the successor as it was issued")
                .isEqualTo(Instant.parse("2026-10-12T17:15:00Z"));
        assertThat(rotated.session().sessionId()).isEqualTo(familyId);
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearOld",
                "findEncryptedSuccessor", "decrypt", "recordUse",
                "audit:identity.session.refreshed", "commit");
        assertThat(families.usedAt).as("the retry counts as a use of the family")
                .isEqualTo(Instant.parse(NOW));
        assertThat(audited).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo("success");
            assertThat(entry.entityId()).isEqualTo(familyId.toString());
            assertThat(entry.afterValue()).isEqualTo("{\"rotation\":\"grace\"}");
        });
    }

    @Test
    void theTenthSecondExactlyIsAReuseAndNothingIsEvenReadForDecryption() {
        families.state = liveFamily();
        tokens.state = consumedAt("2026-10-12T09:19:50Z", UserAgentFingerprint.of(AGENT), true);
        tokens.encryptedSuccessor = new EncryptedSuccessor("v1:never-read", Instant.parse(NOW));

        RefreshOutcome outcome = execute();

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(calls).doesNotContain("findEncryptedSuccessor", "decrypt");
        assertThat(calls).contains("revokeForReuse");
    }

    @Test
    void anotherAgentInsideTheWindowAndAnAdvancedFamilyAreReusesAndNothingIsDecrypted() {
        families.state = liveFamily();
        tokens.state = consumedAt("2026-10-12T09:19:55Z", UserAgentFingerprint.of(AGENT), true);
        tokens.encryptedSuccessor = new EncryptedSuccessor("v1:never-read", Instant.parse(NOW));

        RefreshOutcome anotherAgent = executeWithAgent("agente-dos");
        tokens.state = new RefreshTokenState(tokenId, familyId, Instant.parse("2026-10-12T08:00:00Z"),
                Instant.parse("2026-10-12T16:00:00Z"), Instant.parse("2026-10-12T09:19:55Z"),
                UserAgentFingerprint.of(AGENT), true, true);
        RefreshOutcome advanced = execute();

        assertThat(anotherAgent).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(advanced).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(calls).doesNotContain("findEncryptedSuccessor", "decrypt");
    }

    /** Scenario I99, in the use case: the ciphertext authenticates only against the row that holds it. */
    @Test
    void aSuccessorStoredForAnotherRowDoesNotAuthenticateAndNothingIsWritten() {
        families.state = liveFamily();
        PlainRefreshToken successor = PlainRefreshToken.generate(new SecureRandom());
        tokens.state = consumedAt("2026-10-12T09:19:55Z", UserAgentFingerprint.of(AGENT), true);
        tokens.encryptedSuccessor = new EncryptedSuccessor(
                storedSuccessor(successor, UUID.randomUUID()), Instant.parse("2026-10-12T17:15:00Z"));
        calls.clear();

        assertThatThrownBy(this::execute).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be authenticated")
                .hasMessageNotContaining(successor.value())
                .hasCauseInstanceOf(AeadIntegrityException.class);

        assertThat(calls).doesNotContain("recordUse").endsWith("rollback");
        assertThat(audited).isEmpty();
    }

    @Test
    void aSuccessorTheTokenSaysItHasButTheStoreCannotFindIsAFailureAndRollsBack() {
        families.state = liveFamily();
        tokens.state = consumedAt("2026-10-12T09:19:55Z", UserAgentFingerprint.of(AGENT), true);
        tokens.encryptedSuccessor = null;

        assertThatThrownBy(this::execute).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("encrypted successor");

        assertThat(calls).endsWith("rollback").doesNotContain("recordUse", "decrypt");
    }

    /** Scenario I99 and the row-bound AAD: what is stored decrypts for the predecessor and for no other row. */
    @Test
    void aRotationStoresTheSuccessorEncryptedAndBoundToTheRowOfTheConsumedToken() throws Exception {
        families.state = liveFamily();
        tokens.state = openToken();

        Rotated rotated = (Rotated) execute();

        assertThat(tokens.consumedCiphertext).startsWith("v1:");
        assertThat(tokens.consumedCiphertext).doesNotContain(rotated.session().refreshToken().value());
        assertThat(tokens.consumedTokenId).isEqualTo(tokenId);
        byte[] plain = encryption.decrypt("identity_refresh_token", "successor_token_ciphertext",
                INSTITUTION, tokenId.toString(), tokens.consumedCiphertext);
        String text = new String(plain, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo(rotated.session().refreshToken().value());
        assertThat(RefreshTokenHash.of(PlainRefreshToken.of(text)))
                .as("the stored hash is that of the very same successor")
                .isEqualTo(tokens.insertedHash);
        assertThatThrownBy(() -> encryption.decrypt("identity_refresh_token",
                "successor_token_ciphertext", INSTITUTION, UUID.randomUUID().toString(),
                tokens.consumedCiphertext)).isInstanceOf(AeadIntegrityException.class);
    }

    /** Decision 10, step 5: before it decides, every contact empties what was consumed ten seconds ago or more. */
    @Test
    void everyContactEmptiesTheSuccessorsConsumedTenSecondsAgoOrMoreBeforeItDecides() {
        families.state = liveFamily();
        tokens.state = openToken();

        execute();

        assertThat(tokens.clearedFamily).isEqualTo(familyId);
        assertThat(tokens.clearedCutoff).isEqualTo(Instant.parse("2026-10-12T09:19:50Z"));
    }

    @Test
    void anInstitutionThatIsNotTheConfiguredOneIsAFailureAndNeverReachesAPort() {
        InstitutionId other = new InstitutionId(UUID.randomUUID());
        RotateRefreshToken useCase = useCase(() -> other);

        assertThatThrownBy(() -> useCase.execute(context(),
                new RotateRefreshTokenCommand(PlainRefreshToken.generate(new SecureRandom()).value(),
                        AGENT))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("institution mismatch");

        assertThat(calls).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------

    private RefreshOutcome execute() {
        return executeWith(PlainRefreshToken.generate(new SecureRandom()).value());
    }

    private RefreshOutcome executeWith(String presented) {
        return useCase(() -> INSTITUTION).execute(context(),
                new RotateRefreshTokenCommand(presented, AGENT));
    }

    private RefreshOutcome executeWithAgent(String agent) {
        return useCase(() -> INSTITUTION).execute(context(), new RotateRefreshTokenCommand(
                PlainRefreshToken.generate(new SecureRandom()).value(), agent));
    }

    private RotateRefreshToken useCase(LoginInstitutionProvider provider) {
        Clock clock = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC);
        AuditLogWriter writer = entry -> {
            calls.add("audit:" + entry.action());
            if (auditFails) {
                throw new IllegalStateException("the audit could not be written");
            }
            audited.add(entry);
        };
        return new RotateRefreshToken(JdbcStubTransactions.runner(calls), provider, families, tokens, encryption,
                new AccessTokenIssuer(new CompactJws(SigningKeyRing.of(
                        SigningKey.signing("admin-2026a", KEYS.getPublic(), KEYS.getPrivate()))),
                        clock), writer, clock, new SecureRandom());
    }

    private static SecurityContext context() {
        return new SecurityContext(ACCOUNT.value().toString(), "staff", INSTITUTION.value().toString(),
                UUID.randomUUID().toString());
    }

    private RefreshTokenFamilyState liveFamily() {
        return new RefreshTokenFamilyState(familyId, ACCOUNT, false,
                Instant.parse("2026-10-12T09:15:00Z"), Instant.parse("2026-10-12T20:00:00Z"), null);
    }

    private RefreshTokenState openToken() {
        return new RefreshTokenState(tokenId, familyId, Instant.parse("2026-10-12T08:00:00Z"),
                Instant.parse("2026-10-12T16:00:00Z"), null, null, false, false);
    }

    private RefreshTokenState consumedAt(String at, UserAgentFingerprint by, boolean ciphertext) {
        return new RefreshTokenState(tokenId, familyId, Instant.parse("2026-10-12T08:00:00Z"),
                Instant.parse("2026-10-12T16:00:00Z"), Instant.parse(at), by, ciphertext, false);
    }

    /** What a rotation would have stored in the row {@code rowId}: the successor, encrypted and bound to it. */
    private String storedSuccessor(PlainRefreshToken successor, UUID rowId) {
        return encryption.encryptForNewValue("identity_refresh_token", "successor_token_ciphertext",
                INSTITUTION, rowId.toString(), successor.value().getBytes(StandardCharsets.UTF_8));
    }

    /** Consumed at 09:00, twenty minutes before {@link #NOW}: far outside the ten seconds of grace. */
    private RefreshTokenState consumedToken(UserAgentFingerprint by) {
        return new RefreshTokenState(tokenId, familyId, Instant.parse("2026-10-12T08:00:00Z"),
                Instant.parse("2026-10-12T16:00:00Z"), Instant.parse("2026-10-12T09:00:00Z"), by,
                false, false);
    }

    private static KeyPair generatedPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class FakeFamilies implements RefreshTokenFamilyRepository {

        private final List<String> calls;
        RefreshTokenFamilyState state;
        boolean revokeResult = true;
        Instant revokedAt;
        Instant usedAt;

        FakeFamilies(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void insert(InstitutionId institutionId, RefreshTokenFamily family) {
            calls.add("insertFamily");
        }

        @Override
        public Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId,
                UUID familyId) {
            calls.add("lock");
            return Optional.ofNullable(state);
        }

        @Override
        public Optional<RefreshTokenFamilyState> readById(InstitutionId institutionId,
                UUID familyId) {
            throw new UnsupportedOperationException("the rotation locks its family, it never peeks");
        }

        @Override
        public void recordUse(InstitutionId institutionId, UUID familyId, Instant now) {
            calls.add("recordUse");
            usedAt = now;
        }

        @Override
        public boolean revokeForReuse(InstitutionId institutionId, UUID familyId, Instant now) {
            calls.add("revokeForReuse");
            revokedAt = now;
            return revokeResult;
        }
    }

    private static final class FakeTokens implements RefreshTokenRepository {

        private final List<String> calls;
        RefreshTokenState state;
        EncryptedSuccessor encryptedSuccessor;
        boolean consumeResult = true;
        String consumedCiphertext;
        UUID consumedTokenId;
        RefreshTokenHash insertedHash;
        UUID clearedFamily;
        Instant clearedCutoff;

        FakeTokens(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void insert(InstitutionId institutionId, RefreshTokenRow row) {
            calls.add("insert");
            insertedHash = row.hash();
        }

        @Override
        public Optional<RefreshTokenState> findByHash(InstitutionId institutionId,
                RefreshTokenHash hash) {
            calls.add("findByHash");
            return Optional.ofNullable(state);
        }

        @Override
        public Optional<RefreshTokenState> findById(InstitutionId institutionId, UUID tokenId) {
            calls.add("findById");
            return Optional.ofNullable(state);
        }

        @Override
        public boolean consume(InstitutionId institutionId, UUID tokenId, Instant now,
                UserAgentFingerprint presenter, UUID successorId, String successorCiphertext) {
            calls.add("consume");
            consumedTokenId = tokenId;
            consumedCiphertext = successorCiphertext;
            return consumeResult;
        }

        @Override
        public Optional<EncryptedSuccessor> findEncryptedSuccessor(InstitutionId institutionId,
                UUID tokenId) {
            calls.add("findEncryptedSuccessor");
            return Optional.ofNullable(encryptedSuccessor);
        }

        @Override
        public void clearSuccessorsConsumedAtOrBefore(InstitutionId institutionId, UUID familyId,
                Instant cutoff) {
            calls.add("clearOld");
            clearedFamily = familyId;
            clearedCutoff = cutoff;
        }

        @Override
        public void clearSuccessors(InstitutionId institutionId, UUID familyId) {
            calls.add("clearSuccessors");
        }
    }

    /** One key, in memory; it writes {@code encrypt} and {@code decrypt} where the tests can see them. */
    private static final class RecordingKeys implements DataEncryptionKeyRepository {

        private final List<String> calls;
        private final DataEncryptionKeyMaterial key;

        RecordingKeys(List<String> calls) {
            this.calls = calls;
            byte[] raw = new byte[DataEncryptionKeyMaterial.LENGTH_BYTES];
            new SecureRandom().nextBytes(raw);
            this.key = new DataEncryptionKeyMaterial(new DataEncryptionKeyId(UUID.randomUUID()), raw);
        }

        @Override
        public DataEncryptionKeyMaterial findActiveOrCreate(InstitutionId institutionId) {
            calls.add("encrypt");
            return key;
        }

        @Override
        public DataEncryptionKeyMaterial findById(InstitutionId institutionId,
                DataEncryptionKeyId id) {
            calls.add("decrypt");
            return key;
        }
    }
}

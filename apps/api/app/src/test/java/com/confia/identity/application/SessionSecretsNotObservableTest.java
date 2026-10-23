package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
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
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.domain.UserAgentFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.testsupport.fixture.LeakingRefreshTokenFixture;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.security.SecondFactorPurpose;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.crypto.DataEncryptionKeyId;
import com.confia.shared.crypto.DataEncryptionKeyMaterial;
import com.confia.shared.crypto.DataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Specs/identity/spec.md, requirement "Ningún secreto de sesión es observable" (scenarios I85 and
 * I86). A real issuance, a fresh rotation, a retry inside the grace window, a failure to authenticate a
 * stored successor and a confirmed reuse run for {@code maria.lopez@colegio.edu.hn} with every log
 * line captured. Everything that was produced as text is swept: the log lines (and the traces they
 * carry), the message and trace of every exception, the {@code toString()} of every object that holds
 * a secret, and the audit entries. None may hold a token (access or refresh), the successor in clear
 * or encrypted, any hash of it, a signing key or the data-encryption key.
 *
 * <p><b>The sweep never puts what it searched into its own failure message</b>: on a leak it reports
 * the label and the length of the secret only, the same discipline as {@code IdentitySecretRedactionIT}.
 * The negative control (I86) feeds the text of a variant whose object exposes the refresh token in its
 * {@code toString()} to the same sweep and demands that it fails.
 *
 * <p>A unit test: the store is in memory and the transaction is the real runner over a JDBC stub, so
 * what is swept is the behavior of the use cases and of the value objects, not of PostgreSQL.
 */
class SessionSecretsNotObservableTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final StaffAccountId ACCOUNT = new StaffAccountId(UUID.randomUUID());
    private static final String AGENT = "agente-uno";
    private static final String PRESENTED_TOTP = "739201";
    private static final String PRESENTED_RECOVERY_CODE = "QWERTYUIOP";
    private static final String OTHER_AGENT = "agente-dos";
    private static final String START = "2026-10-12T09:00:00Z";
    private static final String ROTATED_AT = "2026-10-12T09:15:00Z";
    private static final KeyPair KEYS = generatedPair();
    private static final byte[] DEK = new SecureRandom().generateSeed(32);

    private final List<String> transactionCalls = new ArrayList<>();
    private final List<AuditEntry> audited = new ArrayList<>();
    private final InMemorySessions store = new InMemorySessions();
    private final ColumnEncryptionService encryption = new ColumnEncryptionService(
            new FixedKey(), new AesGcmCipher());

    @Test
    void noLogNorExceptionNorToStringNorAuditEntryExposesATokenAKeyTheSuccessorOrAHash()
            throws Exception {
        List<String> produced = new ArrayList<>();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.start();
        root.addAppender(logs);
        Map<String, String> secrets = new LinkedHashMap<>();
        try {
            IssuedSession first = issue(START);
            Rotated fresh = (Rotated) rotate(ROTATED_AT, first.refreshToken(), AGENT);
            UUID consumedId = store.consumedTokenOf(first.sessionId());
            secrets.put("the stored encrypted successor", store.ciphertextOf(consumedId));
            EncryptedSuccessor stored = store.encryptedSuccessorOf(consumedId).orElseThrow();
            RefreshTokenState consumedState = store.findById(INSTITUTION, consumedId).orElseThrow();

            Rotated grace = (Rotated) rotate("2026-10-12T09:15:05Z", first.refreshToken(), AGENT);

            IssuedSession other = issue(START);
            rotate(ROTATED_AT, other.refreshToken(), AGENT);
            store.setCiphertext(store.consumedTokenOf(other.sessionId()), store.ciphertextOf(consumedId));
            Throwable unauthenticated = failureOf(() -> rotate("2026-10-12T09:15:05Z",
                    other.refreshToken(), AGENT));
            Rejected reuse = (Rejected) rotate("2026-10-12T09:15:06Z", first.refreshToken(),
                    OTHER_AGENT);

            assertThat(fresh.kind()).isEqualTo(RotationKind.FRESH);
            assertThat(grace.kind()).isEqualTo(RotationKind.GRACE);
            assertThat(reuse.reason()).isEqualTo(RefreshRejection.REUSE_DETECTED);
            assertThat(unauthenticated).hasMessageContaining("could not be authenticated");

            secrets.put("the first refresh token", first.refreshToken().value());
            secrets.put("the successor refresh token", fresh.session().refreshToken().value());
            secrets.put("the hash of the first refresh token",
                    RefreshTokenHash.of(first.refreshToken()).value());
            secrets.put("the hash of the successor", RefreshTokenHash.of(
                    fresh.session().refreshToken()).value());
            secrets.put("the first access token", first.accessToken().compact());
            secrets.put("the access token of the rotation", fresh.session().accessToken().compact());
            secrets.put("the access token of the retry", grace.session().accessToken().compact());
            secrets.put("the signing key (base64)", Base64.getEncoder().encodeToString(
                    KEYS.getPrivate().getEncoded()));
            secrets.put("the signing key (hex)", HexFormat.of().formatHex(
                    KEYS.getPrivate().getEncoded()));
            secrets.put("the data-encryption key (base64)", Base64.getEncoder().encodeToString(DEK));
            secrets.put("the data-encryption key (hex)", HexFormat.of().formatHex(DEK));
            secrets.put("the fingerprint of the agent", UserAgentFingerprint.of(AGENT).value());
            secrets.put("the TOTP code presented to the second factor", PRESENTED_TOTP);
            secrets.put("the recovery code presented to the second factor", PRESENTED_RECOVERY_CODE);
            PlainTotpSecret enrolledSecret = PlainTotpSecret.generate(new SecureRandom());
            PlainRecoveryCode enrolledCode = PlainRecoveryCode.generate(new SecureRandom());
            secrets.put("the TOTP secret of an enrollment (base32)", enrolledSecret.base32());
            secrets.put("a recovery code of an enrollment", enrolledCode.value());

            produced.addAll(textOf(unauthenticated));
            for (Object holder : List.<Object>of(first, fresh, grace, reuse, first.refreshToken(),
                    first.accessToken(), fresh.session(), grace.session(),
                    new RotateRefreshTokenCommand(first.refreshToken().value(), AGENT),
                    new RotateRefreshTokenCommand(first.refreshToken().value(), null),
                    stored, consumedState,
                    new RefreshTokenRow(UUID.randomUUID(), first.sessionId(),
                            RefreshTokenHash.of(first.refreshToken()), Instant.parse(START),
                            Instant.parse(ROTATED_AT)),
                    UserAgentFingerprint.of(AGENT), new SecurityContext("", "system",
                            INSTITUTION.value().toString(), UUID.randomUUID().toString()),
                    new CompleteSecondFactorCommand(ACCOUNT.value(), INSTITUTION.value(),
                            SecondFactorPurpose.VERIFY, PRESENTED_TOTP, null, null, AGENT, "req"),
                    new CompleteSecondFactorCommand(ACCOUNT.value(), INSTITUTION.value(),
                            SecondFactorPurpose.VERIFY, null, PRESENTED_RECOVERY_CODE, null, AGENT,
                            "req"),
                    new SecondFactorOutcome.Started(new SessionGrant(
                            first.accessToken().compact(), Instant.parse(ROTATED_AT),
                            first.refreshToken().value(), Instant.parse(ROTATED_AT),
                            Instant.parse(ROTATED_AT))),
                    new EnrollTotpSecondFactorResult.Enrolled(enrolledSecret,
                            List.of(enrolledCode), false))) {
                produced.add(String.valueOf(holder));
            }
            for (AuditEntry entry : audited) {
                produced.add(entry.toString());
                produced.add(String.valueOf(entry.afterValue()));
                produced.add(String.valueOf(entry.entityId()));
            }
            assertThat(audited).as("the sweep did look at the entries of every operation")
                    .hasSizeGreaterThanOrEqualTo(6);
        } finally {
            root.detachAppender(logs);
        }
        for (ILoggingEvent event : logs.list) {
            produced.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                produced.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
        }

        assertNothingLeaks(String.join("\n", produced), secrets);

        // The control (I86): the same sweep, fed the text of an object that exposes the token.
        LeakingRefreshTokenFixture leaky = new LeakingRefreshTokenFixture(
                secrets.get("the first refresh token"));
        assertThatThrownBy(() -> assertNothingLeaks(String.join("\n", produced) + "\n" + leaky,
                secrets)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("the first refresh token")
                .hasMessageNotContaining(secrets.get("the first refresh token"));
    }

    /** The sweep demands what it is meant to find: a secret in the text fails it, naming only its label. */
    @Test
    void theSweepFailsOnALeakAndNeverRepeatsTheValueInItsMessage() {
        Map<String, String> secrets = Map.of("a secret", "Zm9vYmFyLWRvLW5vdC1wcmludA");

        assertThatThrownBy(() -> assertNothingLeaks("before Zm9vYmFyLWRvLW5vdC1wcmludA after", secrets))
                .isInstanceOf(AssertionError.class).hasMessageContaining("a secret")
                .hasMessageNotContaining("Zm9vYmFyLWRvLW5vdC1wcmludA");
        assertNothingLeaks("nothing of the kind here", secrets);
    }

    // ---------------------------------------------------------------------------------------------

    private IssuedSession issue(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new IssueSession(JdbcStubTransactions.runner(transactionCalls), store, store, store,
                new AccessTokenIssuer(jws(), clock), audited::add, clock, new SecureRandom())
                .execute(context(), new IssueSessionCommand(ACCOUNT, false, AGENT, null));
    }

    private RefreshOutcome rotate(String instant, PlainRefreshToken token, String agent) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new RotateRefreshToken(JdbcStubTransactions.runner(transactionCalls),
                () -> INSTITUTION, store, store, encryption, new AccessTokenIssuer(jws(), clock),
                audited::add, clock, new SecureRandom())
                .execute(context(), new RotateRefreshTokenCommand(token.value(), agent));
    }

    private static CompactJws jws() {
        return new CompactJws(SigningKeyRing.of(
                SigningKey.signing("admin-2026a", KEYS.getPublic(), KEYS.getPrivate())));
    }

    private static SecurityContext context() {
        return new SecurityContext(ACCOUNT.value().toString(), "staff", INSTITUTION.value().toString(),
                UUID.randomUUID().toString());
    }

    private static Throwable failureOf(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e;
        }
        return fail("the operation was expected to fail");
    }

    /** The message and the stack trace of an exception and of every cause. */
    private static List<String> textOf(Throwable failure) {
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return List.of(String.valueOf(failure.getMessage()), trace.toString());
    }

    /**
     * The failure message carries the label and the length of the secret and never its value or the
     * text it was searched in: the discipline this class exists to demonstrate applies to its own
     * assertion too.
     */
    private static void assertNothingLeaks(String haystack, Map<String, String> secrets) {
        secrets.forEach((label, value) -> {
            if (haystack.contains(value)) {
                fail("produced text unexpectedly contains %s (%d characters); the value itself is "
                        + "deliberately withheld from this failure message", label, value.length());
            }
        });
    }

    private static KeyPair generatedPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One data-encryption key, in memory. */
    private static final class FixedKey implements DataEncryptionKeyRepository {

        private final DataEncryptionKeyMaterial key = new DataEncryptionKeyMaterial(
                new DataEncryptionKeyId(UUID.randomUUID()), DEK);

        @Override
        public DataEncryptionKeyMaterial findActiveOrCreate(InstitutionId institutionId) {
            return key;
        }

        @Override
        public DataEncryptionKeyMaterial findById(InstitutionId institutionId,
                DataEncryptionKeyId id) {
            return key;
        }
    }

    /**
     * Families, tokens and the one account, in memory, with the semantics of the real adapters that
     * the use cases rely on: a consumption is final, a revocation is final, and the encrypted
     * successor is emptied by family or by age.
     */
    private static final class InMemorySessions implements RefreshTokenFamilyRepository,
            RefreshTokenRepository, StaffAccountRepository {

        private static final class Family {
            RefreshTokenFamily row;
            Instant lastUsedAt;
            Instant revokedAt;
        }

        private static final class Token {
            RefreshTokenRow row;
            Instant consumedAt;
            UserAgentFingerprint consumedBy;
            UUID replacedBy;
            String ciphertext;
        }

        private final Map<UUID, Family> families = new LinkedHashMap<>();
        private final Map<UUID, Token> tokens = new LinkedHashMap<>();

        @Override
        public Optional<StaffAccount> lockById(InstitutionId institutionId, StaffAccountId id) {
            return Optional.of(new StaffAccount(id, institutionId,
                    LoginIdentifier.of("maria.lopez@colegio.edu.hn"),
                    new StoredPasswordHash("$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aW5pY2lhbA"), false));
        }

        @Override
        public Optional<StaffAccount> findBy(InstitutionId institutionId, LoginIdentifier identifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean replacePasswordHash(InstitutionId institutionId, StaffAccountId accountId,
                StoredPasswordHash newHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insert(InstitutionId institutionId, RefreshTokenFamily family) {
            Family stored = new Family();
            stored.row = family;
            stored.lastUsedAt = family.lastUsedAt();
            families.put(family.id(), stored);
        }

        @Override
        public Optional<RefreshTokenFamilyState> lockById(InstitutionId institutionId, UUID familyId) {
            return Optional.ofNullable(families.get(familyId)).map(family ->
                    new RefreshTokenFamilyState(familyId, family.row.accountId(),
                            family.row.secondFactorVerified(), family.lastUsedAt,
                            family.row.absoluteExpiresAt(), family.revokedAt));
        }

        @Override
        public Optional<RefreshTokenFamilyState> readById(InstitutionId institutionId,
                UUID familyId) {
            throw new UnsupportedOperationException("the rotation locks its family, it never peeks");
        }

        @Override
        public void recordUse(InstitutionId institutionId, UUID familyId, Instant now) {
            families.get(familyId).lastUsedAt = now;
        }

        @Override
        public boolean revokeForReuse(InstitutionId institutionId, UUID familyId, Instant now) {
            Family family = families.get(familyId);
            if (family.revokedAt != null) {
                return false;
            }
            family.revokedAt = now;
            return true;
        }

        @Override
        public boolean revoke(InstitutionId institutionId, UUID familyId,
                SessionRevocationReason reason, Instant now) {
            return revokeForReuse(institutionId, familyId, now);
        }

        @Override
        public void insert(InstitutionId institutionId, RefreshTokenRow row) {
            Token token = new Token();
            token.row = row;
            tokens.put(row.id(), token);
        }

        @Override
        public Optional<RefreshTokenState> findByHash(InstitutionId institutionId,
                RefreshTokenHash hash) {
            return tokens.values().stream().filter(token -> token.row.hash().equals(hash)).findFirst()
                    .map(this::stateOf);
        }

        @Override
        public Optional<RefreshTokenState> findById(InstitutionId institutionId, UUID tokenId) {
            return Optional.ofNullable(tokens.get(tokenId)).map(this::stateOf);
        }

        @Override
        public boolean consume(InstitutionId institutionId, UUID tokenId, Instant now,
                UserAgentFingerprint presenter, UUID successorId, String successorCiphertext) {
            Token token = tokens.get(tokenId);
            if (token.consumedAt != null) {
                return false;
            }
            token.consumedAt = now;
            token.consumedBy = presenter;
            token.replacedBy = successorId;
            token.ciphertext = successorCiphertext;
            return true;
        }

        @Override
        public Optional<EncryptedSuccessor> findEncryptedSuccessor(InstitutionId institutionId,
                UUID tokenId) {
            return encryptedSuccessorOf(tokenId);
        }

        Optional<EncryptedSuccessor> encryptedSuccessorOf(UUID tokenId) {
            Token token = tokens.get(tokenId);
            if (token == null || token.ciphertext == null) {
                return Optional.empty();
            }
            return Optional.of(new EncryptedSuccessor(token.ciphertext,
                    tokens.get(token.replacedBy).row.expiresAt()));
        }

        @Override
        public void clearSuccessorsConsumedAtOrBefore(InstitutionId institutionId, UUID familyId,
                Instant cutoff) {
            tokens.values().stream().filter(token -> token.row.familyId().equals(familyId))
                    .filter(token -> token.consumedAt != null && !token.consumedAt.isAfter(cutoff))
                    .forEach(token -> token.ciphertext = null);
        }

        @Override
        public void clearSuccessors(InstitutionId institutionId, UUID familyId) {
            tokens.values().stream().filter(token -> token.row.familyId().equals(familyId))
                    .forEach(token -> token.ciphertext = null);
        }

        UUID consumedTokenOf(UUID familyId) {
            return tokens.values().stream().filter(token -> token.row.familyId().equals(familyId))
                    .filter(token -> token.consumedAt != null).map(token -> token.row.id())
                    .findFirst().orElseThrow();
        }

        String ciphertextOf(UUID tokenId) {
            return tokens.get(tokenId).ciphertext;
        }

        void setCiphertext(UUID tokenId, String ciphertext) {
            tokens.get(tokenId).ciphertext = ciphertext;
        }

        private RefreshTokenState stateOf(Token token) {
            boolean successorConsumed = token.replacedBy != null
                    && tokens.get(token.replacedBy).consumedAt != null;
            return new RefreshTokenState(token.row.id(), token.row.familyId(), token.row.issuedAt(),
                    token.row.expiresAt(), token.consumedAt, token.consumedBy,
                    token.ciphertext != null, successorConsumed);
        }
    }
}

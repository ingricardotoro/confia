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
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * The orchestration of {@link RotateRefreshToken} on its own, with in-memory doubles for every port
 * (testing playbook, section 1): which port calls each {@code RotationDecision} leads to, in which
 * order, and whether the outcome is a returned value or an exception. That a row really changes, and
 * what the lock does, is the job of {@code RotateRefreshTokenIT} and {@code RefreshRotationConcurrencyIT}.
 *
 * <p>The {@link TransactionRunner} is the real one over a JDBC stub that does nothing but remember
 * {@code commit} and {@code rollback}: that is how the tests tell a rejection that is committed (a
 * returned value) from a failure that is rolled back. Every double writes into one shared list, so the
 * order of calls across ports is observable.
 *
 * <p>These tests were written against the code of 4.1 and 4.2, which already existed, so they started
 * green; the red is the deliberate break recorded in apply-progress.md.
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
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "consume", "insert",
                "recordUse", "audit:identity.session.refreshed", "commit");
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
        assertThat(calls).doesNotContain("lock");
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
        assertThat(calls).containsExactly("findByHash", "lock", "findById",
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
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "clearSuccessors",
                "audit:identity.session.refresh_rejected", "commit");
    }

    @Test
    void aTokenPastItsOwnExpiryInALiveFamilyIsRejectedWithoutChangingAnything() {
        families.state = liveFamily();
        tokens.state = new RefreshTokenState(tokenId, familyId,
                Instant.parse("2026-10-12T01:00:00Z"), Instant.parse("2026-10-12T09:00:00Z"), null,
                null, false, false);

        RefreshOutcome outcome = execute();

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.TOKEN_EXPIRED));
        assertThat(calls).containsExactly("findByHash", "lock", "findById",
                "audit:identity.session.refresh_rejected", "commit");
    }

    @Test
    void aReuseRevokesThenEmptiesTheSuccessorsThenAuditsAndIsReturnedAsAValue() {
        families.state = liveFamily();
        tokens.state = consumedToken(UserAgentFingerprint.of(AGENT));

        RefreshOutcome outcome = executeWithAgent("agente-dos");

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "revokeForReuse",
                "clearSuccessors", "audit:identity.session.reuse_detected", "commit");
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

        assertThat(calls).containsExactly("findByHash", "lock", "findById", "revokeForReuse",
                "clearSuccessors", "audit:identity.session.reuse_detected", "rollback");
        assertThat(calls).doesNotContain("commit");
    }

    /** Until task 4.3 the grace branch has no effect of its own: it is a rejection and nothing is written. */
    @Test
    void theGraceBranchStillWritesNothingUntilTheChangeThatDeliversIt() {
        families.state = liveFamily();
        tokens.state = new RefreshTokenState(tokenId, familyId,
                Instant.parse("2026-10-12T08:00:00Z"), Instant.parse("2026-10-12T16:00:00Z"),
                Instant.parse("2026-10-12T09:19:55Z"), UserAgentFingerprint.of(AGENT), true, false);

        RefreshOutcome outcome = execute();

        assertThat(outcome).isEqualTo(new Rejected(RefreshRejection.REUSE_DETECTED));
        assertThat(calls).containsExactly("findByHash", "lock", "findById", "commit");
        assertThat(audited).isEmpty();
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
        return new RotateRefreshToken(runner(), provider, families, tokens,
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

    /** Consumed at 09:00, twenty minutes before {@link #NOW}: far outside the ten seconds of grace. */
    private RefreshTokenState consumedToken(UserAgentFingerprint by) {
        return new RefreshTokenState(tokenId, familyId, Instant.parse("2026-10-12T08:00:00Z"),
                Instant.parse("2026-10-12T16:00:00Z"), Instant.parse("2026-10-12T09:00:00Z"), by,
                false, false);
    }

    /**
     * The real runner over a JDBC stub: every call does nothing and returns the default of its type,
     * except that {@code commit} and {@code rollback} are written to the shared list.
     */
    private TransactionRunner runner() {
        PreparedStatement statement = stub(PreparedStatement.class, (proxy, method, args) -> null);
        Connection connection = stub(Connection.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "commit", "rollback" -> calls.add(method.getName());
                case "prepareStatement" -> {
                    return statement;
                }
                default -> {
                }
            }
            return null;
        });
        DataSource dataSource = stub(DataSource.class, (proxy, method, args) ->
                method.getName().equals("getConnection") ? connection : null);
        return new TransactionRunner(new DataSourceTransactionManager(dataSource), dataSource);
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        InvocationHandler withDefaults = (proxy, method, args) -> {
            Object result = handler.invoke(proxy, method, args);
            if (result != null || !method.getReturnType().isPrimitive()) {
                return result;
            }
            Class<?> primitive = method.getReturnType();
            if (primitive == boolean.class) {
                return false;
            }
            if (primitive == void.class) {
                return null;
            }
            return primitive == long.class ? 0L : primitive == int.class ? 0 : (Object) 0;
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, withDefaults);
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
        public void recordUse(InstitutionId institutionId, UUID familyId, Instant now) {
            calls.add("recordUse");
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
        boolean consumeResult = true;

        FakeTokens(List<String> calls) {
            this.calls = calls;
        }

        @Override
        public void insert(InstitutionId institutionId, RefreshTokenRow row) {
            calls.add("insert");
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
                UserAgentFingerprint presenter, UUID successorId) {
            calls.add("consume");
            return consumeResult;
        }

        @Override
        public void clearSuccessors(InstitutionId institutionId, UUID familyId) {
            calls.add("clearSuccessors");
        }
    }
}

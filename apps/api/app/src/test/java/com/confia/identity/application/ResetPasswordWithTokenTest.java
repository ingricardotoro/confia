package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.ResetOutcome.Completed;
import com.confia.identity.application.ResetOutcome.PasswordRejected;
import com.confia.identity.application.ResetOutcome.SecondFactorMissing;
import com.confia.identity.application.ResetOutcome.SecondFactorRejected;
import com.confia.identity.application.ResetOutcome.TokenRejected;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PasswordResetRejectionReason;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenPolicy;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import com.confia.identity.domain.TotpCode;
import com.confia.identity.domain.TotpCredential;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * {@link ResetPasswordWithToken} with test doubles (password-recovery-token design.md decision 7):
 * the order of the steps, which decides what each rejection costs. A made-up token costs a SHA-256
 * and one lookup, never a lock or Argon2id; a password of the wrong length never locks the account
 * or spends a second-factor attempt; and a second factor presented to an account without active MFA
 * is ignored. The real transaction is {@code ResetPasswordWithTokenIT}'s.
 */
class ResetPasswordWithTokenTest {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-05-14T10:20:00Z");
    private static final String TOKEN = "q9Zb2X-kP3r_Tm7Wv1cH8eN4sLd0fGyJ5uAiO6pRtQE";
    private static final String NEW_PASSWORD = "Lempira y maíz 2026";

    private final FakeTokens tokens = new FakeTokens();
    private final FakeAccounts accounts = new FakeAccounts();
    private final FakeTotpCredentials totpCredentials = new FakeTotpCredentials();
    private final CountingHasher hasher = new CountingHasher();
    private final List<AuditEntry> audit = new ArrayList<>();

    @Test
    void anUnknownTokenNeverLocksTheAccountNorComputesArgon2id() {
        ResetPasswordDecision decision = useCase().runWithinTransaction(INSTITUTION, "",
                command(TOKEN, NEW_PASSWORD, new SecondFactorProof.None()));

        assertThat(decision.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(accounts.locks).isZero();
        assertThat(hasher.hashes).isZero();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("identity.password_reset.rejected");
            assertThat(entry.afterValue()).isEqualTo("{\"reason\":\"token-not-found\"}");
        });
    }

    @Test
    void aMalformedTokenIsRejectedLikeAnUnknownOneWithoutEchoingIt() {
        ResetPasswordDecision decision = useCase().runWithinTransaction(INSTITUTION, "",
                command("PRT-not-a-token", NEW_PASSWORD, new SecondFactorProof.None()));

        assertThat(decision.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(accounts.locks).isZero();
        assertThat(audit).singleElement()
                .satisfies(entry -> assertThat(entry.toString()).doesNotContain("PRT-not-a-token"));
    }

    @Test
    void aPasswordOfTheWrongLengthNeitherLocksNorSpendsASecondFactorAttempt() {
        StaffAccountId account = seedLiveTokenFor(true);
        totpCredentials.enrolled = true;

        ResetPasswordDecision decision = useCase().runWithinTransaction(INSTITUTION, "",
                command(TOKEN, "a".repeat(11), new SecondFactorProof.Totp(new TotpCode("123456"))));

        assertThat(decision.outcome()).isEqualTo(
                new PasswordRejected(PasswordResetRejectionReason.PASSWORD_TOO_SHORT));
        assertThat(accounts.locks).isZero();
        assertThat(totpCredentials.lookups)
                .as("the second-factor check must not even run").isZero();
        assertThat(hasher.hashes).isZero();
        assertThat(tokens.consumed).isEmpty();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.entityId()).isEqualTo(account.value().toString());
            assertThat(entry.afterValue()).isEqualTo("{\"reason\":\"password-too-short\"}");
        });
    }

    @Test
    void aSecondFactorPresentedToAnAccountWithoutActiveMfaIsIgnored() {
        seedLiveTokenFor(false);
        totpCredentials.enrolled = true;

        ResetPasswordDecision decision = useCase().runWithinTransaction(INSTITUTION, "",
                command(TOKEN, NEW_PASSWORD, new SecondFactorProof.RecoveryCode(
                        PlainRecoveryCode.of("A7K3M9QZC2"))));

        assertThat(decision.outcome()).isInstanceOf(Completed.class);
        assertThat(hasher.hashes).isEqualTo(1);
        assertThat(accounts.replacedWith).isNotNull();
        assertThat(audit).extracting(AuditEntry::action)
                .containsExactly("identity.password_reset.completed");
        assertThat(audit.get(0).afterValue()).isEqualTo("{\"secondFactor\":\"none\"}");
    }

    @Test
    void aLostConsumptionRaceIsATokenRejectionAndPaysNoArgon2id() {
        seedLiveTokenFor(false);
        tokens.consumeWins = false;

        ResetPasswordDecision decision = useCase().runWithinTransaction(INSTITUTION, "",
                command(TOKEN, NEW_PASSWORD, new SecondFactorProof.None()));

        assertThat(decision.outcome()).isInstanceOf(TokenRejected.class);
        assertThat(hasher.hashes).isZero();
        assertThat(accounts.replacedWith).isNull();
        assertThat(audit).singleElement().extracting(AuditEntry::afterValue)
                .isEqualTo("{\"reason\":\"token-consumed\"}");
    }

    /** A consumer of {@link ResetOutcome} is an exhaustive switch with no {@code default}. */
    @Test
    void everyOutcomeIsHandledByAnExhaustiveSwitch() {
        assertThat(describe(new Completed())).isEqualTo("completed");
        assertThat(describe(new TokenRejected())).isEqualTo("token");
        assertThat(describe(new PasswordRejected(PasswordResetRejectionReason.PASSWORD_TOO_LONG)))
                .isEqualTo("password");
        assertThat(describe(new SecondFactorMissing())).isEqualTo("missing");
        assertThat(describe(new SecondFactorRejected())).isEqualTo("rejected");
        assertThat(ResetOutcome.class.getPermittedSubclasses()).hasSize(5);
    }

    @Test
    void theCommandCarriesNoInstitutionAndPrintsNeitherTheTokenNorThePassword() {
        ResetPasswordCommand command = command(TOKEN, NEW_PASSWORD, new SecondFactorProof.None());

        assertThat(ResetPasswordCommand.class.getDeclaredFields())
                .noneMatch(field -> field.getName().toLowerCase().contains("institution"));
        assertThat(command.toString()).doesNotContain(TOKEN).doesNotContain(NEW_PASSWORD);
    }

    private static String describe(ResetOutcome outcome) {
        return switch (outcome) {
            case Completed completed -> "completed";
            case TokenRejected rejected -> "token";
            case PasswordRejected rejected -> "password";
            case SecondFactorMissing missing -> "missing";
            case SecondFactorRejected rejected -> "rejected";
        };
    }

    private StaffAccountId seedLiveTokenFor(boolean mfaRequired) {
        StaffAccountId account = new StaffAccountId(UUID.randomUUID());
        Instant issuedAt = NOW.minusSeconds(600);
        tokens.row = new PasswordResetTokenRow(UUID.randomUUID(), account, issuedAt,
                new PasswordResetTokenPolicy().expiresAt(issuedAt), null, null);
        tokens.rowHash = PasswordResetTokenHash.of(PlainPasswordResetToken.of(TOKEN));
        accounts.account = new StaffAccount(account, INSTITUTION,
                LoginIdentifier.of("ana.martinez@colegio.edu.hn"),
                new StoredPasswordHash("$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aW5pY2lhbA"),
                mfaRequired);
        return account;
    }

    private static ResetPasswordCommand command(String token, String password,
            SecondFactorProof proof) {
        return new ResetPasswordCommand(token, password, proof);
    }

    private ResetPasswordWithToken useCase() {
        TransactionRunner runner = neverConnectingRunner();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        VerifyTotpCode verifyTotp = new VerifyTotpCode(runner, totpCredentials,
                new UnusedTotpBackoffStore(), new ColumnEncryptionService(null, new AesGcmCipher()),
                audit::add, clock);
        ConsumeRecoveryCode consumeRecoveryCode = new ConsumeRecoveryCode(runner,
                new UnusedRecoveryCodes(), new UnusedRecoveryCodeHasher(), audit::add, clock);
        return new ResetPasswordWithToken(runner, () -> INSTITUTION, tokens, accounts,
                totpCredentials, verifyTotp, consumeRecoveryCode, hasher, audit::add, clock);
    }

    private static TransactionRunner neverConnectingRunner() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        return new TransactionRunner(new DataSourceTransactionManager(dataSource), dataSource);
    }

    private static final class FakeTokens implements PasswordResetTokenRepository {
        PasswordResetTokenRow row;
        PasswordResetTokenHash rowHash;
        boolean consumeWins = true;
        final List<UUID> consumed = new ArrayList<>();

        @Override
        public long countIssuedSince(InstitutionId i, StaffAccountId a, Instant since) {
            throw new UnsupportedOperationException("a reset never counts issuances");
        }

        @Override
        public int supersedeOpen(InstitutionId i, StaffAccountId a, Instant at) {
            throw new UnsupportedOperationException("a reset never supersedes");
        }

        @Override
        public void insert(InstitutionId i, UUID id, StaffAccountId a, PasswordResetTokenHash h,
                Instant issuedAt, Instant expiresAt) {
            throw new UnsupportedOperationException("a reset never issues");
        }

        @Override
        public Optional<PasswordResetTokenRow> findByHash(InstitutionId i,
                PasswordResetTokenHash hash) {
            return Optional.ofNullable(row).filter(r -> hash.equals(rowHash));
        }

        @Override
        public boolean consume(InstitutionId i, UUID id, Instant at) {
            consumed.add(id);
            return consumeWins;
        }
    }

    private static final class FakeAccounts implements StaffAccountRepository {
        StaffAccount account;
        int locks;
        StoredPasswordHash replacedWith;

        @Override
        public Optional<StaffAccount> findBy(InstitutionId i, LoginIdentifier identifier) {
            throw new UnsupportedOperationException("a reset never looks an account up by address");
        }

        @Override
        public Optional<StaffAccount> lockById(InstitutionId i, StaffAccountId accountId) {
            locks++;
            return Optional.ofNullable(account);
        }

        @Override
        public boolean replacePasswordHash(InstitutionId i, StaffAccountId a,
                StoredPasswordHash newHash) {
            replacedWith = newHash;
            return true;
        }
    }

    private static final class FakeTotpCredentials implements TotpCredentialRepository {
        boolean enrolled;
        int lookups;

        @Override
        public void insert(InstitutionId i, StaffAccountId a, String encryptedSecret) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<TotpCredential> findByAccountId(InstitutionId i, StaffAccountId a) {
            lookups++;
            return enrolled ? Optional.of(new TotpCredential(a,
                    "v1:00000000-0000-0000-0000-000000000000:aXY=:Y2lwaGVy:dGFn", 0L))
                    : Optional.empty();
        }

        @Override
        public boolean acceptCounter(InstitutionId i, StaffAccountId a, long candidateCounter) {
            throw new UnsupportedOperationException("never verified in this unit test");
        }
    }

    private static final class CountingHasher implements PasswordHasher {
        int hashes;

        @Override
        public boolean matches(PlainPassword password, StoredPasswordHash hash) {
            throw new UnsupportedOperationException("a reset never verifies the old password");
        }

        @Override
        public StoredPasswordHash hash(PlainPassword password) {
            hashes++;
            return new StoredPasswordHash("$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$bnVldmE");
        }

        @Override
        public StoredPasswordHash decoyHash() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnusedTotpBackoffStore implements TotpVerificationBackoffStore {
        @Override
        public com.confia.identity.domain.BackoffState claim(InstitutionId i, StaffAccountId a,
                Instant now) {
            throw new UnsupportedOperationException("never verified in this unit test");
        }

        @Override
        public void save(InstitutionId i, StaffAccountId a,
                com.confia.identity.domain.BackoffState state) {
            throw new UnsupportedOperationException("never verified in this unit test");
        }
    }

    private static final class UnusedRecoveryCodes implements RecoveryCodeRepository {
        @Override
        public void insert(InstitutionId i, StaffAccountId a, UUID id, StoredRecoveryCodeHash h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.confia.identity.domain.RecoveryCodeRow> findUnusedByAccountId(
                InstitutionId i, StaffAccountId a) {
            throw new UnsupportedOperationException("never consumed in this unit test");
        }

        @Override
        public boolean markUsed(InstitutionId i, UUID id, Instant usedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countUnusedByAccountId(InstitutionId i, StaffAccountId a) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class UnusedRecoveryCodeHasher implements RecoveryCodeHasher {
        @Override
        public StoredRecoveryCodeHash hash(PlainRecoveryCode code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean matches(PlainRecoveryCode code, StoredRecoveryCodeHash hash) {
            throw new UnsupportedOperationException("never consumed in this unit test");
        }
    }
}

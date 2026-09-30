package com.confia.identity.application;

import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.PlainPasswordResetToken;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpAlgorithm;
import com.confia.identity.domain.TotpCode;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.infrastructure.JooqDataEncryptionKeyRepository;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shared set-up for the password-reset integration tests (password-recovery-token tasks.md, cut
 * C4): a fresh institution per test, the real adapters, and helpers that go through the real use
 * cases, so a live token always comes from {@link IssuePasswordResetToken} and a password is always
 * checked by the real {@link AuthenticateWithPassword}. Abstract, so the {@code *IT} naming rule
 * does not apply to it.
 */
abstract class PasswordResetIntegrationTest extends CommittingPostgresIntegrationTest {

    static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
    static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);
    static final Duration TOTP_PERIOD = Duration.ofSeconds(30);

    final InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
    private final ColumnEncryptionMasterKey masterKey = randomMasterKey();

    SecurityContext context() {
        return contextOf(institutionId);
    }

    static SecurityContext contextOf(InstitutionId institution) {
        return new SecurityContext("", "system", institution.value().toString(),
                UUID.randomUUID().toString());
    }

    StaffAccountId seedStaffAccount(String email, String password, boolean mfaRequired) {
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        String hash = HASHER.hash(PlainPassword.of(password)).value();
        transactionRunner().execute(context(), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, ?)
                    """, institutionId.value(), accountId.value(),
                    LoginIdentifier.of(email).value(), hash, mfaRequired);
            return null;
        });
        return accountId;
    }

    /** Enrolls TOTP and ten recovery codes through the real enrollment use case. */
    EnrollTotpSecondFactorResult enroll(StaffAccountId accountId) {
        return new EnrollTotpSecondFactor(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), PEPPER),
                encryptionService(), new JooqAuditLogWriter(dsl))
                .execute(context(), accountId);
    }

    PlainTotpSecret enrollTotp(StaffAccountId accountId) {
        return enroll(accountId).secret();
    }

    /** A live token for the account, issued at {@code instant} by the real issuance. */
    PlainPasswordResetToken issueTokenAt(StaffAccountId accountId, String instant) {
        List<PlainPasswordResetToken> captured = new ArrayList<>();
        new IssuePasswordResetToken(transactionRunner(), new JooqStaffAccountRepository(dsl),
                new JooqPasswordResetTokenRepository(dsl),
                (institution, account, token) -> captured.add(token), new JooqAuditLogWriter(dsl),
                new SecureRandom(), fixedClock(instant))
                .execute(context(), accountId);
        if (captured.size() != 1) {
            throw new IllegalStateException("the issuance was expected to deliver one token");
        }
        return captured.get(0);
    }

    ResetPasswordDecision resetAt(String presentedToken, String newPassword,
            SecondFactorProof proof, String instant) {
        return resetWith(presentedToken, newPassword, proof, instant, new JooqAuditLogWriter(dsl),
                new JooqPasswordResetTokenRepository(dsl));
    }

    ResetPasswordDecision resetWith(String presentedToken, String newPassword,
            SecondFactorProof proof, String instant, AuditLogWriter auditLogWriter,
            PasswordResetTokenRepository tokens) {
        return resetUseCase(instant, auditLogWriter, tokens, institutionId).execute(context(),
                new ResetPasswordCommand(presentedToken, newPassword, proof));
    }

    ResetPasswordWithToken resetUseCase(String instant, AuditLogWriter auditLogWriter,
            PasswordResetTokenRepository tokens, InstitutionId configuredInstitution) {
        Clock clock = fixedClock(instant);
        VerifyTotpCode verifyTotp = new VerifyTotpCode(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqTotpVerificationBackoffStore(dsl),
                encryptionService(), auditLogWriter, clock);
        ConsumeRecoveryCode consumeRecoveryCode = new ConsumeRecoveryCode(transactionRunner(),
                new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), PEPPER), auditLogWriter,
                clock);
        return new ResetPasswordWithToken(transactionRunner(), () -> configuredInstitution, tokens,
                new JooqStaffAccountRepository(dsl), new JooqTotpCredentialRepository(dsl),
                verifyTotp, consumeRecoveryCode, HASHER, auditLogWriter, clock);
    }

    AuthenticationResult loginAt(String email, String password, String instant) {
        return new AuthenticateWithPassword(transactionRunner(), () -> institutionId,
                new JooqStaffAccountRepository(dsl), new JooqTotpCredentialRepository(dsl),
                new JooqLoginBackoffStore(dsl), HASHER, FINGERPRINTER, new JooqAuditLogWriter(dsl),
                fixedClock(instant))
                .execute(context(), new AuthenticationCommand(email, password))
                .result();
    }

    Optional<PasswordResetTokenRow> rowOf(PlainPasswordResetToken token) {
        return transactionRunner().execute(context(),
                () -> new JooqPasswordResetTokenRepository(dsl).findByHash(institutionId,
                        PasswordResetTokenHash.of(token)));
    }

    boolean isOpen(PlainPasswordResetToken token) {
        return rowOf(token).map(row -> row.consumedAt() == null && row.supersededAt() == null)
                .orElse(false);
    }

    List<AuditRowSnapshot> auditRows() {
        return transactionRunner().execute(context(),
                () -> new JooqAuditLogReader(dsl).pageOf(institutionId, 0, 500));
    }

    List<AuditRowSnapshot> auditRows(String action) {
        return auditRows().stream().filter(row -> row.action().equals(action)).toList();
    }

    /** The TOTP code the secret produces at {@code instant}. */
    static TotpCode validCodeAt(PlainTotpSecret secret, String instant) {
        return TotpAlgorithm.generate(secret.value(),
                TotpAlgorithm.counterFor(Instant.parse(instant), TOTP_PERIOD));
    }

    /** The smallest six-digit code outside every counter two steps around {@code instant}. */
    static TotpCode wrongCodeAt(PlainTotpSecret secret, String instant) {
        long counter = TotpAlgorithm.counterFor(Instant.parse(instant), TOTP_PERIOD);
        Set<String> acceptable = new HashSet<>();
        for (long candidate = counter - 2; candidate <= counter + 2; candidate++) {
            acceptable.add(TotpAlgorithm.generate(secret.value(), candidate).value());
        }
        for (int value = 0; ; value++) {
            String code = String.format(Locale.ROOT, "%06d", value);
            if (!acceptable.contains(code)) {
                return new TotpCode(code);
            }
        }
    }

    /** The TOTP verification after login, through its own real transaction. */
    VerifyTotpCodeDecision verifyAfterLoginAt(StaffAccountId accountId, TotpCode code,
            String instant) {
        return new VerifyTotpCode(transactionRunner(), new JooqTotpCredentialRepository(dsl),
                new JooqTotpVerificationBackoffStore(dsl), encryptionService(),
                new JooqAuditLogWriter(dsl), fixedClock(instant))
                .execute(context(), accountId, code);
    }

    ColumnEncryptionService encryptionService() {
        return new ColumnEncryptionService(
                new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey),
                new AesGcmCipher());
    }

    static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static ColumnEncryptionMasterKey randomMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return ColumnEncryptionMasterKey.fromBase64(Base64.getEncoder().encodeToString(key));
    }
}

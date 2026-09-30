package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import com.confia.identity.application.AuthenticateWithPassword;
import com.confia.identity.application.AuthenticationCommand;
import com.confia.identity.application.AuthenticationDecision;
import com.confia.identity.application.EnrollTotpSecondFactor;
import com.confia.identity.application.EnrollTotpSecondFactorResult;
import com.confia.identity.application.VerifyTotpCode;
import com.confia.identity.application.VerifyTotpCodeDecision;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.PlainTotpSecret;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import com.confia.identity.domain.TotpAlgorithm;
import com.confia.identity.domain.TotpCode;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
import com.confia.identity.testsupport.fixture.LeakingMfaSecretFixture;
import com.confia.kernel.AeadIntegrityException;
import com.confia.kernel.AesGcmCipher;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogReader;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.crypto.ColumnEncryptionMasterKey;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.crypto.DataEncryptionKeyMaterial;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

/**
 * Specs/identity/spec.md, requirement "Ningún secreto de este módulo es observable en registros,
 * excepciones ni pruebas": a real attempt with the delta's own literal password ({@code
 * Segura#2026}), collecting every piece of text this module produces along the way — exception
 * messages, every involved object's own {@code toString()}, and the rows written to {@code
 * shared_audit_log} — and asserting none of it carries the password, the computed hash or the
 * pepper.
 *
 * <p><b>The assertion never puts the collected text into its own failure message</b> (task 4.3's
 * own instruction: "comparando longitudes y formas, nunca el valor, en los mensajes de fallo del
 * propio {@code assertThat}"). A naive {@code assertThat(collected).doesNotContain(secret)} would,
 * on failure, print AssertJ's own default message — {@code "Expecting actual: <the haystack> not
 * to contain: <the secret>"} — which would put the very secret this test exists to keep out
 * directly into the test report. {@link #assertSecretNeverLeaked} instead reports only the
 * secret's length and description on failure, never its value or the haystack it was found in.
 */
class IdentitySecretRedactionIT extends CommittingPostgresIntegrationTest {

    private static final String PASSWORD_LITERAL = "Segura#2026";

    @Test
    void noLogNorExceptionNorToStringNorAuditRowExposesThePasswordTheHashOrThePepper() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("maria.lopez@colegio.edu.hn");
        byte[] pepperBytes = new byte[32];
        pepperBytes[0] = 0x2a;
        Argon2Pepper pepper = Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(pepperBytes));
        BouncyCastleArgon2PasswordHasher hasher =
                new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), pepper);
        HmacLoginIdentifierFingerprinter fingerprinter = new HmacLoginIdentifierFingerprinter(pepper);
        PlainPassword plainPassword = PlainPassword.of(PASSWORD_LITERAL);
        StoredPasswordHash storedHash = hasher.hash(plainPassword);
        seedStaffAccount(institutionId, identifier, storedHash);

        AuthenticateWithPassword useCase = new AuthenticateWithPassword(transactionRunner(),
                () -> institutionId, new JooqStaffAccountRepository(dsl),
                new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), hasher,
                fingerprinter, new JooqAuditLogWriter(dsl),
                Clock.fixed(Instant.parse("2026-03-10T16:00:00Z"), ZoneOffset.UTC));

        AuthenticationDecision decision = useCase.execute(contextOf(institutionId),
                new AuthenticationCommand(identifier.value(), PASSWORD_LITERAL));

        // A forced failure, to collect a real exception message too: the closing guard fires
        // loudly on a mismatched institution (design.md decision 11).
        SecurityContext mismatchedContext = new SecurityContext("", "system",
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        String guardExceptionMessage;
        try {
            useCase.execute(mismatchedContext,
                    new AuthenticationCommand(identifier.value(), PASSWORD_LITERAL));
            throw new IllegalStateException("the closing guard must have thrown here");
        } catch (IllegalStateException e) {
            guardExceptionMessage = e.getMessage();
        }

        StringBuilder producedText = new StringBuilder()
                .append(decision)
                .append(decision.result())
                .append(guardExceptionMessage)
                // The command itself, which this class built twice above and never looked at until
                // the pre-merge security audit pointed it out. It is a record carrying the password
                // in a bare String, so its compiler-generated toString() printed the password in
                // clear — and this test, the one written to catch exactly that, handled the leaking
                // object without ever collecting its text.
                .append(new AuthenticationCommand(identifier.value(), PASSWORD_LITERAL))
                .append(plainPassword)
                .append(storedHash)
                .append(pepper)
                .append(hasher.decoyHash());
        for (AuditRowSnapshot row : auditRowsFor(institutionId)) {
            producedText.append(row);
        }
        String collected = producedText.toString();

        assertThat(decision.result()).isNotNull();
        assertSecretNeverLeaked(collected, "the clear-text password", PASSWORD_LITERAL);
        assertSecretNeverLeaked(collected, "the computed Argon2id hash", storedHash.value());
        assertSecretNeverLeaked(collected, "the Argon2id pepper (base64)",
                Base64.getEncoder().encodeToString(pepper.value()));
    }

    /**
     * Specs/identity/spec.md, requirement "Ningún secreto nuevo de este cambio es observable en
     * registros, excepciones, {@code toString()} ni pruebas" (column-encryption-and-mfa-totp,
     * task 5.1; design.md decision 9): a real, complete TOTP enrollment for {@code
     * sofia.mejia@colegio.edu.hn} — the secret, encrypted under a real KEK-wrapped DEK, and ten
     * recovery codes — one rejected TOTP verification, and one deliberate AES-GCM authentication
     * failure forced by tampering with the additional authenticated data. Everything those steps
     * produce as text is collected and swept for the four secrets.
     *
     * <p>Each byte secret is searched for in every encoding it could plausibly leak in (base64,
     * lowercase and uppercase hex, {@link Arrays#toString(byte[])}, and base32 for the TOTP
     * secret), because a hand-written {@code toString()} that leaks rarely prints raw bytes.
     *
     * <p>Rows are rendered value by value, never through jOOQ's {@code Result.format()}: that
     * renderer truncates long cells, which would let a leak past the sweep without it ever having
     * read the whole value.
     */
    @Test
    void noExceptionNorToStringNorRowExposesTheTotpSecretARecoveryCodeTheKekOrADek() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        ColumnEncryptionMasterKey masterKey = randomMasterKey();
        String masterKeyBase64 = Base64.getEncoder().encodeToString(masterKey.value());
        Argon2Pepper pepper = Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(
                new byte[32]));
        seedMfaStaffAccount(institutionId, accountId, "sofia.mejia@colegio.edu.hn");
        ColumnEncryptionService encryption = encryptionServiceFor(masterKey);

        EnrollTotpSecondFactorResult enrollment = new EnrollTotpSecondFactor(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqRecoveryCodeRepository(dsl),
                new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), pepper), encryption,
                new JooqAuditLogWriter(dsl))
                .execute(contextOf(institutionId), accountId);
        PlainTotpSecret totpSecret = enrollment.secret();

        Instant now = Instant.parse("2026-03-10T16:00:00Z");
        VerifyTotpCodeDecision rejected = new VerifyTotpCode(transactionRunner(),
                new JooqTotpCredentialRepository(dsl), new JooqTotpVerificationBackoffStore(dsl),
                encryption, new JooqAuditLogWriter(dsl), Clock.fixed(now, ZoneOffset.UTC))
                .execute(contextOf(institutionId), accountId,
                        aCodeOutsideTheWindow(totpSecret, now));

        DataEncryptionKeyMaterial dek = transactionRunner().execute(contextOf(institutionId),
                () -> new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey)
                        .findActiveOrCreate(institutionId));
        String tamperedAadFailure = decryptWithAnotherRowsAad(encryption, institutionId, accountId);

        List<StoredRecoveryCodeHash> storedHashes = transactionRunner().execute(
                contextOf(institutionId), () -> dsl.fetch("""
                        select code_hash from identity_mfa_recovery_code
                        where institution_id = ? and account_id = ?
                        """, institutionId.value(), accountId.value())
                        .stream()
                        .map(row -> new StoredRecoveryCodeHash(row.get("code_hash", String.class)))
                        .toList());

        StringBuilder producedText = new StringBuilder()
                .append(enrollment)
                .append(totpSecret)
                .append(rejected)
                .append(masterKey)
                .append(dek)
                .append(tamperedAadFailure)
                .append(exceptionMessageOf(() -> ColumnEncryptionMasterKey.fromBase64(
                        masterKeyBase64.substring(4))))
                .append(exceptionMessageOf(() -> new DataEncryptionKeyMaterial(dek.id(),
                        Arrays.copyOf(dek.rawKeyBytes(), 31))))
                .append(exceptionMessageOf(() -> PlainTotpSecret.of(
                        Arrays.copyOf(totpSecret.value(), 19))))
                .append(exceptionMessageOf(() -> PlainRecoveryCode.of(
                        enrollment.recoveryCodes().get(0).value() + "X")));
        for (PlainRecoveryCode code : enrollment.recoveryCodes()) {
            producedText.append(code);
        }
        for (StoredRecoveryCodeHash hash : storedHashes) {
            producedText.append(hash);
        }
        for (String table : List.of("shared_data_encryption_key", "identity_mfa_totp_credential",
                "identity_mfa_recovery_code", "identity_mfa_totp_backoff", "shared_audit_log")) {
            producedText.append(rowsOf(table, institutionId));
        }
        for (AuditRowSnapshot row : auditRowsFor(institutionId)) {
            producedText.append(row);
        }
        String collected = producedText.toString();

        assertThat(enrollment.recoveryCodes()).hasSize(10);
        assertThat(storedHashes)
                .as("the sweep must have hashes to look at, or their redaction proves nothing")
                .hasSize(10);
        assertThat(rejected.accepted()).isFalse();
        assertBytesNeverLeaked(collected, "the clear-text TOTP secret", totpSecret.value());
        assertSecretNeverLeaked(collected, "the clear-text TOTP secret (base32)",
                totpSecret.base32());
        for (PlainRecoveryCode code : enrollment.recoveryCodes()) {
            assertSecretNeverLeaked(collected, "a clear-text recovery code", code.value());
        }
        assertBytesNeverLeaked(collected, "the column-encryption master key (KEK)",
                masterKey.value());
        assertBytesNeverLeaked(collected, "the unwrapped data-encryption key (DEK)",
                dek.rawKeyBytes());
        // The stored hash legitimately sits in its own row, so it is swept against every
        // toString() of its value object only, never against the rows.
        StringBuilder hashToStrings = new StringBuilder();
        storedHashes.forEach(hashToStrings::append);
        for (StoredRecoveryCodeHash hash : storedHashes) {
            assertSecretNeverLeaked(hashToStrings.toString(), "a stored recovery code hash",
                    hash.value());
        }
    }

    /**
     * The negative control design.md decision 9 demands, kept in its own method so a failure here
     * and a failure of the sweep above are never confused in the build report: the deliberately
     * leaking fixture does leak through its generated {@code toString()}, and {@link
     * #assertSecretNeverLeaked} — the very helper every sweep of this class relies on — does reject
     * it, without repeating the leaked value in its own failure message.
     */
    @Test
    void theRedactionSweepDetectsARealLeak() {
        String fakeSecret = "not-a-real-secret-just-proves-the-sweep-works";
        LeakingMfaSecretFixture fixture = new LeakingMfaSecretFixture(fakeSecret);

        assertThat(fixture.toString())
                .as("if this ever fails, the fixture changed shape, not the production code")
                .contains(fakeSecret);
        assertThatThrownBy(() -> assertSecretNeverLeaked(fixture.toString(),
                "the fixture's fake secret", fakeSecret))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("the fixture's fake secret")
                .hasMessageNotContaining(fakeSecret);
    }

    /** Every encoding a leaked byte secret could plausibly take in produced text. */
    private static void assertBytesNeverLeaked(String haystack, String secretDescription,
            byte[] secret) {
        String hex = HexFormat.of().formatHex(secret);
        assertSecretNeverLeaked(haystack, secretDescription + " (base64)",
                Base64.getEncoder().encodeToString(secret));
        assertSecretNeverLeaked(haystack, secretDescription + " (base64url)",
                Base64.getUrlEncoder().withoutPadding().encodeToString(secret));
        assertSecretNeverLeaked(haystack, secretDescription + " (hex)", hex);
        assertSecretNeverLeaked(haystack, secretDescription + " (upper-case hex)",
                hex.toUpperCase(Locale.ROOT));
        assertSecretNeverLeaked(haystack, secretDescription + " (Arrays.toString)",
                Arrays.toString(secret));
    }

    /** A six-digit code guaranteed to fall outside the ±1 step tolerance window at {@code now},
     * so the rejection is certain rather than probable. */
    private static TotpCode aCodeOutsideTheWindow(PlainTotpSecret secret, Instant now) {
        long counter = TotpAlgorithm.counterFor(now, Duration.ofSeconds(30));
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

    /** Decrypts the enrolled secret under another row's additional authenticated data, forcing a
     * real GCM authentication failure, and returns every message along its cause chain. */
    private String decryptWithAnotherRowsAad(ColumnEncryptionService encryption,
            InstitutionId institutionId, StaffAccountId accountId) {
        return transactionRunner().execute(contextOf(institutionId), () -> {
            String stored = dsl.fetchOne("""
                    select encrypted_secret from identity_mfa_totp_credential
                    where institution_id = ? and account_id = ?
                    """, institutionId.value(), accountId.value())
                    .get("encrypted_secret", String.class);
            try {
                encryption.decrypt("identity_mfa_totp_credential", "encrypted_secret",
                        institutionId, institutionId.value() + ":" + UUID.randomUUID(), stored);
            } catch (AeadIntegrityException e) {
                return messagesAlongTheCauseChain(e);
            }
            throw new IllegalStateException("decrypting under another row's AAD must fail");
        });
    }

    private static String exceptionMessageOf(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return messagesAlongTheCauseChain(e);
        }
        throw new IllegalStateException("the action was expected to throw");
    }

    private static String messagesAlongTheCauseChain(Throwable throwable) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            messages.append(current).append('|');
        }
        return messages.toString();
    }

    /** Every value of every row the institution owns in {@code table}, each rendered in full. The
     * table name is one of five constants above, and reaches the statement as a quoted jOOQ name,
     * never by string concatenation (CLAUDE.md, regla 12). */
    private String rowsOf(String table, InstitutionId institutionId) {
        return transactionRunner().execute(contextOf(institutionId), () -> {
            List<Record> rows = dsl.selectFrom(DSL.table(DSL.name(table)))
                    .where(DSL.field(DSL.name("institution_id")).eq(institutionId.value()))
                    .fetch();
            assertThat(rows)
                    .as("%s must hold at least one row, or the sweep never looked at it", table)
                    .isNotEmpty();
            StringBuilder text = new StringBuilder();
            for (Record row : rows) {
                for (Object value : row.intoArray()) {
                    text.append(value).append('|');
                }
            }
            return text.toString();
        });
    }

    private ColumnEncryptionService encryptionServiceFor(ColumnEncryptionMasterKey masterKey) {
        return new ColumnEncryptionService(
                new JooqDataEncryptionKeyRepository(dsl, new AesGcmCipher(), masterKey),
                new AesGcmCipher());
    }

    private static ColumnEncryptionMasterKey randomMasterKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return ColumnEncryptionMasterKey.fromBase64(Base64.getEncoder().encodeToString(key));
    }

    private void seedMfaStaffAccount(InstitutionId institutionId, StaffAccountId accountId,
            String email) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, true)
                    """, institutionId.value(), accountId.value(), email,
                    "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g");
            return null;
        });
    }

    /**
     * Fails with only the secret's own description and length, never its value nor the haystack it
     * was searched in — the discipline this whole test class exists to demonstrate applies to its
     * own assertion, not only to production code.
     */
    private static void assertSecretNeverLeaked(String haystack, String secretDescription,
            String secretValue) {
        if (haystack.contains(secretValue)) {
            fail("produced text unexpectedly contains %s (%d characters); the value itself is "
                    + "deliberately withheld from this failure message", secretDescription,
                    secretValue.length());
        }
    }

    private void seedStaffAccount(InstitutionId institutionId, LoginIdentifier identifier,
            StoredPasswordHash hash) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), UUID.randomUUID(), identifier.value(), hash.value());
            return null;
        });
    }

    private List<AuditRowSnapshot> auditRowsFor(InstitutionId institutionId) {
        AuditLogReader reader = new JooqAuditLogReader(dsl);
        return transactionRunner().execute(contextOf(institutionId),
                () -> reader.pageOf(institutionId, 0, 100));
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

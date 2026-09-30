package com.confia.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.confia.identity.application.AuthenticateWithPassword;
import com.confia.identity.application.AuthenticationCommand;
import com.confia.identity.application.AuthenticationDecision;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogReader;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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

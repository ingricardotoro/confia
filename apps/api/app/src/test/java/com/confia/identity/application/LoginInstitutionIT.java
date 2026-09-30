package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.ConfiguredLoginInstitutionProvider;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The institution half of design.md decision 11 (specs/identity/spec.md, requirement "La
 * institución previa a la autenticación proviene de la configuración del proceso"), with the real
 * {@link ConfiguredLoginInstitutionProvider} adapter this cut delivers — {@code
 * AuthenticateWithPasswordIT} (task 4.1) uses a plain lambda for its own institution provider on
 * purpose, because its own focus is the backoff scenarios, not institution resolution.
 *
 * <p><b>Why there is no scenario literally titled "a request declaring a different institution in
 * its own body is ignored", beyond {@link
 * #theConfiguredProcessInstitutionIsAlwaysUsedRegardlessOfTheRequest}.</b> {@link
 * AuthenticationCommand} has exactly two fields, {@code presentedIdentifier} and {@code
 * presentedPassword} — it carries no institution field at all, so a caller has no channel through
 * which to declare one. That is the strongest form design.md decision 11's "NO DEBE provenir del
 * cuerpo de la solicitud" can take: not a rule that is checked and rejected, but a shape that makes
 * the violation impossible to construct in the first place.
 */
class LoginInstitutionIT extends CommittingPostgresIntegrationTest {

    /** design.md, §6.1, decision 11 — the literal key {@link ConfiguredLoginInstitutionProvider} reads. */
    private static final String CONFIG_KEY = "confia.identity.login-institution-id";

    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
    private static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    private static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);
    private static final String SHARED_EMAIL = "maria.lopez@colegio.edu.hn";

    @Test
    void twoInstitutionsWithTheSameEmailEachResolveTheirOwnAccountNeverTheOthers() {
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of(SHARED_EMAIL);
        seedStaffAccount(institutionA, identifier, "PasswordDeA#2026");
        seedStaffAccount(institutionB, identifier, "PasswordDeB#2026");

        AuthenticationDecision decisionA = attemptWithConfiguredProvider(institutionA,
                identifier.value(), "PasswordDeA#2026");
        AuthenticationDecision decisionB = attemptWithConfiguredProvider(institutionB,
                identifier.value(), "PasswordDeB#2026");

        assertThat(decisionA.result()).isInstanceOf(Authenticated.class);
        assertThat(((Authenticated) decisionA.result()).institutionId()).isEqualTo(institutionA);
        assertThat(decisionB.result()).isInstanceOf(Authenticated.class);
        assertThat(((Authenticated) decisionB.result()).institutionId()).isEqualTo(institutionB);

        AuthenticationDecision crossAttempt = attemptWithConfiguredProvider(institutionB,
                identifier.value(), "PasswordDeA#2026");
        assertThat(crossAttempt.result())
                .as("the same email exists in both institutions, but A's password must never "
                        + "authenticate against B's account")
                .isInstanceOf(Rejected.class);
    }

    @Test
    void theConfiguredProcessInstitutionIsAlwaysUsedRegardlessOfTheRequest() {
        UUID configured = UUID.randomUUID();
        System.setProperty(CONFIG_KEY, configured.toString());
        try {
            ConfiguredLoginInstitutionProvider provider = new ConfiguredLoginInstitutionProvider();
            assertThat(provider.loginInstitutionId()).isEqualTo(new InstitutionId(configured));
        } finally {
            System.clearProperty(CONFIG_KEY);
        }
    }

    @Test
    void theClosingGuardFailsLoudlyOnAMismatchedContextNeverReturningRejected() {
        InstitutionId configured = new InstitutionId(UUID.randomUUID());
        InstitutionId requested = new InstitutionId(UUID.randomUUID());
        System.setProperty(CONFIG_KEY, configured.value().toString());
        try {
            AuthenticateWithPassword useCase = new AuthenticateWithPassword(transactionRunner(),
                    new ConfiguredLoginInstitutionProvider(), new JooqStaffAccountRepository(dsl),
                    new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), HASHER,
                    FINGERPRINTER, new JooqAuditLogWriter(dsl),
                    fixedClock("2026-03-10T15:00:00Z"));
            SecurityContext mismatchedContext = new SecurityContext("", "system",
                    requested.value().toString(), UUID.randomUUID().toString());

            assertThatThrownBy(() -> useCase.execute(mismatchedContext,
                    new AuthenticationCommand("someone@colegio.edu.hn", "whatever")))
                    .as("an institution mismatch is a wiring defect or an attack, never a rejected "
                            + "login (design.md, decision 11)")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            System.clearProperty(CONFIG_KEY);
        }
    }

    private AuthenticationDecision attemptWithConfiguredProvider(InstitutionId institutionId,
            String presentedIdentifier, String presentedPassword) {
        System.setProperty(CONFIG_KEY, institutionId.value().toString());
        try {
            AuthenticateWithPassword useCase = new AuthenticateWithPassword(transactionRunner(),
                    new ConfiguredLoginInstitutionProvider(), new JooqStaffAccountRepository(dsl),
                    new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), HASHER,
                    FINGERPRINTER, new JooqAuditLogWriter(dsl),
                    fixedClock("2026-03-10T15:00:00Z"));
            SecurityContext context = new SecurityContext("", "system",
                    institutionId.value().toString(), UUID.randomUUID().toString());
            return useCase.execute(context,
                    new AuthenticationCommand(presentedIdentifier, presentedPassword));
        } finally {
            System.clearProperty(CONFIG_KEY);
        }
    }

    private void seedStaffAccount(InstitutionId institutionId, LoginIdentifier identifier,
            String password) {
        StoredPasswordHash hash = HASHER.hash(PlainPassword.of(password));
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), UUID.randomUUID(), identifier.value(), hash.value());
            return null;
        });
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

package com.confia.identity.infrastructure.wiring;

import com.confia.identity.application.AuthenticateWithPassword;
import com.confia.identity.application.ConsumeRecoveryCode;
import com.confia.identity.application.EnrollTotpSecondFactor;
import com.confia.identity.application.LoginBackoffStore;
import com.confia.identity.application.LoginIdentifierFingerprinter;
import com.confia.identity.application.LoginInstitutionProvider;
import com.confia.identity.application.PasswordHasher;
import com.confia.identity.application.PasswordResetTokenRepository;
import com.confia.identity.application.RecoveryCodeHasher;
import com.confia.identity.application.RecoveryCodeRepository;
import com.confia.identity.application.ResetPasswordWithToken;
import com.confia.identity.application.StaffAccountRepository;
import com.confia.identity.application.TotpCredentialRepository;
import com.confia.identity.application.TotpVerificationBackoffStore;
import com.confia.identity.application.VerifyTotpCode;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher;
import com.confia.identity.infrastructure.ConfiguredLoginInstitutionProvider;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqPasswordResetTokenRepository;
import com.confia.identity.infrastructure.JooqRecoveryCodeRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.identity.infrastructure.JooqTotpVerificationBackoffStore;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.crypto.ColumnEncryptionService;
import com.confia.shared.security.TransactionRunner;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The public configuration of the {@code identity} module for the administrative process
 * (web-edge-foundations design.md, decisions 1 and 3; ADR-0024). It registers the five use cases
 * whose ports have a production adapter, with those adapters, and nothing else: {@code
 * RequestPasswordReset} and {@code IssuePasswordResetToken} are not registered because their
 * scheduler and link-sender ports have no adapter until changes 9 and 14, and registering them
 * would take a fake adapter or a lambda. No identity class changes behavior; they are only
 * registered.
 *
 * <p><b>Secrets fail at startup, never at the first login.</b> The two identity secrets are read
 * with {@link Environment#getProperty(String)}, never bound with {@code @ConfigurationProperties}
 * or {@code @Value}: Spring Boot's binding failure analyzer prints the rejected value, and a
 * secret must never reach a log (CLAUDE.md, regla 11). A missing or malformed value stops the
 * process with an {@link IllegalStateException} that names the property and repeats no part of
 * the value, and the decoder's own exception is not chained because its message carries a
 * fragment of its input. The third secret, the column-encryption master key, is read the same
 * way by {@code SharedPlatformConfiguration}.
 *
 * <p><b>Package.</b> The design placed this class in the module's root package. It cannot live
 * there: its factory methods take a {@link DSLContext}, and {@code JooqConfinedToInfrastructureTest}
 * (ADR-0015, rule 4) forbids {@code org.jooq} outside an {@code infrastructure} package. It lives
 * in a nested package that carries {@code @NamedInterface} (ADR-0024: a public configuration sits
 * in the base package or in a named interface), which exposes this class and none of the
 * adapters beside it. The deviation is recorded in {@code design.md} and {@code tasks.md}.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityConfiguration {

    /** The property the Argon2id pepper is read from (environment variable {@code
     * CONFIA_IDENTITY_ARGON2PEPPER}). */
    static final String PEPPER_PROPERTY = "confia.identity.argon2-pepper";

    /** The property the login institution id is read from (environment variable {@code
     * CONFIA_IDENTITY_LOGININSTITUTIONID}). */
    static final String INSTITUTION_PROPERTY = "confia.identity.login-institution-id";

    @Bean
    Argon2Pepper argon2Pepper(Environment environment) {
        String configured = environment.getProperty(PEPPER_PROPERTY);
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("the property " + PEPPER_PROPERTY
                    + " is required and was not set");
        }
        try {
            return Argon2Pepper.fromBase64(configured);
        } catch (IllegalArgumentException e) {
            // The message of Argon2Pepper states what is wrong and never the value; the cause is
            // not chained so no decoder detail can carry a fragment of the secret.
            throw new IllegalStateException("the property " + PEPPER_PROPERTY
                    + " is not a valid pepper: " + e.getMessage());
        }
    }

    @Bean
    LoginInstitutionProvider loginInstitutionProvider(Environment environment) {
        return ConfiguredLoginInstitutionProvider.fromValue(
                environment.getProperty(INSTITUTION_PROPERTY));
    }

    @Bean
    PasswordHasher passwordHasher(Argon2Pepper pepper) {
        return new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), pepper);
    }

    @Bean
    RecoveryCodeHasher recoveryCodeHasher(Argon2Pepper pepper) {
        return new BouncyCastleRecoveryCodeHasher(Argon2Profile.floor(), pepper);
    }

    @Bean
    LoginIdentifierFingerprinter loginIdentifierFingerprinter(Argon2Pepper pepper) {
        return new HmacLoginIdentifierFingerprinter(pepper);
    }

    @Bean
    StaffAccountRepository staffAccountRepository(DSLContext dsl) {
        return new JooqStaffAccountRepository(dsl);
    }

    @Bean
    LoginBackoffStore loginBackoffStore(DSLContext dsl) {
        return new JooqLoginBackoffStore(dsl);
    }

    @Bean
    TotpCredentialRepository totpCredentialRepository(DSLContext dsl) {
        return new JooqTotpCredentialRepository(dsl);
    }

    @Bean
    TotpVerificationBackoffStore totpVerificationBackoffStore(DSLContext dsl) {
        return new JooqTotpVerificationBackoffStore(dsl);
    }

    @Bean
    RecoveryCodeRepository recoveryCodeRepository(DSLContext dsl) {
        return new JooqRecoveryCodeRepository(dsl);
    }

    @Bean
    PasswordResetTokenRepository passwordResetTokenRepository(DSLContext dsl) {
        return new JooqPasswordResetTokenRepository(dsl);
    }

    @Bean
    AuthenticateWithPassword authenticateWithPassword(TransactionRunner runner,
            LoginInstitutionProvider institutionProvider, StaffAccountRepository accounts,
            TotpCredentialRepository totpCredentials, LoginBackoffStore backoffStore,
            PasswordHasher passwordHasher, LoginIdentifierFingerprinter fingerprinter,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new AuthenticateWithPassword(runner, institutionProvider, accounts, totpCredentials,
                backoffStore, passwordHasher, fingerprinter, auditLogWriter, clock);
    }

    @Bean
    VerifyTotpCode verifyTotpCode(TransactionRunner runner, TotpCredentialRepository credentials,
            TotpVerificationBackoffStore backoffStore, ColumnEncryptionService encryption,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new VerifyTotpCode(runner, credentials, backoffStore, encryption, auditLogWriter,
                clock);
    }

    @Bean
    ConsumeRecoveryCode consumeRecoveryCode(TransactionRunner runner,
            RecoveryCodeRepository recoveryCodes, RecoveryCodeHasher recoveryCodeHasher,
            AuditLogWriter auditLogWriter, Clock clock) {
        return new ConsumeRecoveryCode(runner, recoveryCodes, recoveryCodeHasher, auditLogWriter,
                clock);
    }

    @Bean
    EnrollTotpSecondFactor enrollTotpSecondFactor(TransactionRunner runner,
            TotpCredentialRepository credentials, RecoveryCodeRepository recoveryCodes,
            RecoveryCodeHasher recoveryCodeHasher, ColumnEncryptionService encryption,
            AuditLogWriter auditLogWriter) {
        return new EnrollTotpSecondFactor(runner, credentials, recoveryCodes, recoveryCodeHasher,
                encryption, auditLogWriter);
    }

    @Bean
    ResetPasswordWithToken resetPasswordWithToken(TransactionRunner runner,
            LoginInstitutionProvider institutionProvider, PasswordResetTokenRepository tokens,
            StaffAccountRepository accounts, TotpCredentialRepository totpCredentials,
            VerifyTotpCode verifyTotp, ConsumeRecoveryCode consumeRecoveryCode,
            PasswordHasher passwordHasher, AuditLogWriter auditLogWriter, Clock clock) {
        return new ResetPasswordWithToken(runner, institutionProvider, tokens, accounts,
                totpCredentials, verifyTotp, consumeRecoveryCode, passwordHasher, auditLogWriter,
                clock);
    }
}

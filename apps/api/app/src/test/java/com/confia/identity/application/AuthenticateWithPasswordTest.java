package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.AuthenticationResult.RejectionReason;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

/**
 * {@link AuthenticateWithPassword} against hand-written test doubles of its five application
 * ports (design.md §11, paso 15) — no real transaction, no PostgreSQL. Exercises {@link
 * AuthenticateWithPassword#runWithinTransaction} directly, the package-private method that holds
 * every step design.md's decision 9 draws inside {@code TransactionRunner.execute(...)}'s own box:
 * {@link AuthenticateWithPassword} itself only ever calls that method from inside a real
 * transaction ({@link AuthenticateWithPassword#execute}), which is exactly why testing it directly
 * needs no real {@link TransactionRunner} at all.
 */
class AuthenticateWithPasswordTest {

    private static final InstitutionId INSTITUTION_ID = new InstitutionId(UUID.randomUUID());
    private static final LoginIdentifier EXISTING_IDENTIFIER =
            LoginIdentifier.of("maria.lopez@colegio.edu.hn");
    private static final LoginIdentifier NONEXISTENT_IDENTIFIER =
            LoginIdentifier.of("nadie.registrado@colegio.edu.hn");
    private static final PlainPassword CORRECT_PASSWORD = PlainPassword.of("Segura#2026");
    private static final PlainPassword WRONG_PASSWORD = PlainPassword.of("incorrecta");
    private static final StoredPasswordHash STORED_HASH =
            new StoredPasswordHash("$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$dGFn");
    private static final StoredPasswordHash DECOY_HASH =
            new StoredPasswordHash("$argon2id$v=19$m=19456,t=3,p=1$ZGVjb3k$c2VudGluZWw");

    @Test
    void correctPasswordProducesAuthenticatedWithTheComputedRequiredDelay() {
        // design.md §4's own worked example: three prior failures at 12:00:00, correct password at
        // 12:00:10 — ordinal 4, delay 2^(4-3) = 2 seconds, and still Authenticated.
        Clock clock = fixedClock("2026-03-10T12:00:10Z");
        FakeLoginBackoffStore backoffStore = new FakeLoginBackoffStore();
        IdentifierFingerprint fingerprint = fingerprintOf(EXISTING_IDENTIFIER);
        backoffStore.seed(fingerprint, new BackoffState(3, Instant.parse("2026-03-10T12:00:00Z")));
        FakeStaffAccountRepository accounts = new FakeStaffAccountRepository();
        accounts.add(accountOf(EXISTING_IDENTIFIER));
        FakePasswordHasher passwordHasher = new FakePasswordHasher(CORRECT_PASSWORD, STORED_HASH,
                DECOY_HASH);
        FakeAuditLogWriter auditLogWriter = new FakeAuditLogWriter();

        AuthenticateWithPassword useCase = useCase(accounts, backoffStore, passwordHasher,
                auditLogWriter, clock);

        AuthenticationDecision decision = useCase.runWithinTransaction(INSTITUTION_ID, "",
                new AuthenticationCommand(EXISTING_IDENTIFIER.value(), "Segura#2026"));

        assertThat(decision.result()).isInstanceOf(Authenticated.class);
        assertThat(decision.requiredDelay()).isEqualTo(Duration.ofSeconds(2));
        assertThat(passwordHasher.hashInvocations)
                .as("the stored hash must never be recalculated on the success path")
                .isZero();
        assertThat(backoffStore.savedStates.get(fingerprint).consecutiveFailures())
                .as("a successful login clears the failure counter, even mid-backoff")
                .isZero();
        assertThat(auditLogWriter.entries)
                .as("both the login-succeeded row and the backoff-cycle row are written, because "
                        + "requiredDelay > 0")
                .hasSize(2);
    }

    @Test
    void wrongPasswordAgainstAnExistingAccountProducesRejectedWithInvalidPasswordReason() {
        AuthenticateWithPassword useCase = freshUseCaseWith(EXISTING_IDENTIFIER, true);

        AuthenticationDecision decision = useCase.runWithinTransaction(INSTITUTION_ID, "",
                new AuthenticationCommand(EXISTING_IDENTIFIER.value(), "incorrecta"));

        assertThat(decision.result()).isInstanceOf(Rejected.class);
        assertThat(((Rejected) decision.result()).reason()).isEqualTo(RejectionReason.INVALID_PASSWORD);
    }

    @Test
    void aNonexistentAccountProducesRejectedWithAccountNotFoundReason() {
        AuthenticateWithPassword useCase = freshUseCaseWith(NONEXISTENT_IDENTIFIER, false);

        AuthenticationDecision decision = useCase.runWithinTransaction(INSTITUTION_ID, "",
                new AuthenticationCommand(NONEXISTENT_IDENTIFIER.value(), "cualquiera"));

        assertThat(decision.result()).isInstanceOf(Rejected.class);
        assertThat(((Rejected) decision.result()).reason())
                .isEqualTo(RejectionReason.ACCOUNT_NOT_FOUND);
    }

    @Test
    void invalidPasswordAndAccountNotFoundProduceDistinctInternalReasons() {
        AuthenticateWithPassword wrongPasswordCase = freshUseCaseWith(EXISTING_IDENTIFIER, true);
        AuthenticationDecision wrongPassword = wrongPasswordCase.runWithinTransaction(INSTITUTION_ID,
                "", new AuthenticationCommand(EXISTING_IDENTIFIER.value(), "incorrecta"));

        AuthenticateWithPassword noAccountCase = freshUseCaseWith(NONEXISTENT_IDENTIFIER, false);
        AuthenticationDecision noAccount = noAccountCase.runWithinTransaction(INSTITUTION_ID, "",
                new AuthenticationCommand(NONEXISTENT_IDENTIFIER.value(), "cualquiera"));

        assertThat(((Rejected) wrongPassword.result()).reason())
                .isNotEqualTo(((Rejected) noAccount.result()).reason());
    }

    @Test
    void theDecoyVerificationRunsExactlyOnceWhenTheAccountDoesNotExist() {
        FakeStaffAccountRepository accounts = new FakeStaffAccountRepository();
        FakeLoginBackoffStore backoffStore = new FakeLoginBackoffStore();
        FakePasswordHasher passwordHasher = new FakePasswordHasher(CORRECT_PASSWORD, STORED_HASH,
                DECOY_HASH);
        FakeAuditLogWriter auditLogWriter = new FakeAuditLogWriter();
        AuthenticateWithPassword useCase = useCase(accounts, backoffStore, passwordHasher,
                auditLogWriter, fixedClock("2026-03-10T11:00:00Z"));

        useCase.runWithinTransaction(INSTITUTION_ID, "",
                new AuthenticationCommand(NONEXISTENT_IDENTIFIER.value(), "cualquiera"));

        assertThat(passwordHasher.matchesInvocations)
                .as("the decoy verification must run exactly once for a nonexistent account")
                .isEqualTo(1);
        assertThat(passwordHasher.matchedAgainst).containsExactly(DECOY_HASH);
    }

    @Test
    void aSuccessfulLoginNeverRecalculatesTheStoredHash() {
        FakeStaffAccountRepository accounts = new FakeStaffAccountRepository();
        accounts.add(accountOf(EXISTING_IDENTIFIER));
        FakePasswordHasher passwordHasher = new FakePasswordHasher(CORRECT_PASSWORD, STORED_HASH,
                DECOY_HASH);
        AuthenticateWithPassword useCase = useCase(accounts, new FakeLoginBackoffStore(),
                passwordHasher, new FakeAuditLogWriter(), fixedClock("2026-03-10T11:00:00Z"));

        AuthenticationDecision decision = useCase.runWithinTransaction(INSTITUTION_ID, "",
                new AuthenticationCommand(EXISTING_IDENTIFIER.value(), "Segura#2026"));

        assertThat(decision.result()).isInstanceOf(Authenticated.class);
        assertThat(passwordHasher.hashInvocations)
                .as("hash(...) must never be invoked on the successful-login path")
                .isZero();
        assertThat(passwordHasher.matchesInvocations)
                .as("matches(...) is the only verification call on the successful path")
                .isEqualTo(1);
    }

    @Test
    void aMismatchedInstitutionFailsLoudlyAndNeverReturnsRejected() {
        InstitutionId configured = new InstitutionId(UUID.randomUUID());
        InstitutionId requested = new InstitutionId(UUID.randomUUID());
        AuthenticateWithPassword useCase = new AuthenticateWithPassword(unusedTransactionRunner(),
                () -> configured, new FakeStaffAccountRepository(), new FakeLoginBackoffStore(),
                new FakePasswordHasher(CORRECT_PASSWORD, STORED_HASH, DECOY_HASH),
                AuthenticateWithPasswordTest::deterministicFingerprint, new FakeAuditLogWriter(),
                fixedClock("2026-03-10T11:00:00Z"));
        SecurityContext context = new SecurityContext("", ACTOR_KIND_SYSTEM,
                requested.value().toString(), UUID.randomUUID().toString());

        assertThatThrownBy(() -> useCase.execute(context,
                new AuthenticationCommand("someone@colegio.edu.hn", "whatever")))
                .as("an institution mismatch is a wiring defect, never a Rejected outcome")
                .isInstanceOf(IllegalStateException.class);
    }

    private static final String ACTOR_KIND_SYSTEM = "system";

    private AuthenticateWithPassword freshUseCaseWith(LoginIdentifier identifier,
            boolean accountExists) {
        FakeStaffAccountRepository accounts = new FakeStaffAccountRepository();
        if (accountExists) {
            accounts.add(accountOf(identifier));
        }
        FakeLoginBackoffStore backoffStore = new FakeLoginBackoffStore();
        FakePasswordHasher passwordHasher = new FakePasswordHasher(CORRECT_PASSWORD, STORED_HASH,
                DECOY_HASH);
        FakeAuditLogWriter auditLogWriter = new FakeAuditLogWriter();
        return useCase(accounts, backoffStore, passwordHasher, auditLogWriter,
                fixedClock("2026-03-10T11:00:00Z"));
    }

    private AuthenticateWithPassword useCase(FakeStaffAccountRepository accounts,
            FakeLoginBackoffStore backoffStore, FakePasswordHasher passwordHasher,
            FakeAuditLogWriter auditLogWriter, Clock clock) {
        return new AuthenticateWithPassword(unusedTransactionRunner(), () -> INSTITUTION_ID, accounts,
                backoffStore, passwordHasher, AuthenticateWithPasswordTest::deterministicFingerprint,
                auditLogWriter, clock);
    }

    private static StaffAccount accountOf(LoginIdentifier identifier) {
        return new StaffAccount(new StaffAccountId(UUID.randomUUID()), INSTITUTION_ID, identifier,
                STORED_HASH);
    }

    private static IdentifierFingerprint fingerprintOf(LoginIdentifier identifier) {
        return deterministicFingerprint(identifier);
    }

    /** SHA-256 of the identifier's own normalized value: deterministic, 64 lowercase hex chars. */
    private static IdentifierFingerprint deterministicFingerprint(LoginIdentifier identifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identifier.value().getBytes(StandardCharsets.UTF_8));
            return new IdentifierFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must always be available on the JVM", e);
        }
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    /**
     * {@link TransactionRunner} needs a real, non-null {@link PlatformTransactionManager} and
     * {@link DataSource} in its constructor, but every test above calls {@link
     * AuthenticateWithPassword#runWithinTransaction} directly, never {@link
     * AuthenticateWithPassword#execute} — so this instance is held but never exercised. Both
     * collaborators throw if a future change ever does exercise it by mistake, so a silent false
     * green is impossible.
     */
    private static TransactionRunner unusedTransactionRunner() {
        PlatformTransactionManager transactionManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                throw new UnsupportedOperationException("not exercised by this unit test");
            }

            @Override
            public void commit(TransactionStatus status) {
                throw new UnsupportedOperationException("not exercised by this unit test");
            }

            @Override
            public void rollback(TransactionStatus status) {
                throw new UnsupportedOperationException("not exercised by this unit test");
            }
        };
        return new TransactionRunner(transactionManager, new UnusedDataSource());
    }

    private static final class UnusedDataSource implements DataSource {
        @Override
        public java.sql.Connection getConnection() {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public int getLoginTimeout() {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            throw new UnsupportedOperationException("not exercised by this unit test");
        }
    }

    private static final class FakeStaffAccountRepository implements StaffAccountRepository {
        private final Map<LoginIdentifier, StaffAccount> accounts = new HashMap<>();

        void add(StaffAccount account) {
            accounts.put(account.identifier(), account);
        }

        @Override
        public Optional<StaffAccount> findBy(InstitutionId institutionId, LoginIdentifier identifier) {
            StaffAccount account = accounts.get(identifier);
            if (account == null || !account.institutionId().equals(institutionId)) {
                return Optional.empty();
            }
            return Optional.of(account);
        }
    }

    private static final class FakeLoginBackoffStore implements LoginBackoffStore {
        private final Map<IdentifierFingerprint, BackoffState> states = new HashMap<>();
        private final Map<IdentifierFingerprint, BackoffState> savedStates = new HashMap<>();

        void seed(IdentifierFingerprint fingerprint, BackoffState state) {
            states.put(fingerprint, state);
        }

        @Override
        public BackoffState claim(InstitutionId institutionId, IdentifierFingerprint fingerprint,
                Instant now) {
            return states.getOrDefault(fingerprint, BackoffState.initial(now));
        }

        @Override
        public void save(InstitutionId institutionId, IdentifierFingerprint fingerprint,
                BackoffState state) {
            states.put(fingerprint, state);
            savedStates.put(fingerprint, state);
        }
    }

    private static final class FakePasswordHasher implements PasswordHasher {
        private final PlainPassword correctPassword;
        private final StoredPasswordHash accountHash;
        private final StoredPasswordHash decoy;
        private int matchesInvocations;
        private int hashInvocations;
        private final List<StoredPasswordHash> matchedAgainst = new ArrayList<>();

        FakePasswordHasher(PlainPassword correctPassword, StoredPasswordHash accountHash,
                StoredPasswordHash decoy) {
            this.correctPassword = correctPassword;
            this.accountHash = accountHash;
            this.decoy = decoy;
        }

        @Override
        public boolean matches(PlainPassword password, StoredPasswordHash hash) {
            matchesInvocations++;
            matchedAgainst.add(hash);
            return hash.equals(accountHash) && password.equals(correctPassword);
        }

        @Override
        public StoredPasswordHash hash(PlainPassword password) {
            hashInvocations++;
            return accountHash;
        }

        @Override
        public StoredPasswordHash decoyHash() {
            return decoy;
        }
    }

    private static final class FakeAuditLogWriter implements AuditLogWriter {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public void append(AuditEntry entry) {
            entries.add(entry);
        }
    }
}

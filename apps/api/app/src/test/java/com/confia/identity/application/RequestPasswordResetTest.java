package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditEntry;
import com.confia.shared.security.SecurityContext;
import com.confia.shared.security.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * {@link RequestPasswordReset} with test doubles (password-recovery-token design.md decisions 6
 * and 9). The real transaction and the real adapters are {@code RequestPasswordResetIT}'s; this
 * class covers what needs no database: the institution guard of part 1's decision 11, and a result
 * that has nothing in it to differ between an existing and a missing account.
 */
class RequestPasswordResetTest {

    private static final InstitutionId CONFIGURED = new InstitutionId(UUID.randomUUID());

    @Test
    void anInstitutionMismatchIsAWiringDefectThatTouchesNoPort() {
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingAccounts accounts = new RecordingAccounts();
        List<AuditEntry> audit = new ArrayList<>();
        RequestPasswordReset useCase = new RequestPasswordReset(neverConnectingRunner(),
                () -> CONFIGURED, accounts, RequestPasswordResetTest::fingerprint, scheduler,
                audit::add);
        SecurityContext otherInstitution = new SecurityContext("", "system",
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        assertThatThrownBy(() -> useCase.execute(otherInstitution,
                new RequestPasswordResetCommand("ana.martinez@colegio.edu.hn")))
                .as("a mismatched institution is a programming error, never a uniform result")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("ana.martinez");
        assertThat(scheduler.calls).isEmpty();
        assertThat(accounts.lookups).isZero();
        assertThat(audit).isEmpty();
    }

    /** Scenario "El resultado uniforme es del caso de uso, no una respuesta HTTP" (I30). */
    @Test
    void theDecisionHasNoComponentsSoNothingInItCanDifferOrCarryAToken() {
        assertThat(RequestPasswordResetDecision.class.isRecord()).isTrue();
        assertThat(RequestPasswordResetDecision.class.getRecordComponents()).isEmpty();
        assertThat(new RequestPasswordResetDecision()).isEqualTo(new RequestPasswordResetDecision());
    }

    @Test
    void anExistingAccountIsScheduledByIdAndAMissingOneIsNotWithOneAuditEntryEach() {
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingAccounts accounts = new RecordingAccounts();
        StaffAccountId existingId = new StaffAccountId(UUID.randomUUID());
        accounts.existing = new StaffAccount(existingId, CONFIGURED,
                LoginIdentifier.of("ana.martinez@colegio.edu.hn"), new StoredPasswordHash(
                        "$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA"), false);
        List<AuditEntry> audit = new ArrayList<>();
        RequestPasswordReset useCase = new RequestPasswordReset(neverConnectingRunner(),
                () -> CONFIGURED, accounts, RequestPasswordResetTest::fingerprint, scheduler,
                audit::add);

        RequestPasswordResetDecision forExisting = useCase.runWithinTransaction(CONFIGURED, "",
                new RequestPasswordResetCommand("Ana.Martinez@Colegio.edu.hn"));
        RequestPasswordResetDecision forMissing = useCase.runWithinTransaction(CONFIGURED, "",
                new RequestPasswordResetCommand("nadie.registrado@colegio.edu.hn"));

        assertThat(forExisting).isEqualTo(forMissing);
        assertThat(scheduler.calls).containsExactly(new ScheduledIssuance(CONFIGURED, existingId));
        assertThat(audit).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.action()).isEqualTo("identity.password_reset.requested");
            assertThat(entry.entityType()).isEqualTo("identity.staff_account");
            assertThat(entry.outcome()).isEqualTo("success");
        });
        assertThat(audit.get(0).entityId())
                .isEqualTo(fingerprint(LoginIdentifier.of("ana.martinez@colegio.edu.hn")).value());
        assertThat(audit.get(0).afterValue()).isEqualTo("{\"outcome\":\"issuance-scheduled\"}");
        assertThat(audit.get(0).actorId()).isEqualTo(existingId.value());
        assertThat(audit.get(1).afterValue()).isEqualTo("{\"outcome\":\"account-not-found\"}");
        assertThat(audit.get(1).actorLabel()).isEqualTo("unknown-account");
        assertThat(audit.get(1).actorId()).isNull();
        assertThat(audit.get(1).toString()).doesNotContain("nadie.registrado");
    }

    @Test
    void aCommandNeverCarriesAnInstitution() {
        assertThat(RequestPasswordResetCommand.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("presentedIdentifier");
    }

    /** A 64-hex stand-in for the keyed fingerprint: an unkeyed SHA-256, only ever in tests. */
    private static IdentifierFingerprint fingerprint(LoginIdentifier identifier) {
        try {
            return new IdentifierFingerprint(HexFormat.of().formatHex(MessageDigest
                    .getInstance("SHA-256")
                    .digest(identifier.value().getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** No URL: any attempt to open a transaction fails, and never with IllegalStateException. */
    private static TransactionRunner neverConnectingRunner() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        return new TransactionRunner(new DataSourceTransactionManager(dataSource), dataSource);
    }

    record ScheduledIssuance(InstitutionId institutionId, StaffAccountId accountId) {
    }

    static final class RecordingScheduler implements PasswordResetIssuanceScheduler {
        final List<ScheduledIssuance> calls = new ArrayList<>();

        @Override
        public void schedule(InstitutionId institutionId, StaffAccountId accountId) {
            calls.add(new ScheduledIssuance(institutionId, accountId));
        }
    }

    private static final class RecordingAccounts implements StaffAccountRepository {
        StaffAccount existing;
        int lookups;

        @Override
        public Optional<StaffAccount> findBy(InstitutionId institutionId,
                LoginIdentifier identifier) {
            lookups++;
            return Optional.ofNullable(existing)
                    .filter(account -> account.identifier().equals(identifier));
        }

        @Override
        public Optional<StaffAccount> lockById(InstitutionId institutionId,
                StaffAccountId accountId) {
            throw new UnsupportedOperationException("a request never locks an account");
        }

        @Override
        public boolean replacePasswordHash(InstitutionId institutionId, StaffAccountId accountId,
                StoredPasswordHash newHash) {
            throw new UnsupportedOperationException("a request never changes a password");
        }
    }
}

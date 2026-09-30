package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.RequestPasswordResetTest.RecordingScheduler;
import com.confia.identity.application.RequestPasswordResetTest.ScheduledIssuance;
import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link RequestPasswordReset} through the real transaction, against a real PostgreSQL and the real
 * fingerprint, audit and account adapters (password-recovery-token design.md decisions 6 and 9;
 * tasks.md task 3.1). The scheduling port has no production adapter until change 9, so a double
 * that only records the call stands in for it: a request can never produce a token by itself.
 */
class RequestPasswordResetIT extends CommittingPostgresIntegrationTest {

    private static final String EXISTING_EMAIL = "ana.martinez@colegio.edu.hn";
    private static final String NONEXISTENT_EMAIL = "nadie.registrado@colegio.edu.hn";
    private static final String CURRENT_PASSWORD = "Cafetal de Copán 2026";
    private static final String ACTION_REQUESTED = "identity.password_reset.requested";

    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
    private static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    private static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);

    private final InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
    private final RecordingScheduler scheduler = new RecordingScheduler();

    /** Scenarios I1 and I39: scheduled by internal id, no token, one audit entry. */
    @Test
    void anExistingAccountIsScheduledByItsInternalIdWithNoTokenAndOneAuditEntry() {
        StaffAccountId accountId = seedStaffAccount(EXISTING_EMAIL);

        RequestPasswordResetDecision decision = request(EXISTING_EMAIL);

        assertThat(scheduler.calls).containsExactly(new ScheduledIssuance(institutionId, accountId));
        assertThat(scheduler.calls.get(0).toString()).doesNotContain(EXISTING_EMAIL);
        assertThat(tokenCount()).isZero();
        assertThat(decision).isEqualTo(new RequestPasswordResetDecision());
        List<AuditRowSnapshot> rows = auditRowsFor(EXISTING_EMAIL);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(ACTION_REQUESTED);
            assertThat(row.entityId()).isEqualTo(fingerprintOf(EXISTING_EMAIL).value());
        });
    }

    /** Scenario I2: the request neither locks the account nor touches its password. */
    @Test
    void theHolderStillLogsInWithTheCurrentPasswordAfterARequest() {
        seedStaffAccount(EXISTING_EMAIL);
        request(EXISTING_EMAIL);

        AuthenticationDecision login = new AuthenticateWithPassword(transactionRunner(),
                () -> institutionId, new JooqStaffAccountRepository(dsl),
                new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), HASHER,
                FINGERPRINTER, new JooqAuditLogWriter(dsl),
                Clock.fixed(Instant.parse("2026-10-12T10:01:00Z"), ZoneOffset.UTC))
                .execute(context(), new AuthenticationCommand(EXISTING_EMAIL, CURRENT_PASSWORD));

        assertThat(login.result()).isInstanceOf(Authenticated.class);
    }

    /** Scenario I40: the same result, nothing scheduled, one entry without the address. */
    @Test
    void aNonexistentAddressGetsTheSameResultNothingScheduledAndOneEntryWithoutTheAddress() {
        seedStaffAccount(EXISTING_EMAIL);
        RequestPasswordResetDecision forExisting = request(EXISTING_EMAIL);

        RequestPasswordResetDecision forMissing = request(NONEXISTENT_EMAIL);

        assertThat(forMissing).isEqualTo(forExisting);
        assertThat(scheduler.calls).hasSize(1);
        List<AuditRowSnapshot> rows = auditRowsFor(NONEXISTENT_EMAIL);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(ACTION_REQUESTED);
            assertThat(row.entityId()).isEqualTo(fingerprintOf(NONEXISTENT_EMAIL).value());
            assertThat(row.toString())
                    .as("an address with no account never appears in clear in any column")
                    .doesNotContain(NONEXISTENT_EMAIL)
                    .doesNotContain("nadie.registrado");
        });
        assertThat(auditRowsFor(EXISTING_EMAIL)).hasSameSizeAs(rows)
                .allSatisfy(row -> assertThat(row.action()).isEqualTo(ACTION_REQUESTED));
    }

    /** Scenario I29: no per-IP limit exists yet, so eleven requests are all scheduled. */
    @Test
    void elevenRequestsForElevenAccountsAreAllScheduled() {
        List<StaffAccountId> accounts = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            accounts.add(seedStaffAccount("docente" + i + "@colegio.edu.hn"));
        }

        for (int i = 0; i < 11; i++) {
            request("docente" + i + "@colegio.edu.hn");
        }

        assertThat(scheduler.calls).extracting(ScheduledIssuance::accountId)
                .containsExactlyElementsOf(accounts);
    }

    /** Scenario I26: without an explicit issuance, a request never produces a token. */
    @Test
    void aRequestNeverProducesATokenByItself() {
        seedStaffAccount(EXISTING_EMAIL);

        request(EXISTING_EMAIL);
        request(EXISTING_EMAIL);

        assertThat(scheduler.calls).hasSize(2);
        assertThat(tokenCount()).isZero();
    }

    private RequestPasswordResetDecision request(String presentedIdentifier) {
        return new RequestPasswordReset(transactionRunner(), () -> institutionId,
                new JooqStaffAccountRepository(dsl), FINGERPRINTER, scheduler,
                new JooqAuditLogWriter(dsl))
                .execute(context(), new RequestPasswordResetCommand(presentedIdentifier));
    }

    private StaffAccountId seedStaffAccount(String email) {
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        String hash = HASHER.hash(PlainPassword.of(CURRENT_PASSWORD)).value();
        transactionRunner().execute(context(), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), accountId.value(),
                    LoginIdentifier.of(email).value(), hash);
            return null;
        });
        return accountId;
    }

    private long tokenCount() {
        return transactionRunner().execute(context(), () -> dsl.fetchOne(
                "select count(*) as c from identity_password_reset_token where institution_id = ?",
                institutionId.value()).get("c", Number.class).longValue());
    }

    private List<AuditRowSnapshot> auditRowsFor(String email) {
        String fingerprint = fingerprintOf(email).value();
        return transactionRunner().execute(context(),
                () -> new JooqAuditLogReader(dsl).pageOf(institutionId, 0, 100)).stream()
                .filter(row -> fingerprint.equals(row.entityId()))
                .toList();
    }

    private static IdentifierFingerprint fingerprintOf(String email) {
        return FINGERPRINTER.fingerprintOf(LoginIdentifier.of(email));
    }

    private SecurityContext context() {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

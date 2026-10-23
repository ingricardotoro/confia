package com.confia.identity.web;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqTotpCredentialRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the integration tests of the login route share: a pepper generated for the run, accounts
 * seeded with the hash the process will check, and the reads of what a login left in the database.
 * Abstract, so the {@code *IT} naming rule does not apply to it.
 */
abstract class LoginEndpointSupport extends CommittingPostgresIntegrationTest {

    private long auditBaseline;

    static final String PASSWORD = "Cafetal de Copán 2026";
    static final String DUMMY_ENCRYPTED_TOTP_SECRET =
            "v1:00000000-0000-0000-0000-000000000000:aXY=:Y2lwaGVy:dGFn";
    static final byte[] PEPPER_BYTES = LoginProcess.newPepperBytes();
    static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(PEPPER_BYTES));
    static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** The institution the process under test is configured with. */
    abstract InstitutionId institution();

    static SecurityContext contextOf(InstitutionId of) {
        return new SecurityContext("", "system", of.value().toString(), UUID.randomUUID().toString());
    }

    StaffAccountId seedAccount(String email, boolean mfaRequired, boolean secretEnrolled) {
        return seedAccountIn(institution(), email, PASSWORD, mfaRequired, secretEnrolled);
    }

    StaffAccountId seedAccountIn(InstitutionId of, String email, String password,
            boolean mfaRequired, boolean secretEnrolled) {
        StaffAccountId account = new StaffAccountId(UUID.randomUUID());
        String hash = HASHER.hash(PlainPassword.of(password)).value();
        transactionRunner().execute(contextOf(of), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, ?)
                    """, of.value(), account.value(), LoginIdentifier.of(email).value(), hash,
                    mfaRequired);
            if (secretEnrolled) {
                new JooqTotpCredentialRepository(dsl).insert(of, account, DUMMY_ENCRYPTED_TOTP_SECRET);
            }
            return null;
        });
        return account;
    }

    long count(String table) {
        return transactionRunner().execute(contextOf(institution()), () -> dsl.fetchSingle(
                "select count(*) from " + table + " where institution_id = ?",
                institution().value()).get(0, Long.class));
    }

    /**
     * The entries this test wrote, and only those. The audit trail is append-only, so the tables a
     * test class shares through the process (one institution for the whole class) keep the entries of
     * the earlier tests; the identifier of the last one is read before each test and everything
     * after it is this test's.
     */
    List<AuditRowSnapshot> auditRows() {
        return transactionRunner().execute(contextOf(institution()),
                () -> new JooqAuditLogReader(dsl).pageOf(institution(), auditBaseline, 5000));
    }

    @BeforeEach
    void rememberTheLastEntryOfTheTrail() {
        List<AuditRowSnapshot> all = transactionRunner().execute(contextOf(institution()),
                () -> new JooqAuditLogReader(dsl).pageOf(institution(), 0, 100_000));
        auditBaseline = all.isEmpty() ? 0 : all.get(all.size() - 1).id();
    }

    List<AuditRowSnapshot> auditRows(String action) {
        return auditRows().stream().filter(row -> row.action().equals(action)).toList();
    }

    /** The largest {@code consecutive_failures} of any login backoff row, or zero without rows. */
    long highestConsecutiveFailures() {
        return transactionRunner().execute(contextOf(institution()), () -> dsl.fetchSingle("""
                select coalesce(max(consecutive_failures), 0) from identity_login_backoff
                 where institution_id = ?
                """, institution().value()).get(0, Long.class));
    }

    IdentifierFingerprint fingerprintOf(String identifier) {
        return FINGERPRINTER.fingerprintOf(LoginIdentifier.of(identifier));
    }

    static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    static String everythingOf(HttpResponse<String> response) {
        return response.body() + " " + response.headers().map();
    }
}

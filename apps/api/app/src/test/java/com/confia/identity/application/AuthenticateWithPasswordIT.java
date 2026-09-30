package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.AuthenticationResult.Authenticated;
import com.confia.identity.domain.AuthenticationResult.Rejected;
import com.confia.identity.domain.AuthenticationResult.RejectionReason;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AuthenticateWithPassword} end to end, against a real PostgreSQL and the real Argon2id
 * mechanism, with the three real jOOQ/configuration adapters this cut delivers (design.md, §10
 * sondas S3/S4; §11 paso 16). {@code LoginBackoffAtomicityIT} and {@code LoginBackoffConcurrencyIT}
 * (task 4.2) take the atomicity and concurrency half of decision 9 further; {@code
 * LoginInstitutionIT} (task 4.3) takes the institution-resolution half further. This class is the
 * seven backoff scenarios of specs/identity/spec.md's modified requirement, run through the real
 * transaction, never through {@code runWithinTransaction} directly.
 *
 * <p><b>Never sleeps</b> (design.md §7.2): every scenario constructs its own {@link
 * AuthenticateWithPassword} with a fresh {@link Clock#fixed}, one per attempt, and asserts the
 * {@link Duration} the use case computes and returns — never a wait it lived through.
 */
class AuthenticateWithPasswordIT extends CommittingPostgresIntegrationTest {

    private static final String EXISTING_EMAIL = "carlos.ramirez@colegio.edu.hn";
    private static final String NONEXISTENT_EMAIL = "nadie.registrado@colegio.edu.hn";
    private static final String CORRECT_PASSWORD_RAW = "Correcta#2026-C3b";
    private static final String WRONG_PASSWORD_RAW = "incorrecta";

    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
    private static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    private static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @Test
    void delayAppliesFromTheThirdConsecutiveFailureAndTheCycleIsAuditedWithItsDuration() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of(EXISTING_EMAIL);
        seedStaffAccount(institutionId, identifier);

        AuthenticationDecision first = attempt(institutionId, identifier.value(),
                WRONG_PASSWORD_RAW, "2026-03-10T08:00:00Z");
        AuthenticationDecision second = attempt(institutionId, identifier.value(),
                WRONG_PASSWORD_RAW, "2026-03-10T08:00:20Z");
        AuthenticationDecision third = attempt(institutionId, identifier.value(),
                WRONG_PASSWORD_RAW, "2026-03-10T08:00:40Z");

        assertThat(first.requiredDelay()).isEqualTo(Duration.ZERO);
        assertThat(second.requiredDelay()).isEqualTo(Duration.ZERO);
        assertThat(third.requiredDelay())
                .as("the third consecutive failure is where docs/03-seguridad.md §4.4 starts "
                        + "delaying: 2^(3-3) = 1 second")
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(third.result()).isInstanceOf(Rejected.class);
        assertThat(((Rejected) third.result()).reason()).isEqualTo(RejectionReason.INVALID_PASSWORD);

        List<AuditRowSnapshot> auditRows = auditRowsFor(institutionId, fingerprintOf(identifier));
        assertThat(auditRows)
                .as("three failed attempts, the third one over the delay threshold: three "
                        + "login.failed rows plus exactly one backoff_applied row")
                .hasSize(4);
        AuditRowSnapshot cycleRow = auditRows.stream()
                .filter(row -> row.action().equals("identity.login.backoff_applied"))
                .findFirst()
                .orElseThrow();
        JsonNode afterValue = readTree(cycleRow.afterValue());
        assertThat(afterValue.get("delaySeconds").asInt()).isEqualTo(1);
        assertThat(afterValue.get("consecutiveFailures").asInt()).isEqualTo(3);
    }

    @Test
    void progressionCapsAtNineHundredSecondsInsteadOfTheUncappedOneThousandTwentyFour() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of(EXISTING_EMAIL);
        seedStaffAccount(institutionId, identifier);
        seedBackoff(institutionId, fingerprintOf(identifier), 12, Instant.parse("2026-03-10T08:00:00Z"));

        AuthenticationDecision thirteenth = attempt(institutionId, identifier.value(),
                WRONG_PASSWORD_RAW, "2026-03-10T08:15:00Z");

        assertThat(thirteenth.requiredDelay())
                .as("the uncapped progression would be 2^(13-3) = 1024 seconds; the cap is 900")
                .isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    void theCounterExpiresThirtyMinutesAfterTheLastAttemptWithNoDelayOnTheFreshCycle() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of(EXISTING_EMAIL);
        seedStaffAccount(institutionId, identifier);
        seedBackoff(institutionId, fingerprintOf(identifier), 3, Instant.parse("2026-03-10T09:00:00Z"));

        AuthenticationDecision afterExpiry = attempt(institutionId, identifier.value(),
                WRONG_PASSWORD_RAW, "2026-03-10T09:31:00Z");

        assertThat(afterExpiry.requiredDelay())
                .as("31 minutes with no attempt: the cycle expired, this is ordinal 1 of a fresh one")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void aSuccessfulLoginClearsTheCounterAndTheNextFailureStartsAFreshCycle() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of(EXISTING_EMAIL);
        seedStaffAccount(institutionId, identifier);
        seedBackoff(institutionId, fingerprintOf(identifier), 2, Instant.parse("2026-03-10T10:00:00Z"));

        AuthenticationDecision success = attempt(institutionId, identifier.value(),
                CORRECT_PASSWORD_RAW, "2026-03-10T10:05:00Z");
        assertThat(success.result()).isInstanceOf(Authenticated.class);

        AuthenticationDecision nextFailure = attempt(institutionId, identifier.value(),
                WRONG_PASSWORD_RAW, "2026-03-10T10:06:00Z");
        assertThat(nextFailure.requiredDelay())
                .as("the counter is clean after the successful login: the very next failure is "
                        + "ordinal 1, not ordinal 3")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void delayAppliesEquallyToANonexistentIdentifierWithTheSameAuditEntryCount() {
        InstitutionId institutionForAccount = new InstitutionId(UUID.randomUUID());
        LoginIdentifier existing = LoginIdentifier.of(EXISTING_EMAIL);
        seedStaffAccount(institutionForAccount, existing);
        attempt(institutionForAccount, existing.value(), WRONG_PASSWORD_RAW, "2026-03-10T11:00:00Z");
        attempt(institutionForAccount, existing.value(), WRONG_PASSWORD_RAW, "2026-03-10T11:00:20Z");
        AuthenticationDecision thirdForExistingAccount = attempt(institutionForAccount,
                existing.value(), WRONG_PASSWORD_RAW, "2026-03-10T11:00:40Z");
        List<AuditRowSnapshot> auditRowsForExisting =
                auditRowsFor(institutionForAccount, fingerprintOf(existing));

        InstitutionId institutionForNoAccount = new InstitutionId(UUID.randomUUID());
        LoginIdentifier nonexistent = LoginIdentifier.of(NONEXISTENT_EMAIL);
        attempt(institutionForNoAccount, nonexistent.value(), WRONG_PASSWORD_RAW,
                "2026-03-10T11:00:00Z");
        attempt(institutionForNoAccount, nonexistent.value(), WRONG_PASSWORD_RAW,
                "2026-03-10T11:00:20Z");
        AuthenticationDecision thirdForNoAccount = attempt(institutionForNoAccount,
                nonexistent.value(), WRONG_PASSWORD_RAW, "2026-03-10T11:00:40Z");
        List<AuditRowSnapshot> auditRowsForNoAccount =
                auditRowsFor(institutionForNoAccount, fingerprintOf(nonexistent));

        assertThat(thirdForNoAccount.requiredDelay())
                .as("the delay is calculated on the presented identifier, identically whether or "
                        + "not it corresponds to an account")
                .isEqualTo(thirdForExistingAccount.requiredDelay())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(thirdForNoAccount.result()).isInstanceOf(Rejected.class);
        assertThat(((Rejected) thirdForNoAccount.result()).reason())
                .isEqualTo(RejectionReason.ACCOUNT_NOT_FOUND);
        assertThat(((Rejected) thirdForNoAccount.result()).reason())
                .as("the internal reason distinguishes the two causes, even though the delay and "
                        + "the audit row count do not")
                .isNotEqualTo(((Rejected) thirdForExistingAccount.result()).reason());
        assertThat(auditRowsForNoAccount)
                .as("same code path, same number of writes, same number of audit entries — the "
                        + "structural half of uniformity (design.md, decision 3)")
                .hasSameSizeAs(auditRowsForExisting)
                .hasSize(4);
    }

    @Test
    void aCorrectPasswordDuringBackoffSucceedsAfterTheDelayAndClearsTheCounter() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of(EXISTING_EMAIL);
        seedStaffAccount(institutionId, identifier);
        seedBackoff(institutionId, fingerprintOf(identifier), 3, Instant.parse("2026-03-10T12:00:00Z"));

        AuthenticationDecision fourth = attempt(institutionId, identifier.value(),
                CORRECT_PASSWORD_RAW, "2026-03-10T12:00:10Z");

        assertThat(fourth.result())
                .as("the backoff delays the response and does NOT deny it: correct credentials "
                        + "mid-backoff still authenticate (design.md §4's own worked example)")
                .isInstanceOf(Authenticated.class);
        assertThat(fourth.requiredDelay()).isEqualTo(Duration.ofSeconds(2));

        AuthenticationDecision nextFailure = attempt(institutionId, identifier.value(),
                WRONG_PASSWORD_RAW, "2026-03-10T12:00:20Z");
        assertThat(nextFailure.requiredDelay())
                .as("the successful login above must have cleared the counter")
                .isEqualTo(Duration.ZERO);
    }

    private AuthenticationDecision attempt(InstitutionId institutionId, String presentedIdentifier,
            String presentedPassword, String instant) {
        AuthenticateWithPassword useCase = new AuthenticateWithPassword(transactionRunner(),
                () -> institutionId, new JooqStaffAccountRepository(dsl),
                new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), HASHER,
                FINGERPRINTER, new JooqAuditLogWriter(dsl), fixedClock(instant));
        SecurityContext context = new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
        return useCase.execute(context, new AuthenticationCommand(presentedIdentifier, presentedPassword));
    }

    private void seedStaffAccount(InstitutionId institutionId, LoginIdentifier identifier) {
        StoredPasswordHash hash = HASHER.hash(PlainPassword.of(CORRECT_PASSWORD_RAW));
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), UUID.randomUUID(), identifier.value(), hash.value());
            return null;
        });
    }

    private void seedBackoff(InstitutionId institutionId, IdentifierFingerprint fingerprint,
            int consecutiveFailures, Instant lastAttemptAt) {
        JooqLoginBackoffStore store = new JooqLoginBackoffStore(dsl);
        transactionRunner().execute(contextOf(institutionId), () -> {
            store.claim(institutionId, fingerprint, lastAttemptAt);
            store.save(institutionId, fingerprint, new BackoffState(consecutiveFailures, lastAttemptAt));
            return null;
        });
    }

    private List<AuditRowSnapshot> auditRowsFor(InstitutionId institutionId,
            IdentifierFingerprint fingerprint) {
        AuditLogReader reader = new JooqAuditLogReader(dsl);
        return transactionRunner()
                .execute(contextOf(institutionId), () -> reader.pageOf(institutionId, 0, 100))
                .stream()
                .filter(row -> row.entityId().equals(fingerprint.value()))
                .toList();
    }

    private static IdentifierFingerprint fingerprintOf(LoginIdentifier identifier) {
        return FINGERPRINTER.fingerprintOf(identifier);
    }

    /**
     * {@code afterValue} round-trips through PostgreSQL's {@code jsonb}, which re-serializes with
     * its own key order and spacing (observed: {@code {"delaySeconds": 1, "consecutiveFailures":
     * 3}}, not the literal text {@link AuthenticateWithPassword} wrote) — parsing structurally,
     * rather than asserting on a literal substring, is what makes this assertion resilient to that.
     */
    private static JsonNode readTree(String json) {
        return JSON_MAPPER.readTree(json);
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

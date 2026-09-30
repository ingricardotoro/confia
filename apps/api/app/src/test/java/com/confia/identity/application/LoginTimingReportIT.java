package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Measures and reports — never gates — the timing difference between an existing-account attempt
 * and a nonexistent-identifier attempt (design.md §7.2, "La uniformidad de tiempo se mide y se
 * reporta"; docs/03-seguridad.md §4.6). The real gate over HTTP responses belongs to {@code
 * session-tokens-and-web-layer}, not this change: this class's own sample is 25 plus 25, not
 * §4.6's 200 plus 200, because each attempt pays a real Argon2id computation of hundreds of
 * milliseconds, and 400 of them would risk this suite's own 8-minute budget (design.md §13) for a
 * measurement that is reported, never enforced.
 *
 * <p>The mechanism itself — that a verification runs against the decoy hash when the account does
 * not exist, with the same current parameters as a real one — is proven deterministically by
 * interaction elsewhere (the decoy-invocation-count test of PR C2, and {@code
 * AuthenticateWithPasswordIT}'s own uniform audit-entry-count scenario). This class only measures
 * wall-clock time, and only to report it.
 */
class LoginTimingReportIT extends CommittingPostgresIntegrationTest {

    private static final int SAMPLE_SIZE = 25;
    private static final String EXISTING_EMAIL = "carlos.ramirez@colegio.edu.hn";
    private static final String NONEXISTENT_EMAIL = "nadie.registrado@colegio.edu.hn";

    @Test
    void measuresAndReportsTheMedianTimingDifferenceWithoutGatingOnIt(TestReporter reporter) {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        Argon2Pepper pepper = Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
        BouncyCastleArgon2PasswordHasher hasher =
                new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), pepper);
        HmacLoginIdentifierFingerprinter fingerprinter = new HmacLoginIdentifierFingerprinter(pepper);
        LoginIdentifier existingIdentifier = LoginIdentifier.of(EXISTING_EMAIL);
        StoredPasswordHash hash = hasher.hash(PlainPassword.of("Correcta#2026-Timing"));
        seedStaffAccount(institutionId, existingIdentifier, hash);

        AuthenticateWithPassword useCase = new AuthenticateWithPassword(transactionRunner(),
                () -> institutionId, new JooqStaffAccountRepository(dsl),
                new JooqTotpCredentialRepository(dsl), new JooqLoginBackoffStore(dsl), hasher,
                fingerprinter, new JooqAuditLogWriter(dsl), Clock.systemUTC());
        SecurityContext context = contextOf(institutionId);

        List<Long> existingAccountNanos = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long start = System.nanoTime();
            useCase.execute(context, new AuthenticationCommand(existingIdentifier.value(),
                    "intento-incorrecto-" + i));
            existingAccountNanos.add(System.nanoTime() - start);
        }

        List<Long> nonexistentNanos = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long start = System.nanoTime();
            useCase.execute(context, new AuthenticationCommand(NONEXISTENT_EMAIL,
                    "cualquiera-" + i));
            nonexistentNanos.add(System.nanoTime() - start);
        }

        double medianExistingMs = medianMillis(existingAccountNanos);
        double medianNonexistentMs = medianMillis(nonexistentNanos);
        double differenceMs = Math.abs(medianExistingMs - medianNonexistentMs);

        // JUnit's own reporting channel, not System.out. docs/03-seguridad.md section 6 forbids
        // System.out, System.err and printStackTrace in apps/api without scoping the prohibition to
        // production code, and NoStandardStreamAccessTest only checks productionClasses(), so the
        // automated gate would not have caught this one. The pre-merge security audit did, and it
        // was right to: today the line prints only durations, but the precedent it would set is
        // that a measuring test may reach for System.out, and the next measurement might not be
        // as harmless.
        reporter.publishEntry("loginTimingReport", String.format(
                "informational, never a gate: median existing-account attempt = %.2f ms, "
                        + "median nonexistent-identifier attempt = %.2f ms, difference = %.2f ms, "
                        + "n = %d each",
                medianExistingMs, medianNonexistentMs, differenceMs, SAMPLE_SIZE));

        // The mechanism is proven elsewhere (see this class's own Javadoc); these two assertions
        // only confirm the sample itself is real, never that the difference stays under any bound
        // — turning this into a gate is explicitly out of scope here (design.md, decision 7).
        assertThat(existingAccountNanos).hasSize(SAMPLE_SIZE);
        assertThat(nonexistentNanos).hasSize(SAMPLE_SIZE);
    }

    private static double medianMillis(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        long medianNanos = sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) / 2
                : sorted.get(middle);
        return medianNanos / 1_000_000.0;
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

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

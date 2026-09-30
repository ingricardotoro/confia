package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Measures and reports, never gates, the timing difference between a password-reset request for an
 * existing account and one for an address with no account (password-recovery-token tasks.md task
 * 3.1; docs/03-seguridad.md §4.6), the same informational role {@code LoginTimingReportIT} has for
 * login. The gate over HTTP responses belongs to {@code session-tokens-and-web-layer}. Both paths do
 * the same work by construction, a fingerprint, one lookup and one audit entry; the only difference
 * is the recorded call to the scheduling double.
 */
class PasswordResetRequestTimingReportIT extends CommittingPostgresIntegrationTest {

    private static final int SAMPLE_SIZE = 25;
    private static final String EXISTING_EMAIL = "carlos.ramirez@colegio.edu.hn";
    private static final String NONEXISTENT_EMAIL = "nadie.registrado@colegio.edu.hn";

    @Test
    void measuresAndReportsTheMedianTimingDifferenceWithoutGatingOnIt(TestReporter reporter) {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        SecurityContext context = new SecurityContext("", "system",
                institutionId.value().toString(), UUID.randomUUID().toString());
        transactionRunner().execute(context, () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, '$argon2id$v=19$m=19456,t=3,p=1$c2FsdA$aGFzaA', false)
                    """, institutionId.value(), UUID.randomUUID(),
                    LoginIdentifier.of(EXISTING_EMAIL).value());
            return null;
        });
        RequestPasswordReset useCase = new RequestPasswordReset(transactionRunner(),
                () -> institutionId, new JooqStaffAccountRepository(dsl),
                new HmacLoginIdentifierFingerprinter(Argon2Pepper.fromBase64(
                        Base64.getEncoder().encodeToString(new byte[32]))),
                (institution, account) -> { }, new JooqAuditLogWriter(dsl));

        List<Long> existingNanos = measure(useCase, context, EXISTING_EMAIL);
        List<Long> nonexistentNanos = measure(useCase, context, NONEXISTENT_EMAIL);

        double medianExistingMs = medianMillis(existingNanos);
        double medianNonexistentMs = medianMillis(nonexistentNanos);
        reporter.publishEntry("passwordResetRequestTimingReport", String.format(
                "informational, never a gate: median existing-account request = %.2f ms, "
                        + "median nonexistent-address request = %.2f ms, difference = %.2f ms, "
                        + "n = %d each",
                medianExistingMs, medianNonexistentMs,
                Math.abs(medianExistingMs - medianNonexistentMs), SAMPLE_SIZE));

        assertThat(existingNanos).hasSize(SAMPLE_SIZE);
        assertThat(nonexistentNanos).hasSize(SAMPLE_SIZE);
    }

    private static List<Long> measure(RequestPasswordReset useCase, SecurityContext context,
            String email) {
        List<Long> nanos = new ArrayList<>(SAMPLE_SIZE);
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            long start = System.nanoTime();
            useCase.execute(context, new RequestPasswordResetCommand(email));
            nanos.add(System.nanoTime() - start);
        }
        return nanos;
    }

    private static double medianMillis(List<Long> nanos) {
        List<Long> sorted = nanos.stream().sorted().toList();
        return sorted.get(sorted.size() / 2) / 1_000_000.0;
    }
}

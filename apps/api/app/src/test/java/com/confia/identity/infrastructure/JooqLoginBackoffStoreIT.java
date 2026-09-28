package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqLoginBackoffStore} against a real PostgreSQL (design.md, decision 5; §10, sonda S3;
 * §11 paso 16). {@code LoginBackoffAtomicityIT} and {@code LoginBackoffConcurrencyIT} (task 4.2)
 * exercise the same reclaim statement under a deterministic rollback and real concurrency; this
 * class proves the adapter's own single-session, single-transaction shape first.
 */
class JooqLoginBackoffStoreIT extends CommittingPostgresIntegrationTest {

    private JooqLoginBackoffStore store() {
        return new JooqLoginBackoffStore(dsl);
    }

    @Test
    void claimCreatesAndReturnsTheInitialStateForAFreshFingerprint() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdentifierFingerprint fingerprint = someFingerprint();
        Instant now = Instant.parse("2026-03-10T12:00:00Z");

        BackoffState claimed = transactionRunner().execute(contextOf(institutionId),
                () -> store().claim(institutionId, fingerprint, now));

        assertThat(claimed).isEqualTo(BackoffState.initial(now));
    }

    @Test
    void claimReturnsThePriorStateNeverTheRowsOwnProposedValues() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdentifierFingerprint fingerprint = someFingerprint();
        Instant firstAttempt = Instant.parse("2026-03-10T12:00:00Z");
        Instant secondAttempt = Instant.parse("2026-03-10T12:00:10Z");

        transactionRunner().execute(contextOf(institutionId), () -> {
            store().claim(institutionId, fingerprint, firstAttempt);
            store().save(institutionId, fingerprint, new BackoffState(3, firstAttempt));
            return null;
        });

        BackoffState claimed = transactionRunner().execute(contextOf(institutionId),
                () -> store().claim(institutionId, fingerprint, secondAttempt));

        assertThat(claimed.consecutiveFailures())
                .as("the reclaim's own VALUES clause proposes 0 at secondAttempt (design.md, "
                        + "decision 5): RETURNING must hand back the row that already existed, "
                        + "never that proposal (sonda S3)")
                .isEqualTo(3);
        assertThat(claimed.lastAttemptAt()).isEqualTo(firstAttempt);
    }

    @Test
    void saveOverwritesTheCountAndTheLastAttemptInstant() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdentifierFingerprint fingerprint = someFingerprint();
        Instant initial = Instant.parse("2026-03-10T12:00:00Z");
        Instant updated = Instant.parse("2026-03-10T12:00:10Z");

        transactionRunner().execute(contextOf(institutionId), () -> {
            store().claim(institutionId, fingerprint, initial);
            store().save(institutionId, fingerprint, new BackoffState(4, updated));
            return null;
        });

        BackoffState reread = transactionRunner().execute(contextOf(institutionId),
                () -> store().claim(institutionId, fingerprint, updated));

        assertThat(reread).isEqualTo(new BackoffState(4, updated));
    }

    /**
     * A syntactically valid stand-in fingerprint: 64 lowercase hex characters, which is all {@code
     * identity_login_backoff_hash_chk} enforces — the same construction {@code IdentityRowSecurityIT}
     * already uses, deliberately not derived from any real identifier.
     */
    private static IdentifierFingerprint someFingerprint() {
        return new IdentifierFingerprint(
                (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

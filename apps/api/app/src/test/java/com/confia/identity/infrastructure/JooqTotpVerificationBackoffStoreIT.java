package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.TotpVerificationBackoffStore;
import com.confia.identity.domain.BackoffState;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqTotpVerificationBackoffStore} against a real PostgreSQL
 * (column-encryption-and-mfa-totp design.md, decision 8): its {@code claim(...)} reproduces {@code
 * JooqLoginBackoffStore}'s own {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}, with {@link
 * StaffAccountId} as the key instead of an identifier fingerprint.
 */
class JooqTotpVerificationBackoffStoreIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";

    @Test
    void claimCreatesTheRowAndReturnsTheInitialStateOnFirstCall() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        Instant now = Instant.parse("2026-10-05T09:00:00Z");
        TotpVerificationBackoffStore store = new JooqTotpVerificationBackoffStore(dsl);

        BackoffState firstClaim = transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            return store.claim(institutionId, accountId, now);
        });

        assertThat(firstClaim.consecutiveFailures()).isZero();
        assertThat(firstClaim.lastAttemptAt()).isEqualTo(now);
    }

    /**
     * The same property {@code JooqLoginBackoffStoreIT}'s precedent already established for the
     * login backoff: {@code claim(...)} returns the state that existed <b>before</b> this call,
     * and reclaiming again after {@code save(...)} shows the persisted, updated state.
     */
    @Test
    void claimReturnsThePriorStateAndSavePersistsTheNewOne() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        Instant firstAttempt = Instant.parse("2026-10-05T09:00:00Z");
        Instant secondAttempt = Instant.parse("2026-10-05T09:01:00Z");
        TotpVerificationBackoffStore store = new JooqTotpVerificationBackoffStore(dsl);

        transactionRunner().execute(contextOf(institutionId), () -> {
            seedAccount(institutionId, accountId);
            store.claim(institutionId, accountId, firstAttempt);
            store.save(institutionId, accountId, new BackoffState(3, firstAttempt));
            return null;
        });

        BackoffState secondClaim = transactionRunner().execute(contextOf(institutionId),
                () -> store.claim(institutionId, accountId, secondAttempt));

        assertThat(secondClaim.consecutiveFailures()).isEqualTo(3);
        assertThat(secondClaim.lastAttemptAt()).isEqualTo(firstAttempt);
    }

    private void seedAccount(InstitutionId institutionId, StaffAccountId accountId) {
        dsl.execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, ?, true)
                """, institutionId.value(), accountId.value(),
                "mfa." + accountId.value() + "@colegio.edu.hn", PLACEHOLDER_PASSWORD_HASH);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

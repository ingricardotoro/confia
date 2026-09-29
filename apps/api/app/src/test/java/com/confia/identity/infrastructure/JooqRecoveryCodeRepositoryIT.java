package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.RecoveryCodeRepository;
import com.confia.identity.domain.RecoveryCodeRow;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredRecoveryCodeHash;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqRecoveryCodeRepository} against a real PostgreSQL. Written during the verification of
 * cut C3, which found this was the <b>only</b> jOOQ adapter of the identity module without an
 * integration test of its own — {@code JooqLoginBackoffStoreIT},
 * {@code JooqStaffAccountRepositoryIT}, {@code JooqTotpCredentialRepositoryIT} and
 * {@code JooqTotpVerificationBackoffStoreIT} all have one. It was exercised only indirectly, through
 * the use cases of the next cut, which also left {@code RecoveryCodeRow} at zero coverage.
 *
 * <p>The assertion that carries the most weight is the one on {@link
 * RecoveryCodeRepository#markUsed}: its {@code AND used_at IS NULL} predicate is what makes a
 * recovery code single-use, and returning zero rows is how a caller learns a concurrent consumption
 * already won. A single-session test of the use case cannot distinguish that predicate from a plain
 * {@code UPDATE}, the same lesson cut C2 learned about the TOTP counter.
 */
class JooqRecoveryCodeRepositoryIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final Instant USED_AT = Instant.parse("2026-10-05T08:00:00Z");

    @Test
    void anInsertedCodeIsReadBackUnusedWithItsIdentifierAndHash() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        UUID codeId = UUID.randomUUID();
        StoredRecoveryCodeHash hash = hashOf("c2FsdG9uZQ");
        seedAccount(institutionId, accountId);

        transactionRunner().execute(contextOf(institutionId), () -> {
            repository().insert(institutionId, accountId, codeId, hash);
            return null;
        });

        List<RecoveryCodeRow> unused = transactionRunner().execute(contextOf(institutionId),
                () -> repository().findUnusedByAccountId(institutionId, accountId));

        assertThat(unused).singleElement()
                .isEqualTo(new RecoveryCodeRow(codeId, hash));
    }

    @Test
    void markingACodeUsedSucceedsOnceAndAffectsZeroRowsOnASecondAttempt() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        UUID codeId = UUID.randomUUID();
        seedAccount(institutionId, accountId);
        transactionRunner().execute(contextOf(institutionId), () -> {
            repository().insert(institutionId, accountId, codeId, hashOf("c2FsdHR3bw"));
            return null;
        });

        boolean firstUse = transactionRunner().execute(contextOf(institutionId),
                () -> repository().markUsed(institutionId, codeId, USED_AT));
        boolean secondUse = transactionRunner().execute(contextOf(institutionId),
                () -> repository().markUsed(institutionId, codeId, USED_AT.plusSeconds(60)));

        assertThat(firstUse).isTrue();
        assertThat(secondUse)
                .as("a recovery code is single use: the second attempt must affect zero rows, "
                        + "which is also how a caller learns a concurrent consumption already won")
                .isFalse();
    }

    @Test
    void aUsedCodeLeavesTheUnusedListAndTheUnusedCount() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        seedAccount(institutionId, accountId);
        transactionRunner().execute(contextOf(institutionId), () -> {
            repository().insert(institutionId, accountId, firstId, hashOf("c2FsdHRocmVl"));
            repository().insert(institutionId, accountId, secondId, hashOf("c2FsdGZvdXI"));
            return null;
        });

        assertThat(countUnused(institutionId, accountId)).isEqualTo(2L);

        transactionRunner().execute(contextOf(institutionId),
                () -> repository().markUsed(institutionId, firstId, USED_AT));

        assertThat(countUnused(institutionId, accountId)).isEqualTo(1L);
        assertThat(transactionRunner().execute(contextOf(institutionId),
                () -> repository().findUnusedByAccountId(institutionId, accountId)))
                .as("the consumed code must disappear from the unused list, without being deleted: "
                        + "nothing removes a row here, used_at is what changes")
                .extracting(RecoveryCodeRow::id)
                .containsExactly(secondId);
    }

    private long countUnused(InstitutionId institutionId, StaffAccountId accountId) {
        return transactionRunner().execute(contextOf(institutionId),
                () -> repository().countUnusedByAccountId(institutionId, accountId));
    }

    private RecoveryCodeRepository repository() {
        return new JooqRecoveryCodeRepository(dsl);
    }

    /** A syntactically valid stand-in: satisfies the {@code $argon2id$} prefix CHECK only. */
    private static StoredRecoveryCodeHash hashOf(String salt) {
        return new StoredRecoveryCodeHash(
                "$argon2id$v=19$m=19456,t=3,p=1$" + salt + "$aGFzaGhhc2hoYXNo");
    }

    private void seedAccount(InstitutionId institutionId, StaffAccountId accountId) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, true)
                    """, institutionId.value(), accountId.value(),
                    "mfa." + accountId.value() + "@colegio.edu.hn", PLACEHOLDER_PASSWORD_HASH);
            return null;
        });
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

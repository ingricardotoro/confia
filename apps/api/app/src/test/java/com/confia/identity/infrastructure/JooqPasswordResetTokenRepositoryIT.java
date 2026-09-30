package com.confia.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.PasswordResetTokenRepository;
import com.confia.identity.domain.PasswordResetTokenHash;
import com.confia.identity.domain.PasswordResetTokenRow;
import com.confia.identity.domain.StaffAccountId;
import com.confia.kernel.InstitutionId;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link JooqPasswordResetTokenRepository} against a real PostgreSQL (password-recovery-token
 * design.md decisions 1 and 5; tasks.md task 1.2). Every instant is passed in explicitly, never read
 * from the database clock, because the adapter writes {@code issued_at} and {@code expires_at} from
 * the caller's injected {@code Clock}: that is what lets the 30-minute and 60-minute borders be
 * tested exactly.
 *
 * <p>The assertion that carries the most weight is {@link PasswordResetTokenRepository#consume}:
 * its predicate on {@code consumed_at}, {@code superseded_at} and {@code expires_at} is what makes a
 * token single use, and a {@code false} is how the reset learns that a concurrent reset already
 * won, the same shape {@code JooqRecoveryCodeRepositoryIT} proves for {@code markUsed}.
 */
class JooqPasswordResetTokenRepositoryIT extends CommittingPostgresIntegrationTest {

    private static final String PLACEHOLDER_PASSWORD_HASH =
            "$argon2id$v=19$m=19456,t=3,p=1$c2FsdHNhbHRzYWx0$aGFzaGhhc2hoYXNoaGFzaGhhc2g";
    private static final Instant ISSUED_AT = Instant.parse("2026-10-05T08:00:00Z");
    private static final Duration VALIDITY = Duration.ofMinutes(30);

    private final InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
    private final StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());

    @Test
    void anInsertedTokenIsFoundByItsHashWithEveryInstantItWasGiven() {
        seedAccount();
        UUID tokenId = UUID.randomUUID();
        PasswordResetTokenHash hash = someHash();

        insert(tokenId, hash, ISSUED_AT);
        Optional<PasswordResetTokenRow> found = findByHash(hash);

        assertThat(found).contains(new PasswordResetTokenRow(tokenId, accountId, ISSUED_AT,
                ISSUED_AT.plus(VALIDITY), null, null));
    }

    @Test
    void findByHashReturnsNothingForAnUnknownHash() {
        seedAccount();
        insert(UUID.randomUUID(), someHash(), ISSUED_AT);

        assertThat(findByHash(someHash())).isEmpty();
    }

    @Test
    void countIssuedSinceExcludesATokenIssuedExactlyAtTheBorder() {
        seedAccount();
        Instant since = ISSUED_AT;
        insertAndSupersede(since.minusSeconds(1));
        insertAndSupersede(since);
        insertAndSupersede(since.plusSeconds(1));
        insert(UUID.randomUUID(), someHash(), since.plusSeconds(120));

        long counted = inTransaction(() -> repository().countIssuedSince(institutionId, accountId,
                since));

        assertThat(counted)
                .as("only issuances strictly after the window's start count: one issued exactly "
                        + "sixty minutes ago has already left the window")
                .isEqualTo(2L);
    }

    @Test
    void countIssuedSinceCountsOnlyThisAccount() {
        seedAccount();
        StaffAccountId otherAccount = new StaffAccountId(UUID.randomUUID());
        seedAccount(otherAccount);
        inTransaction(() -> {
            repository().insert(institutionId, UUID.randomUUID(), otherAccount, someHash(),
                    ISSUED_AT.plusSeconds(5), ISSUED_AT.plusSeconds(5).plus(VALIDITY));
            return null;
        });

        assertThat(inTransaction(() -> repository().countIssuedSince(institutionId, accountId,
                ISSUED_AT))).isZero();
    }

    @Test
    void supersedeOpenMarksAnOpenTokenEvenWhenItHasAlreadyExpiredAndLeavesAConsumedOneAlone() {
        seedAccount();
        UUID consumedId = UUID.randomUUID();
        PasswordResetTokenHash consumedHash = someHash();
        insert(consumedId, consumedHash, ISSUED_AT);
        assertThat(consume(consumedId, ISSUED_AT.plusSeconds(60))).isTrue();

        UUID expiredOpenId = UUID.randomUUID();
        PasswordResetTokenHash expiredOpenHash = someHash();
        insert(expiredOpenId, expiredOpenHash, ISSUED_AT.plusSeconds(120));
        Instant supersededAt = ISSUED_AT.plus(Duration.ofHours(2));

        int superseded = inTransaction(() -> repository().supersedeOpen(institutionId, accountId,
                supersededAt));
        int supersededAgain = inTransaction(() -> repository().supersedeOpen(institutionId,
                accountId, supersededAt.plusSeconds(1)));

        assertThat(superseded)
                .as("an expired token nobody superseded is still open and must be superseded")
                .isEqualTo(1);
        assertThat(supersededAgain).isZero();
        assertThat(findByHash(expiredOpenHash)).get()
                .extracting(PasswordResetTokenRow::supersededAt).isEqualTo(supersededAt);
        assertThat(findByHash(consumedHash)).get()
                .extracting(PasswordResetTokenRow::supersededAt).isNull();
    }

    @Test
    void consumeSucceedsExactlyOnceOnALiveToken() {
        seedAccount();
        UUID tokenId = UUID.randomUUID();
        insert(tokenId, someHash(), ISSUED_AT);

        boolean first = consume(tokenId, ISSUED_AT.plusSeconds(60));
        boolean second = consume(tokenId, ISSUED_AT.plusSeconds(61));

        assertThat(first).isTrue();
        assertThat(second)
                .as("a token is single use: the second attempt must affect zero rows")
                .isFalse();
    }

    @Test
    void consumeRefusesASupersededToken() {
        seedAccount();
        UUID tokenId = UUID.randomUUID();
        insert(tokenId, someHash(), ISSUED_AT);
        inTransaction(() -> repository().supersedeOpen(institutionId, accountId,
                ISSUED_AT.plusSeconds(10)));

        assertThat(consume(tokenId, ISSUED_AT.plusSeconds(20))).isFalse();
    }

    @Test
    void consumeRefusesATokenExactlyAtItsExpiryButAcceptsItOneSecondBefore() {
        seedAccount();
        UUID atExpiry = UUID.randomUUID();
        insert(atExpiry, someHash(), ISSUED_AT);

        assertThat(consume(atExpiry, ISSUED_AT.plus(VALIDITY)))
                .as("expires_at <= now: the thirty-minute border is outside the validity")
                .isFalse();
        assertThat(consume(atExpiry, ISSUED_AT.plus(VALIDITY).minusSeconds(1))).isTrue();
    }

    /** Scenario "Un token vencido sigue almacenado" (I31). */
    @Test
    void anExpiredTokenIsStillStoredTwoHoursAfterItWasIssued() {
        seedAccount();
        UUID tokenId = UUID.randomUUID();
        PasswordResetTokenHash hash = someHash();
        insert(tokenId, hash, ISSUED_AT);
        Instant twoHoursLater = ISSUED_AT.plus(Duration.ofHours(2));

        assertThat(consume(tokenId, twoHoursLater)).isFalse();
        assertThat(findByHash(hash))
                .as("nothing purges an expired token: rows are retained until change 9")
                .contains(new PasswordResetTokenRow(tokenId, accountId, ISSUED_AT,
                        ISSUED_AT.plus(VALIDITY), null, null));
    }

    /** Scenario "Los tokens usados y superados también se conservan" (I32). */
    @Test
    void consumedAndSupersededTokensAreStillStoredTheNextDayWithTheirMarks() {
        seedAccount();
        UUID supersededId = UUID.randomUUID();
        PasswordResetTokenHash supersededHash = someHash();
        insert(supersededId, supersededHash, ISSUED_AT);
        Instant supersededAt = ISSUED_AT.plusSeconds(300);
        inTransaction(() -> repository().supersedeOpen(institutionId, accountId, supersededAt));

        UUID consumedId = UUID.randomUUID();
        PasswordResetTokenHash consumedHash = someHash();
        Instant secondIssuedAt = ISSUED_AT.plusSeconds(301);
        insert(consumedId, consumedHash, secondIssuedAt);
        Instant consumedAt = secondIssuedAt.plusSeconds(60);
        assertThat(consume(consumedId, consumedAt)).isTrue();

        assertThat(findByHash(supersededHash)).contains(new PasswordResetTokenRow(supersededId,
                accountId, ISSUED_AT, ISSUED_AT.plus(VALIDITY), null, supersededAt));
        assertThat(findByHash(consumedHash)).contains(new PasswordResetTokenRow(consumedId,
                accountId, secondIssuedAt, secondIssuedAt.plus(VALIDITY), consumedAt, null));
    }

    private JooqPasswordResetTokenRepository repository() {
        return new JooqPasswordResetTokenRepository(dsl);
    }

    private void insert(UUID tokenId, PasswordResetTokenHash hash, Instant issuedAt) {
        inTransaction(() -> {
            repository().insert(institutionId, tokenId, accountId, hash, issuedAt,
                    issuedAt.plus(VALIDITY));
            return null;
        });
    }

    /** Inserts a token and supersedes it at once, so several can coexist for one account. */
    private void insertAndSupersede(Instant issuedAt) {
        insert(UUID.randomUUID(), someHash(), issuedAt);
        inTransaction(() -> repository().supersedeOpen(institutionId, accountId, issuedAt));
    }

    private boolean consume(UUID tokenId, Instant at) {
        return inTransaction(() -> repository().consume(institutionId, tokenId, at));
    }

    private Optional<PasswordResetTokenRow> findByHash(PasswordResetTokenHash hash) {
        return inTransaction(() -> repository().findByHash(institutionId, hash));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> body) {
        return transactionRunner().execute(contextOf(institutionId), body::get);
    }

    private void seedAccount() {
        seedAccount(accountId);
    }

    private void seedAccount(StaffAccountId account) {
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), account.value(),
                    "reset." + account.value() + "@colegio.edu.hn", PLACEHOLDER_PASSWORD_HASH);
            return null;
        });
    }

    /** 64 lowercase hex characters from two random UUIDs: a stand-in, not the hash of anything. */
    private static PasswordResetTokenHash someHash() {
        return new PasswordResetTokenHash(
                (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""));
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}

package com.confia.identity.application;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * The real account repository, with a pause right after the lock of the account is taken: the test
 * counts {@code locked} down and the transaction waits for {@code release}, so another transaction
 * on the same account is started while this one holds the lock. No clock decides the order: the test
 * goes on when the database reports a backend waiting for the lock, a condition with a bound.
 */
final class HoldingAccounts implements StaffAccountRepository {

    private final StaffAccountRepository real;
    private final CountDownLatch locked;
    private final CountDownLatch release;

    HoldingAccounts(StaffAccountRepository real, CountDownLatch locked, CountDownLatch release) {
        this.real = real;
        this.locked = locked;
        this.release = release;
    }

    @Override
    public Optional<StaffAccount> findBy(InstitutionId institutionId, LoginIdentifier identifier) {
        return real.findBy(institutionId, identifier);
    }

    @Override
    public Optional<StaffAccount> lockById(InstitutionId institutionId, StaffAccountId accountId) {
        Optional<StaffAccount> account = real.lockById(institutionId, accountId);
        locked.countDown();
        RotationIntegrationTest.awaitRelease(release);
        return account;
    }

    @Override
    public boolean replacePasswordHash(InstitutionId institutionId, StaffAccountId accountId,
            StoredPasswordHash newHash) {
        return real.replacePasswordHash(institutionId, accountId, newHash);
    }
}

package com.confia.identity.application;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.kernel.InstitutionId;
import java.util.Optional;

/**
 * Resolves a {@link StaffAccount} by its normalized login identifier, scoped to one institution
 * (design.md, §6.1). {@link com.confia.identity.infrastructure.JooqStaffAccountRepository} is its
 * real adapter, arriving in PR C3b; this cut's own {@code AuthenticateWithPasswordTest} exercises
 * {@link AuthenticateWithPassword} against a hand-written test double instead (design.md §11, paso
 * 15).
 */
public interface StaffAccountRepository {

    Optional<StaffAccount> findBy(InstitutionId institutionId, LoginIdentifier identifier);

    /**
     * Reads the account and holds its row lock until the transaction ends ({@code SELECT ... FOR
     * UPDATE}; password-recovery-token design.md decision 2). Password-reset issuance and reset
     * both take this lock first, so two of them on the same account run one after the other.
     */
    Optional<StaffAccount> lockById(InstitutionId institutionId, StaffAccountId accountId);

    /**
     * Rewrites the account's stored hash and reports whether a row was affected. Only the password
     * reset calls it; login never rewrites a hash.
     */
    boolean replacePasswordHash(InstitutionId institutionId, StaffAccountId accountId,
            StoredPasswordHash newHash);
}

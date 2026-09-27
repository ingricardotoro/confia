package com.confia.identity.application;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StaffAccount;
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
}

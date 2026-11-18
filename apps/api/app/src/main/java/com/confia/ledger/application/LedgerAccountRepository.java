package com.confia.ledger.application;

import com.confia.kernel.InstitutionId;
import com.confia.ledger.domain.LedgerAccount;
import java.util.Optional;
import java.util.UUID;

/**
 * The port of the accounts of the chart. It is not the port of the entries: an account is configuration, so it is inserted and
 * its name and active flag are edited, but it is never deleted. Every operation is scoped to the institution given.
 */
public interface LedgerAccountRepository {

    Optional<LedgerAccount> findById(InstitutionId institution, UUID id);

    /** The account with {@code SELECT ... FOR UPDATE}, so an edit and a use of the account take turns. */
    Optional<LedgerAccount> lockById(InstitutionId institution, UUID id);

    /** Inserts the account; a code the institution already has (whatever the capitals) is a duplicate-account-code. */
    void insert(InstitutionId institution, LedgerAccount account);

    /** Writes the name, the active flag and the version of the account if the stored version is {@code expectedVersion}. */
    void update(InstitutionId institution, LedgerAccount account, long expectedVersion);

    Page<LedgerAccount> list(InstitutionId institution, AccountPageRequest request, AccountFilter filter);
}

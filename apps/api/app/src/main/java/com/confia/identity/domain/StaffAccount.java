package com.confia.identity.domain;

import com.confia.kernel.InstitutionId;
import java.util.Objects;

/**
 * A staff account, as {@code identity_staff_account} models it (design.md, decision 4): its
 * identity within its institution and the login identifier it authenticates with.
 *
 * <p>This PR (C1) creates only the identity half of the row. The stored password hash is not a
 * field here yet: {@code StoredPasswordHash} does not exist until PR C2 (design.md §11's note on
 * {@code LoginIdentifier}'s placement), and this cut has no repository or use case to read this
 * type at all — {@code StaffAccountRepository.findBy(...)} is PR C3a's own port. Adding the
 * password hash is that later cut's change to this file, not a guess made here ahead of any test
 * that needs it.
 */
public record StaffAccount(StaffAccountId id, InstitutionId institutionId,
        LoginIdentifier identifier) {

    public StaffAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(identifier, "identifier");
    }
}

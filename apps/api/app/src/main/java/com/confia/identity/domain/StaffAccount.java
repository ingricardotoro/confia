package com.confia.identity.domain;

import com.confia.kernel.InstitutionId;
import java.util.Objects;

/**
 * A staff account, as {@code identity_staff_account} models it (design.md, decision 4): its
 * identity within its institution, the login identifier it authenticates with, and its stored
 * Argon2id hash.
 *
 * <p>PR C1 created only the identity half of this row, before {@code StoredPasswordHash} existed
 * (PR C2) and before any repository or use case read this type at all. This cut (C3a) is that
 * later change: {@code StaffAccountRepository.findBy(...)} now has a real consumer, {@code
 * AuthenticateWithPassword}, which needs the stored hash to verify a presented password against —
 * exactly the field this record was missing until now.
 */
public record StaffAccount(StaffAccountId id, InstitutionId institutionId,
        LoginIdentifier identifier, StoredPasswordHash passwordHash) {

    public StaffAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(passwordHash, "passwordHash");
    }
}

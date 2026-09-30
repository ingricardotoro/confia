package com.confia.identity.domain;

import com.confia.kernel.InstitutionId;
import java.util.Objects;

/**
 * A staff account, as {@code identity_staff_account} models it (design.md, decision 4): its
 * identity within its institution, the login identifier it authenticates with, its stored
 * Argon2id hash, and whether it requires a second factor.
 *
 * <p>PR C1 created only the identity half of this row, before {@code StoredPasswordHash} existed
 * (PR C2) and before any repository or use case read this type at all. This cut (C3a) is that
 * later change: {@code StaffAccountRepository.findBy(...)} now has a real consumer, {@code
 * AuthenticateWithPassword}, which needs the stored hash to verify a presented password against —
 * exactly the field this record was missing until now.
 *
 * <p>{@code mfaRequired} arrives with {@code column-encryption-and-mfa-totp} (C4): the
 * {@code identity_staff_account.mfa_required} column, decided explicitly by whoever creates the
 * account, with no role or permission behind it yet
 * (specs/identity/spec.md, "Columna {@code mfa_required}..."). {@code AuthenticateWithPassword}
 * reads it, together with the account's TOTP-enrollment state, to decide between {@code
 * Authenticated}, {@code SecondFactorRequired} and {@code SecondFactorEnrollmentRequired}
 * (proposal.md, "Cómo se determina qué cuenta necesita segundo factor").
 */
public record StaffAccount(StaffAccountId id, InstitutionId institutionId,
        LoginIdentifier identifier, StoredPasswordHash passwordHash, boolean mfaRequired) {

    public StaffAccount {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(passwordHash, "passwordHash");
    }
}

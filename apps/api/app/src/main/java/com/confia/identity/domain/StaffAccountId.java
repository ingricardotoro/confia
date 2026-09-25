package com.confia.identity.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a staff account within its institution, part of the composite primary key
 * {@code (institution_id, id)} of {@code identity_staff_account} (design.md, decision 4).
 *
 * <p>A {@code null} value is a programming error (ADR-0019, point 6), not a business condition:
 * the compact constructor throws {@link NullPointerException}, never a {@code DomainException}
 * subclass, following {@code com.confia.kernel.InstitutionId}'s own precedent.
 */
public record StaffAccountId(UUID value) {

    public StaffAccountId {
        Objects.requireNonNull(value, "value");
    }
}

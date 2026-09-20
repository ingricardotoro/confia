package com.confia.kernel;

import java.util.Objects;
import java.util.UUID;

/**
 * Identifier of the tenant every business module keys its data on (ADR-0009). Lives in {@code
 * kernel}, not in {@code organization.domain}, so every module can use it in its own signatures
 * without importing another module's domain, which the layer-boundary rule and Spring Modulith
 * would otherwise forbid (ADR-0002; design.md, decision 1).
 *
 * <p>A null value is a programming error (ADR-0019, point 6): the compact constructor throws
 * {@link NullPointerException}, never a {@link DomainException} subclass, so this type adds no
 * code to {@code kernel}'s catalog and {@link KernelErrorCodesTest} is unaffected. Equality and
 * hash code come from the record's generated implementation over the underlying {@link UUID},
 * which is exact and needs no override, unlike {@link Money}'s reason for not being a record.
 */
public record InstitutionId(UUID value) {

    public InstitutionId {
        Objects.requireNonNull(value, "value");
    }
}

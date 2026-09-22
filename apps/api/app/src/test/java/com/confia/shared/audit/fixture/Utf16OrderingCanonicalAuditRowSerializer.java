package com.confia.shared.audit.fixture;

import com.confia.shared.audit.CanonicalAuditRowSerializer;

/**
 * Deliberately wrong: orders JSON object keys by {@link String#compareTo} (UTF-16 code unit order)
 * instead of by their unsigned UTF-8 byte sequence (design.md §6.4, deviation 1;
 * {@link CanonicalAuditRowSerializer#compareObjectKeys}). Exists solely as the permanent fixture
 * {@code CanonicalSerializationDivergenceTest} needs to prove the property-based cross-check
 * ({@code CanonicalSerializationCrossCheckIT}, task 4.2/4.3) actually detects a real divergence,
 * and does not merely pass because nothing was ever compared.
 *
 * <p>Never used in production. Never extended further. The only override is the one line that
 * makes it wrong on purpose.
 */
public final class Utf16OrderingCanonicalAuditRowSerializer extends CanonicalAuditRowSerializer {

    @Override
    protected int compareObjectKeys(String a, String b) {
        return a.compareTo(b);
    }
}

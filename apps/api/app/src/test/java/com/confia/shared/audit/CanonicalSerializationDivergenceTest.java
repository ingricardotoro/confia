package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.audit.fixture.Utf16OrderingCanonicalAuditRowSerializer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves the property-based cross-check ({@link CanonicalSerializationCrossCheckIT}) can actually
 * detect a real divergence, and does not merely pass by absence of comparison —
 * specs/audit-trail/spec.md, requirement "Reproducibilidad de la serialización canónica entre
 * PL/pgSQL y Java", scenario "Una divergencia introducida a propósito hace fallar la prueba de
 * propiedades". design.md §6.5 names the deterministic key pair used here: {@code "Ｚ"}
 * orders before {@code "😀"} by unsigned UTF-8 bytes ({@code 0xEF < 0xF0}), but after it
 * by UTF-16 code units ({@code 0xD83D < 0xFF3A}) — a comparator built on {@link String#compareTo}
 * orders this pair the wrong way around.
 */
class CanonicalSerializationDivergenceTest {

    /** {@code U+FF3A}, FULLWIDTH LATIN CAPITAL LETTER Z. */
    private static final String KEY_UTF16_FIRST_UTF8_SECOND = "Ｚ";

    /** {@code U+1F600}, GRINNING FACE — a surrogate pair in UTF-16. */
    private static final String KEY_UTF16_SECOND_UTF8_FIRST = "😀";

    private final CanonicalAuditRowSerializer correct = new CanonicalAuditRowSerializer();
    private final CanonicalAuditRowSerializer utf16Ordered = new Utf16OrderingCanonicalAuditRowSerializer();

    @Test
    void utf16KeyOrderingDivergesFromUtf8ByteOrderingOnTheDeterministicPair() {
        ObjectNode object = JsonNodeFactory.instance.objectNode();
        object.put(KEY_UTF16_FIRST_UTF8_SECOND, 1);
        object.put(KEY_UTF16_SECOND_UTF8_FIRST, 2);
        CanonicalAuditRow row = rowWithBeforeValue(object);

        String correctJson = correct.canonicalJson(object);
        String wrongJson = utf16Ordered.canonicalJson(object);
        byte[] correctHash = correct.rowHash(row);
        byte[] wrongHash = utf16Ordered.rowHash(row);

        assertThat(wrongJson)
                .as("the two keys must order oppositely under UTF-8 bytes (correct=%s) and "
                        + "UTF-16 code units (wrong=%s): the wrong comparator produces different "
                        + "canonical text for the exact same input", correctJson, wrongJson)
                .isNotEqualTo(correctJson);
        assertThat(wrongHash)
                .as("a key-ordering divergence in the JSON canonicalizer must change row_hash: "
                        + "the property-based cross-check exists precisely to catch this class of "
                        + "silent divergence")
                .isNotEqualTo(correctHash);
    }

    private static CanonicalAuditRow rowWithBeforeValue(ObjectNode beforeValue) {
        return new CanonicalAuditRow(new byte[32], 1L, UUID.randomUUID(), Instant.now(), null,
                "system", "actor", null, null, UUID.randomUUID(), null, "test.action",
                "test_entity", "entity-1", "success", beforeValue, null, null, null);
    }
}

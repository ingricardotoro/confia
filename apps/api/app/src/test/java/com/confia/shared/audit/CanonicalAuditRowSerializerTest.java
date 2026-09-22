package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.StringNode;

/**
 * Hand-written unit cases for {@link CanonicalAuditRowSerializer}, the "Unitaria" layer of
 * design.md §7's test strategy table ("Serialización canónica sobre casos límite escritos a mano
 * ... JUnit y AssertJ, sin contenedor") — no Docker, no PostgreSQL. Complements, never replaces,
 * {@link CanonicalSerializationCrossCheckIT}'s property-based cross-check against the real engine:
 * these cases target defensive branches (an unsupported JSON node type, control characters with a
 * short escape form) that the generator does not happen to hit, plus {@link CanonicalAuditRow}'s
 * own {@code toString()}.
 */
class CanonicalAuditRowSerializerTest {

    private final CanonicalAuditRowSerializer serializer = new CanonicalAuditRowSerializer();

    @Test
    void backspaceAndFormFeedUseTheirShortEscapeForm() {
        assertThat(serializer.canonicalJson(new StringNode("\b"))).isEqualTo("\"\\b\"");
        assertThat(serializer.canonicalJson(new StringNode("\f"))).isEqualTo("\"\\f\"");
    }

    @Test
    void unsupportedJsonNodeTypeIsRejected() {
        assertThatThrownBy(() -> serializer.canonicalJson(MissingNode.getInstance()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported JSON node type");
    }

    @Test
    void canonicalAuditRowToStringIncludesEveryFieldForDebuggability() {
        CanonicalAuditRow row = new CanonicalAuditRow(new byte[32], 1L, UUID.randomUUID(),
                Instant.EPOCH, null, "system", "actor label", "192.168.1.1/32", null,
                UUID.randomUUID(), null, "test.action", "test_entity", "entity-1", "success", null,
                null, null, null);

        assertThat(row.toString())
                .startsWith("CanonicalAuditRow{prevHash=")
                .contains("id=1", "actorKind=system", "actorLabel=actor label",
                        "sourceIp=192.168.1.1/32", "action=test.action", "outcome=success");
    }
}

package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;
import org.junit.jupiter.api.Test;

/**
 * Unit cases for {@link RequestPayloadHasher} (design.md, decision 8; specs/build-integrity/spec.md,
 * requirement "Rechazo de la misma clave con carga útil distinta, comparada por hash
 * canonicalizado"). No Docker, no container — pure JUnit and AssertJ, matching
 * {@code CanonicalAuditRowSerializerTest}'s "Unitaria" layer.
 *
 * <p><strong>The golden vector is a hard-coded literal, not a value the test computes with the
 * same production code it exercises.</strong> {@link #GOLDEN_PREIMAGE} and {@link #GOLDEN_HASH}
 * were derived once, outside this test and outside {@link RequestPayloadHasher}, by concatenating
 * {@code RequestPayloadHasher.FORMAT_VERSION} with the hand-written canonical JSON text
 * {@code {"a":2,"b":1}} and running that exact byte string through an independent SHA-256
 * implementation ({@code openssl dgst -sha256}, not this JVM):
 *
 * <pre>{@code
 * $ printf '%s' 'confia.idempotency.v1{"a":2,"b":1}' | openssl dgst -sha256
 * SHA2-256(stdin)= f372044022b69509a05bc7b6d581566fbaffb0c7c271e0b2b335fceb6b04f89a
 * }</pre>
 *
 * <p>Because the literal was never produced by calling {@link RequestPayloadHasher#hash}, a
 * silent change to {@link CanonicalAuditRowSerializer#canonicalJson} — a reordered key, a
 * different number format, an added escape — moves the hasher's output away from this fixed
 * literal and turns {@link #sameGoldenPayloadAlwaysProducesTheSameFixedHash()} red, which is the
 * entire point of decision 8's mitigation 2. An assertion that compared {@code hash(payload)}
 * against another call to {@code hash(payload)} would pass trivially and prove nothing.
 */
class RequestPayloadHasherTest {

    /**
     * {@code confia.idempotency.v1} concatenated with the canonical form of {@code {"b":1,"a":2}}
     * ({@code {"a":2,"b":1}}, keys sorted by unsigned UTF-8 byte order — see the class Javadoc for
     * the exact independent derivation).
     */
    private static final String GOLDEN_HASH =
            "f372044022b69509a05bc7b6d581566fbaffb0c7c271e0b2b335fceb6b04f89a";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final RequestPayloadHasher hasher = new RequestPayloadHasher();

    @Test
    void sameGoldenPayloadAlwaysProducesTheSameFixedHash() {
        JsonNode payload = JSON_MAPPER.readTree("{\"b\":1,\"a\":2}");

        assertThat(hasher.hash(payload)).isEqualTo(GOLDEN_HASH);
    }

    @Test
    void twoPayloadsWithTheSameKeysInADifferentOrderProduceTheSameHash() {
        JsonNode reversedOrder = JSON_MAPPER.readTree("{\"b\":1,\"a\":2}");
        JsonNode sortedOrder = JSON_MAPPER.readTree("{\"a\":2,\"b\":1}");

        assertThat(hasher.hash(reversedOrder)).isEqualTo(hasher.hash(sortedOrder));
    }

    @Test
    void aPayloadWithNoBodyPassesTheJsonNullLiteralAndDoesNotThrow() {
        JsonNode jsonNull = NullNode.instance;

        assertThat(hasher.hash(jsonNull))
                .isNotBlank()
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    void aJavaNullPayloadIsRejectedWithNullPointerExceptionInsteadOfASilentResult() {
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theHashIsALowercaseHexadecimalSha256Digest() {
        JsonNode payload = JSON_MAPPER.readTree("{\"x\":1}");

        assertThat(hasher.hash(payload)).matches("^[0-9a-f]{64}$");
    }
}

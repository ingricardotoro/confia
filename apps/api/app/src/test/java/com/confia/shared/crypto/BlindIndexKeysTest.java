package com.confia.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlindIndexKeysTest {

    private static String keyOf(byte fill) {
        byte[] bytes = new byte[BlindIndexKeys.LENGTH_BYTES];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void theCurrentVersionComesFirstAndTheOthersFollowNewestFirst() {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("v1", keyOf((byte) 1));
        configured.put("v3", keyOf((byte) 3));
        configured.put("v2", keyOf((byte) 2));

        BlindIndexKeys keys = BlindIndexKeys.of("v2", configured);

        assertThat(keys.currentVersion()).isEqualTo("v2");
        assertThat(keys.versions()).containsExactly("v2", "v3", "v1");
    }

    @Test
    void theCurrentVersionMustBeConfigured() {
        assertThatThrownBy(() -> BlindIndexKeys.of("v2", Map.of("v1", keyOf((byte) 1))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("v2");
    }

    @Test
    void atLeastOneKeyIsRequired() {
        assertThatThrownBy(() -> BlindIndexKeys.of("v1", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVersionNameHasTheShapeVAndDigits() {
        for (String bad : new String[] {"1", "v", "V1", "v1:x", "v0", "v1234", " v1", "latest"}) {
            assertThatThrownBy(() -> BlindIndexKeys.of(bad, Map.of(bad, keyOf((byte) 1))))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aKeyMustBeBase64OfExactlyThirtyTwoBytesAndTheMessageNeverEchoesTheValue() {
        String notBase64 = "not~base64~SECRETFRAGMENT";
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> BlindIndexKeys.of("v1", Map.of("v1", notBase64)))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("SECRETFRAGMENT");
                    assertThat(e.getCause()).isNull();
                });
        assertThatThrownBy(() -> BlindIndexKeys.of("v1", Map.of("v1", shortKey)))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(shortKey));
        assertThatThrownBy(() -> BlindIndexKeys.of("v1", Map.of("v1", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTextRepresentationNamesNoKeyMaterialNorVersion() {
        String key = keyOf((byte) 5);

        BlindIndexKeys keys = BlindIndexKeys.of("v1", Map.of("v1", key));

        assertThat(keys.toString()).isEqualTo("BlindIndexKeys[REDACTED]").doesNotContain(key);
    }
}

package com.confia.students.infrastructure.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.crypto.BlindIndexKeys;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The administrative process refuses to start without its dedicated blind-index keys, and says what is wrong
 * without ever saying the value (docs/03-seguridad.md section 7.3; CLAUDE.md regla 11 and 13).
 */
class BlindIndexKeysConfigurationTest {

    private static final String SECRET_FRAGMENT = "SECRETFRAGMENT";

    private static String keyOf(byte fill) {
        byte[] bytes = new byte[BlindIndexKeys.LENGTH_BYTES];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void theCurrentVersionAndTheKeysOfEveryVersionAreReadFromTheEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StudentsConfiguration.CURRENT_VERSION_PROPERTY, "v2")
                .withProperty(StudentsConfiguration.KEYS_PROPERTY + ".v1", keyOf((byte) 1))
                .withProperty(StudentsConfiguration.KEYS_PROPERTY + ".v2", keyOf((byte) 2));

        BlindIndexKeys keys = StudentsConfiguration.blindIndexKeysFrom(environment);

        assertThat(keys.currentVersion()).isEqualTo("v2");
        assertThat(keys.versions()).containsExactly("v2", "v1");
    }

    @Test
    void startupFailsWithoutTheCurrentVersion() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StudentsConfiguration.KEYS_PROPERTY + ".v1", keyOf((byte) 1));

        assertThatThrownBy(() -> StudentsConfiguration.blindIndexKeysFrom(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StudentsConfiguration.CURRENT_VERSION_PROPERTY);
    }

    @Test
    void startupFailsWithoutAnyKey() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StudentsConfiguration.CURRENT_VERSION_PROPERTY, "v1");

        assertThatThrownBy(() -> StudentsConfiguration.blindIndexKeysFrom(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(StudentsConfiguration.KEYS_PROPERTY);
    }

    @Test
    void startupFailsWhenTheCurrentVersionHasNoKey() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StudentsConfiguration.CURRENT_VERSION_PROPERTY, "v2")
                .withProperty(StudentsConfiguration.KEYS_PROPERTY + ".v1", keyOf((byte) 1));

        assertThatThrownBy(() -> StudentsConfiguration.blindIndexKeysFrom(environment))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("v2");
    }

    @Test
    void aMalformedKeyFailsStartupWithoutEchoingItNorChainingACause() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StudentsConfiguration.CURRENT_VERSION_PROPERTY, "v1")
                .withProperty(StudentsConfiguration.KEYS_PROPERTY + ".v1", "not~base64~" + SECRET_FRAGMENT);

        assertThatThrownBy(() -> StudentsConfiguration.blindIndexKeysFrom(environment))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(SECRET_FRAGMENT);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void theKeyNeverAppearsInTheTextOfTheKeysTheServiceOrTheirFailures() {
        String key = keyOf((byte) 9);
        MockEnvironment environment = new MockEnvironment()
                .withProperty(StudentsConfiguration.CURRENT_VERSION_PROPERTY, "v1")
                .withProperty(StudentsConfiguration.KEYS_PROPERTY + ".v1", key);

        BlindIndexKeys keys = StudentsConfiguration.blindIndexKeysFrom(environment);

        assertThat(keys.toString()).doesNotContain(key);
        assertThat(new com.confia.shared.crypto.BlindIndexService(keys).toString()).doesNotContain(key);
    }
}

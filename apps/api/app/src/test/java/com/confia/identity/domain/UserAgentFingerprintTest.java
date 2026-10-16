package com.confia.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * {@link UserAgentFingerprint} (session-tokens-and-web-layer design.md decision 8; scenarios I119
 * and I120): the SHA-256 of the sanitized agent as lowercase hex, and the SHA-256 of the empty text
 * when there is no agent. The agent itself is never stored.
 */
class UserAgentFingerprintTest {

    private static final String AGENT = "Mozilla/5.0 (agente-prueba)";
    private static final String SHA256_OF_EMPTY =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @Test
    void ofIsTheSha256InHexOfTheAgentText() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(AGENT.getBytes(StandardCharsets.UTF_8)));

        UserAgentFingerprint fingerprint = UserAgentFingerprint.of(AGENT);

        assertThat(fingerprint.value()).isEqualTo(expected).hasSize(64).doesNotContain("Mozilla");
    }

    @Test
    void noAgentAndTheEmptyAgentShareTheFingerprintOfTheEmptyText() {
        assertThat(UserAgentFingerprint.of(null).value()).isEqualTo(SHA256_OF_EMPTY);
        assertThat(UserAgentFingerprint.of("").value()).isEqualTo(SHA256_OF_EMPTY);
    }

    @Test
    void differentAgentsHaveDifferentFingerprintsAndTheSameAgentTheSame() {
        assertThat(UserAgentFingerprint.of(AGENT)).isEqualTo(UserAgentFingerprint.of(AGENT))
                .hasSameHashCodeAs(UserAgentFingerprint.of(AGENT))
                .isNotEqualTo(UserAgentFingerprint.of(AGENT + "!")).isNotEqualTo(AGENT);
    }

    @Test
    void aStoredFingerprintMustBeSixtyFourLowercaseHexCharacters() {
        assertThat(new UserAgentFingerprint(SHA256_OF_EMPTY)).isEqualTo(UserAgentFingerprint.of(null));
        assertThatThrownBy(() -> new UserAgentFingerprint("abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAgentFingerprint(SHA256_OF_EMPTY.toUpperCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserAgentFingerprint(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringNeverPrintsTheFingerprint() {
        assertThat(UserAgentFingerprint.of(AGENT).toString())
                .isEqualTo("UserAgentFingerprint[REDACTED]");
    }
}

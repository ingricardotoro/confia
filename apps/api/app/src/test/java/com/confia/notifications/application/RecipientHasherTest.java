package com.confia.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.notifications.domain.RecipientHash;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Scenarios NT13 and NT14: a keyed HMAC-SHA-256 of the normalized address, with the module's own key. */
class RecipientHasherTest {

    private static final String ADDRESS = "ana.martinez@colegio.edu.hn";

    private static byte[] key(int length, int fill) {
        byte[] key = new byte[length];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    /** Independent computation with the JDK, so the production path is not compared with itself. */
    private static String reference(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theHashIsTheHmacOfTheNormalizedAddress() throws Exception {
        byte[] key = key(32, 0x11);

        assertThat(new RecipientHasher(key).hash(ADDRESS).value()).isEqualTo(reference(key, ADDRESS));
    }

    @Test
    void capitalizationAndSurroundingBlanksDoNotChangeTheHash() {
        RecipientHasher hasher = new RecipientHasher(key(32, 0x11));

        assertThat(hasher.hash("Ana.Martinez@Colegio.edu.hn ")).isEqualTo(hasher.hash(ADDRESS));
        assertThat(hasher.hash("\t ANA.MARTINEZ@COLEGIO.EDU.HN\n")).isEqualTo(hasher.hash(ADDRESS));
    }

    @Test
    void twoDifferentKeysGiveTwoDifferentHashes() {
        assertThat(new RecipientHasher(key(32, 0x11)).hash(ADDRESS))
                .isNotEqualTo(new RecipientHasher(key(32, 0x12)).hash(ADDRESS));
    }

    @Test
    void twoDifferentAddressesGiveTwoDifferentHashes() {
        RecipientHasher hasher = new RecipientHasher(key(32, 0x11));

        assertThat(hasher.hash(ADDRESS)).isNotEqualTo(hasher.hash("otra.persona@colegio.edu.hn"));
    }

    @Test
    void theHashIsNotTheUnkeyedSha256OfTheAddress() throws Exception {
        String unkeyed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ADDRESS.getBytes(StandardCharsets.UTF_8)));

        assertThat(new RecipientHasher(key(32, 0x11)).hash(ADDRESS).value()).isNotEqualTo(unkeyed);
    }

    @Test
    void aKeyShorterThan32BytesIsRejectedWithoutRevealingIt() {
        assertThatThrownBy(() -> new RecipientHasher(key(31, 0x11)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("17");
    }

    @Test
    void theHasherKeepsItsOwnCopyOfTheKey() {
        byte[] key = key(32, 0x11);
        RecipientHasher hasher = new RecipientHasher(key);
        RecipientHash before = hasher.hash(ADDRESS);

        Arrays.fill(key, (byte) 0);

        assertThat(hasher.hash(ADDRESS)).isEqualTo(before);
    }

    @Test
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> new RecipientHasher(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecipientHasher(key(32, 1)).hash(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theTextualRepresentationRevealsNoKeyMaterial() {
        assertThat(new RecipientHasher(key(32, 0x11)).toString()).contains("redacted")
                .doesNotContain("17").doesNotContain("0x11");
    }
}

package com.confia.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class BlindIndexServiceTest {

    private static final String PURPOSE = "students.national-id";

    private static ColumnEncryptionMasterKey masterKey(byte fill) {
        byte[] bytes = new byte[ColumnEncryptionMasterKey.LENGTH_BYTES];
        Arrays.fill(bytes, fill);
        return ColumnEncryptionMasterKey.fromBase64(Base64.getEncoder().encodeToString(bytes));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // --- The blind index ---

    @Test
    void theIndexIsSixtyFourLowercaseHexCharactersAndIsDeterministic() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        BlindIndexService service = new BlindIndexService(masterKey((byte) 7));

        String first = service.compute(PURPOSE, institution, "0801199912345");

        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(service.compute(PURPOSE, institution, "0801199912345")).isEqualTo(first);
        assertThat(new BlindIndexService(masterKey((byte) 7)).compute(PURPOSE, institution, "0801199912345"))
                .isEqualTo(first);
    }

    @Test
    void anotherValueAnotherInstitutionAnotherPurposeOrAnotherMasterKeyGiveAnotherIndex() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        BlindIndexService service = new BlindIndexService(masterKey((byte) 7));
        String base = service.compute(PURPOSE, institution, "0801199912345");

        assertThat(service.compute(PURPOSE, institution, "0801199912346")).isNotEqualTo(base);
        assertThat(service.compute(PURPOSE, new InstitutionId(UUID.randomUUID()), "0801199912345"))
                .isNotEqualTo(base);
        assertThat(service.compute("students.other", institution, "0801199912345")).isNotEqualTo(base);
        assertThat(new BlindIndexService(masterKey((byte) 8)).compute(PURPOSE, institution, "0801199912345"))
                .isNotEqualTo(base);
    }

    @Test
    void theIndexIsTheHmacUnderTheDerivedKeyAndNeverUnderTheMasterKeyItself() throws Exception {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        byte[] master = new byte[ColumnEncryptionMasterKey.LENGTH_BYTES];
        Arrays.fill(master, (byte) 9);
        ColumnEncryptionMasterKey key = ColumnEncryptionMasterKey.fromBase64(Base64.getEncoder().encodeToString(master));
        String value = institution.value() + ":ABC12345";

        Mac masterMac = Mac.getInstance("HmacSHA256");
        masterMac.init(new SecretKeySpec(master, "HmacSHA256"));
        byte[] derived = masterMac.doFinal(ascii("confia/blind-index/v1/" + PURPOSE));
        Mac derivedMac = Mac.getInstance("HmacSHA256");
        derivedMac.init(new SecretKeySpec(derived, "HmacSHA256"));
        Mac underMaster = Mac.getInstance("HmacSHA256");
        underMaster.init(new SecretKeySpec(master, "HmacSHA256"));

        String index = new BlindIndexService(key).compute(PURPOSE, institution, "ABC12345");

        assertThat(index).isEqualTo(HexFormat.of().formatHex(derivedMac.doFinal(ascii(value))));
        assertThat(index).isNotEqualTo(HexFormat.of().formatHex(underMaster.doFinal(ascii(value))));
    }

    @Test
    void theIndexNeverContainsTheValue() {
        String index = new BlindIndexService(masterKey((byte) 7)).compute(PURPOSE,
                new InstitutionId(UUID.randomUUID()), "0801199912345");

        assertThat(index).doesNotContain("0801199912345");
    }

    @Test
    void aMalformedPurposeAndNullArgumentsAreRefused() {
        BlindIndexService service = new BlindIndexService(masterKey((byte) 7));
        InstitutionId institution = new InstitutionId(UUID.randomUUID());

        assertThatThrownBy(() -> service.compute("Students", institution, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compute("", institution, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.compute(null, institution, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.compute(PURPOSE, null, "x")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> service.compute(PURPOSE, institution, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BlindIndexService(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theTextRepresentationIsRedacted() {
        assertThat(new BlindIndexService(masterKey((byte) 7)).toString()).isEqualTo("BlindIndexService[REDACTED]");
    }
}

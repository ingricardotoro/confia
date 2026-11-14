package com.confia.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class BlindIndexServiceTest {

    private static final String PURPOSE = "students.national-id";

    private static String keyOf(byte fill) {
        byte[] bytes = new byte[BlindIndexKeys.LENGTH_BYTES];
        Arrays.fill(bytes, fill);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static BlindIndexKeys keys(byte fill) {
        return BlindIndexKeys.of("v1", Map.of("v1", keyOf(fill)));
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    // --- The blind index ---

    @Test
    void theIndexCarriesTheVersionOfTheCurrentKeyAndSixtyFourLowercaseHexCharactersAndIsDeterministic() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        BlindIndexService service = new BlindIndexService(keys((byte) 7));

        String first = service.compute(PURPOSE, institution, "0801199912345");

        assertThat(first).matches("v1:[0-9a-f]{64}");
        assertThat(service.compute(PURPOSE, institution, "0801199912345")).isEqualTo(first);
        assertThat(new BlindIndexService(keys((byte) 7)).compute(PURPOSE, institution, "0801199912345"))
                .isEqualTo(first);
        assertThat(service.currentVersion()).isEqualTo("v1");
    }

    @Test
    void anotherValueAnotherInstitutionAnotherPurposeOrAnotherKeyGiveAnotherIndex() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        BlindIndexService service = new BlindIndexService(keys((byte) 7));
        String base = service.compute(PURPOSE, institution, "0801199912345");

        assertThat(service.compute(PURPOSE, institution, "0801199912346")).isNotEqualTo(base);
        assertThat(service.compute(PURPOSE, new InstitutionId(UUID.randomUUID()), "0801199912345"))
                .isNotEqualTo(base);
        assertThat(service.compute("students.other", institution, "0801199912345")).isNotEqualTo(base);
        assertThat(new BlindIndexService(keys((byte) 8)).compute(PURPOSE, institution, "0801199912345"))
                .isNotEqualTo(base);
    }

    @Test
    void theIndexIsTheHmacUnderTheKeyDerivedFromTheVersionKeyForThePurpose() throws Exception {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        byte[] versionKey = new byte[BlindIndexKeys.LENGTH_BYTES];
        Arrays.fill(versionKey, (byte) 9);
        String value = institution.value() + ":ABC12345";

        Mac versionMac = Mac.getInstance("HmacSHA256");
        versionMac.init(new SecretKeySpec(versionKey, "HmacSHA256"));
        byte[] derived = versionMac.doFinal(ascii("confia/blind-index/v1/" + PURPOSE));
        Mac derivedMac = Mac.getInstance("HmacSHA256");
        derivedMac.init(new SecretKeySpec(derived, "HmacSHA256"));
        Mac underVersionKey = Mac.getInstance("HmacSHA256");
        underVersionKey.init(new SecretKeySpec(versionKey, "HmacSHA256"));

        String index = new BlindIndexService(keys((byte) 9)).compute(PURPOSE, institution, "ABC12345");

        assertThat(index).isEqualTo("v1:" + HexFormat.of().formatHex(derivedMac.doFinal(ascii(value))));
        assertThat(index).isNotEqualTo("v1:" + HexFormat.of().formatHex(underVersionKey.doFinal(ascii(value))));
    }

    @Test
    void theIndexNeverContainsTheValue() {
        String index = new BlindIndexService(keys((byte) 7)).compute(PURPOSE,
                new InstitutionId(UUID.randomUUID()), "0801199912345");

        assertThat(index).doesNotContain("0801199912345");
    }

    // --- Versions and rotation ---

    @Test
    void duringARotationTheLookupCandidatesAreTheCurrentVersionFirstThenThePreviousOne() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        BlindIndexKeys rotating = BlindIndexKeys.of("v2", Map.of("v1", keyOf((byte) 1), "v2", keyOf((byte) 2)));
        BlindIndexService service = new BlindIndexService(rotating);
        BlindIndexService beforeRotation = new BlindIndexService(keys((byte) 1));

        List<String> candidates = service.candidates(PURPOSE, institution, "0801199912345");

        assertThat(service.currentVersion()).isEqualTo("v2");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst()).startsWith("v2:")
                .isEqualTo(service.compute(PURPOSE, institution, "0801199912345"));
        assertThat(candidates.get(1)).isEqualTo(beforeRotation.compute(PURPOSE, institution, "0801199912345"));
    }

    @Test
    void aValueIndexedBeforeTheRotationIsFoundByTheCandidatesAfterIt() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        String stored = new BlindIndexService(keys((byte) 1)).compute(PURPOSE, institution, "ABC12345");
        BlindIndexService rotated = new BlindIndexService(
                BlindIndexKeys.of("v2", Map.of("v1", keyOf((byte) 1), "v2", keyOf((byte) 2))));

        assertThat(rotated.candidates(PURPOSE, institution, "ABC12345")).contains(stored);
        assertThat(rotated.compute(PURPOSE, institution, "ABC12345")).isNotEqualTo(stored);
        assertThat(rotated.isCurrent(stored)).isFalse();
        assertThat(rotated.isCurrent(rotated.compute(PURPOSE, institution, "ABC12345"))).isTrue();
    }

    @Test
    void aVersionThatIsNoLongerConfiguredCannotBeFoundAndIsNotCurrent() {
        InstitutionId institution = new InstitutionId(UUID.randomUUID());
        String stored = new BlindIndexService(keys((byte) 1)).compute(PURPOSE, institution, "ABC12345");
        BlindIndexService other = new BlindIndexService(BlindIndexKeys.of("v2", Map.of("v2", keyOf((byte) 2))));

        assertThat(other.candidates(PURPOSE, institution, "ABC12345")).doesNotContain(stored);
        assertThat(other.isCurrent(stored)).isFalse();
    }

    // --- Argument checks ---

    @Test
    void aMalformedPurposeAndNullArgumentsAreRefused() {
        BlindIndexService service = new BlindIndexService(keys((byte) 7));
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
        assertThat(new BlindIndexService(keys((byte) 7)).toString()).isEqualTo("BlindIndexService[REDACTED]");
    }
}

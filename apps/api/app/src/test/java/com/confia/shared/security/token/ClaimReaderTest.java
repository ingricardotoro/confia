package com.confia.shared.security.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit test of the claim reader, one declared claim at a time and by its JSON type (specs/identity,
 * scenarios I212 and I213). The range of {@code epochSecond} is pinned at both edges and at the
 * extremes of {@code long}, and every wrong type is a {@code CLAIMS_INVALID} without data.
 */
class ClaimReaderTest {

    private static final long LAST_EPOCH_SECOND = 253_402_300_799L;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // --- the closed claim list ---------------------------------------------------------------------

    @Test
    void aPayloadThatIsNotAnObjectIsInvalid() {
        assertInvalid(() -> new ClaimReader(MAPPER.readTree("[\"a\"]"), Set.of("a")));
        assertInvalid(() -> new ClaimReader(MAPPER.readTree("\"a\""), Set.of()));
    }

    @Test
    void aMissingClaimAndAnExtraClaimAreEachInvalid() {
        assertInvalid(() -> new ClaimReader(json("{\"a\":\"x\"}"), Set.of("a", "b")));
        assertInvalid(() -> new ClaimReader(json("{\"a\":\"x\",\"b\":\"y\"}"), Set.of("a")));
        assertInvalid(() -> new ClaimReader(json("{\"a\":\"x\",\"c\":\"y\"}"), Set.of("a", "b")));
        assertThatCode(() -> new ClaimReader(json("{\"a\":\"x\",\"b\":\"y\"}"), Set.of("a", "b")))
                .doesNotThrowAnyException();
    }

    @Test
    void aClaimThatWasNotDeclaredCannotBeRead() {
        ClaimReader reader = new ClaimReader(json("{\"a\":\"x\"}"), Set.of("a"));

        assertInvalid(() -> reader.text("b"));
        assertInvalid(() -> reader.epochSecond("b"));
        assertInvalid(() -> reader.texts("b"));
        assertInvalid(() -> reader.uuid("b"));
    }

    // --- text ---------------------------------------------------------------------------------------

    @Test
    void aTextIsReadOnlyFromAStringNode() {
        assertThat(reader("{\"a\":\"value\"}").text("a")).isEqualTo("value");
        assertThat(reader("{\"a\":\"\"}").text("a")).isEmpty();
        assertInvalid(() -> reader("{\"a\":7}").text("a"));
        assertInvalid(() -> reader("{\"a\":true}").text("a"));
        assertInvalid(() -> reader("{\"a\":null}").text("a"));
        assertInvalid(() -> reader("{\"a\":[\"x\"]}").text("a"));
    }

    @Test
    void anExpectedTextMustMatchExactly() {
        ClaimReader reader = reader("{\"a\":\"value\"}");

        reader.requireText("a", "value");
        assertInvalid(() -> reader.requireText("a", "Value"));
    }

    // --- epochSecond [I212, I213] -----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "1760263800", "253402300798", "253402300799"})
    void anIntegralNumberInsideTheRangeIsRead(String literal) {
        assertThat(reader("{\"t\":" + literal + "}").epochSecond("t")).isEqualTo(Long.parseLong(literal));
    }

    @Test
    void theTwoEdgesOfTheRangeAreExact() {
        assertThat(reader("{\"t\":0}").epochSecond("t")).isZero();
        assertThat(reader("{\"t\":" + LAST_EPOCH_SECOND + "}").epochSecond("t"))
                .isEqualTo(LAST_EPOCH_SECOND);
        assertInvalid(() -> reader("{\"t\":-1}").epochSecond("t"));
        assertInvalid(() -> reader("{\"t\":" + (LAST_EPOCH_SECOND + 1) + "}").epochSecond("t"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-9223372036854775808", "9223372036854775807", "9223372036854775808",
            "99999999999999999999999"})
    void theExtremesOfLongAndBeyondAreInvalid(String literal) {
        assertInvalid(() -> reader("{\"t\":" + literal + "}").epochSecond("t"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.5", "1760263800.0", "1.7602638E9", "\"1760263800\"", "true", "null",
            "[1760263800]"})
    void aNumberThatIsNotIntegralOrAValueThatIsNotANumberIsInvalid(String literal) {
        assertInvalid(() -> reader("{\"t\":" + literal + "}").epochSecond("t"));
    }

    // --- texts ---------------------------------------------------------------------------------------

    @Test
    void aListOfTextsIsReadOnlyFromAnArrayOfStrings() {
        assertThat(reader("{\"l\":[\"a\",\"b\"]}").texts("l")).containsExactly("a", "b");
        assertThat(reader("{\"l\":[]}").texts("l")).isEqualTo(List.of());
        assertInvalid(() -> reader("{\"l\":\"a\"}").texts("l"));
        assertInvalid(() -> reader("{\"l\":{\"0\":\"a\"}}").texts("l"));
        assertInvalid(() -> reader("{\"l\":null}").texts("l"));
        assertInvalid(() -> reader("{\"l\":[\"a\",1]}").texts("l"));
        assertInvalid(() -> reader("{\"l\":[1,\"a\"]}").texts("l"));
        assertInvalid(() -> reader("{\"l\":[null]}").texts("l"));
    }

    // --- uuid ----------------------------------------------------------------------------------------

    @Test
    void aUuidMustBeAStringInTheCanonicalLowerCaseForm() {
        String canonical = "123e4567-e89b-12d3-a456-426614174000";

        assertThat(reader("{\"u\":\"" + canonical + "\"}").uuid("u").toString()).isEqualTo(canonical);
        assertInvalid(() -> reader("{\"u\":\"" + canonical.toUpperCase() + "\"}").uuid("u"));
        assertInvalid(() -> reader("{\"u\":\"not-a-uuid\"}").uuid("u"));
        assertInvalid(() -> reader("{\"u\":\"1-1-1-1-1\"}").uuid("u"));
        assertInvalid(() -> reader("{\"u\":7}").uuid("u"));
    }

    // --- the time rules -----------------------------------------------------------------------------

    @Test
    void theWindowIsExactlyTheLifetimeWithNoToleranceOnExpiry() {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        long issued = now.getEpochSecond();
        Duration lifetime = Duration.ofMinutes(10);

        assertThatCode(() -> ClaimReader.requireWindow(issued, issued + 600, lifetime, now))
                .doesNotThrowAnyException();
        assertInvalid(() -> ClaimReader.requireWindow(issued, issued + 601, lifetime, now));
        assertInvalid(() -> ClaimReader.requireWindow(issued, issued + 599, lifetime, now));
        assertThatCode(() -> ClaimReader.requireWindow(issued + 60, issued + 660, lifetime, now))
                .doesNotThrowAnyException();
        assertInvalid(() -> ClaimReader.requireWindow(issued + 61, issued + 661, lifetime, now));
        assertThatThrownBy(() -> ClaimReader.requireWindow(issued - 600, issued, lifetime, now))
                .isInstanceOfSatisfying(TokenRejectedException.class,
                        e -> assertThat(e.rejection()).isEqualTo(TokenRejection.EXPIRED));
        assertThatCode(() -> ClaimReader.requireWindow(issued - 599, issued + 1, lifetime, now))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }

    private static ClaimReader reader(String payload) {
        JsonNode node = json(payload);
        return new ClaimReader(node, Set.copyOf(node.propertyNames()));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(TokenRejectedException.class,
                e -> assertThat(e.rejection()).isEqualTo(TokenRejection.CLAIMS_INVALID));
    }
}

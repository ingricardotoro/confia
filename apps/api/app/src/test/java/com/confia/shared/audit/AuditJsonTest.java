package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Specs/audit-trail, scenarios AT02, AT03, AT05 and AT06 (session-endpoints-cookie-and-csrf
 * design.md, decision 17): the typed builder of the detail JSON of an audit entry escapes every
 * value, emits members in insertion order without whitespace, admits only text, integers, booleans
 * and instants, and refuses a member name that would ever need escaping.
 */
class AuditJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void anEmptyDocumentIsAnEmptyObject() {
        assertThat(AuditJson.object().toJson()).isEqualTo("{}");
    }

    @Test
    void aTextWithQuotesBackslashNewlineAndNonAsciiRoundTripsThroughAJsonParser() {
        String tricky = "say \"hi\" \\ back\nslash\ttab áñ 中文 😀 / done";

        String json = AuditJson.object().text("note", tricky).toJson();

        JsonNode parsed = JSON.readTree(json);
        assertThat(parsed.get("note").textValue()).isEqualTo(tricky);
        assertThat(parsed.size()).isEqualTo(1);
    }

    @Test
    void everyCharacterOfTheC0RangeIsEscapedAndRoundTrips() {
        StringBuilder all = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            all.append(c);
        }

        String json = AuditJson.object().text("control", all.toString()).toJson();

        assertThat(json).doesNotContainPattern("[\\x00-\\x1f]");
        assertThat(JSON.readTree(json).get("control").textValue()).isEqualTo(all.toString());
    }

    @Test
    void theEscapeIsTheOneTheCanonicalSerializerUses() {
        String value = "a\"b\\c\u0001\b";

        assertThat(AuditJson.object().text("v", value).toJson())
                .isEqualTo("{\"v\":" + CanonicalAuditRowSerializer.canonString(value) + "}");
    }

    @Test
    void numbersBooleansAndInstantsHaveTheirFixedShape() {
        Instant whole = Instant.parse("2026-10-21T10:15:30Z");
        Instant fraction = Instant.parse("2026-10-21T10:15:30.123456Z");

        String json = AuditJson.object()
                .number("zero", 0).number("max", Integer.MAX_VALUE).number("negative", -7)
                .number("big", Long.MAX_VALUE)
                .bool("yes", true).bool("no", false)
                .instant("whole", whole).instant("fraction", fraction)
                .toJson();

        assertThat(json).isEqualTo("{\"zero\":0,\"max\":2147483647,\"negative\":-7,"
                + "\"big\":9223372036854775807,\"yes\":true,\"no\":false,"
                + "\"whole\":\"2026-10-21T10:15:30Z\",\"fraction\":\"2026-10-21T10:15:30.123456Z\"}");
        JsonNode parsed = JSON.readTree(json);
        assertThat(parsed.get("yes").isBoolean()).isTrue();
        assertThat(parsed.get("max").isNumber()).isTrue();
        assertThat(parsed.get("whole").isTextual()).isTrue();
    }

    @Test
    void theSameInsertionOrderGivesTheSameBytesWithoutSpaces() {
        String first = AuditJson.object().text("b", "x").number("a", 1).bool("c", true).toJson();
        String second = AuditJson.object().text("b", "x").number("a", 1).bool("c", true).toJson();

        assertThat(first.getBytes(StandardCharsets.UTF_8))
                .isEqualTo(second.getBytes(StandardCharsets.UTF_8));
        assertThat(first).isEqualTo("{\"b\":\"x\",\"a\":1,\"c\":true}");
        assertThat(first).doesNotContain(" ");
    }

    @Test
    void anotherInsertionOrderIsKeptAndNeverSortedAlphabetically() {
        String reversed = AuditJson.object().bool("c", true).number("a", 1).text("b", "x").toJson();

        assertThat(reversed).isEqualTo("{\"c\":true,\"a\":1,\"b\":\"x\"}");
    }

    @Test
    void aBuilderCanBeReadMoreThanOnceAndKeepsGrowing() {
        AuditJson.Builder builder = AuditJson.object().number("a", 1);

        assertThat(builder.toJson()).isEqualTo("{\"a\":1}");
        assertThat(builder.number("b", 2).toJson()).isEqualTo("{\"a\":1,\"b\":2}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Reason", "second-factor", "second_factor", "", " ", "a b", "1abc",
            "a\"b", "café", "a.b", "ábc"})
    void aMemberNameOutsideTheAllowedShapeIsRefusedByEveryMemberKind(String name) {
        Instant now = Instant.EPOCH;

        assertThatThrownBy(() -> AuditJson.object().text(name, "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditJson.object().number(name, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditJson.object().bool(name, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuditJson.object().instant(name, now))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "aB", "a1", "secondFactor", "remainingUnusedCodes", "zZ09"})
    void aMemberNameOfTheAllowedShapeIsAccepted(String name) {
        assertThat(AuditJson.object().number(name, 1).toJson()).isEqualTo("{\"" + name + "\":1}");
    }

    @Test
    void aNameAddedTwiceIsRefusedEvenAcrossMemberKinds() {
        AuditJson.Builder builder = AuditJson.object().text("reason", "x");

        assertThatThrownBy(() -> builder.text("reason", "y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.number("reason", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.bool("reason", true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.instant("reason", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.toJson()).as("the failed attempts built nothing")
                .isEqualTo("{\"reason\":\"x\"}");
    }

    @Test
    void aNullNameOrValueIsRefused() {
        assertThatThrownBy(() -> AuditJson.object().text(null, "v"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AuditJson.object().text("a", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> AuditJson.object().instant("a", null))
                .isInstanceOf(NullPointerException.class);
    }

    /** AT05: the type system admits text, integers, booleans and instants, and nothing else. */
    @Test
    void theBuilderOffersOnlyTextIntegerBooleanAndInstantMembers() {
        Map<String, List<Class<?>>> members = Arrays.stream(AuditJson.Builder.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .collect(Collectors.toMap(Method::getName,
                        method -> List.of(method.getParameterTypes())));

        assertThat(members).containsOnlyKeys("text", "number", "bool", "instant", "toJson");
        assertThat(members.get("text")).containsExactly(String.class, String.class);
        assertThat(members.get("number")).containsExactly(String.class, long.class);
        assertThat(members.get("bool")).containsExactly(String.class, boolean.class);
        assertThat(members.get("instant")).containsExactly(String.class, Instant.class);
        assertThat(members.get("toJson")).isEmpty();
    }

    @Test
    void theUtilityCannotBeInstantiated() {
        assertThat(Modifier.isFinal(AuditJson.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(AuditJson.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()))).isTrue();
    }
}

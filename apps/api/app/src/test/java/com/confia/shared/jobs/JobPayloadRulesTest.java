package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.RotationDecision;
import com.confia.kernel.Money;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The type rule for the payload of a task (design.md decision 8; scenario BJ52): only types that
 * cannot carry free text. A name rule could never prove that a {@code String} holds no e-mail
 * address; a type rule can.
 */
class JobPayloadRulesTest {

    enum OwnEnum { ONE, TWO }

    record WithUuid(UUID id) { }

    record WithInstant(Instant at) { }

    record WithLocalDate(LocalDate day) { }

    record WithBoolean(boolean flag) { }

    record WithInt(int count) { }

    record WithLong(long count) { }

    record WithOwnEnum(OwnEnum kind) { }

    record Everything(UUID id, Instant at, LocalDate day, boolean flag, int count, long total,
            OwnEnum kind) { }

    record Empty() { }

    record WithString(String email) { }

    record WithBigDecimal(BigDecimal amount) { }

    record WithMoney(Money amount) { }

    record WithList(List<UUID> ids) { }

    record WithMap(Map<String, UUID> ids) { }

    record WithArray(UUID[] ids) { }

    record WithNestedRecord(WithUuid inner) { }

    record WithWrapperInteger(Integer count) { }

    record WithDouble(double ratio) { }

    record WithDuration(Duration length) { }

    record WithForeignEnum(DayOfWeek day) { }

    record WithOtherModuleEnum(RotationDecision decision) { }

    record WithObject(Object anything) { }

    static Stream<Arguments> allowed() {
        return Stream.of(Arguments.of(WithUuid.class, "UUID"), Arguments.of(WithInstant.class, "Instant"),
                Arguments.of(WithLocalDate.class, "LocalDate"), Arguments.of(WithBoolean.class, "boolean"),
                Arguments.of(WithInt.class, "int"), Arguments.of(WithLong.class, "long"),
                Arguments.of(WithOwnEnum.class, "enum of the same module"),
                Arguments.of(Everything.class, "all of them"), Arguments.of(Empty.class, "no components"));
    }

    static Stream<Arguments> forbidden() {
        return Stream.of(Arguments.of(WithString.class, "email", "java.lang.String"),
                Arguments.of(WithBigDecimal.class, "amount", "java.math.BigDecimal"),
                Arguments.of(WithMoney.class, "amount", "com.confia.kernel.Money"),
                Arguments.of(WithList.class, "ids", "java.util.List"),
                Arguments.of(WithMap.class, "ids", "java.util.Map"),
                Arguments.of(WithArray.class, "ids", "java.util.UUID[]"),
                Arguments.of(WithNestedRecord.class, "inner", WithUuid.class.getName()),
                Arguments.of(WithWrapperInteger.class, "count", "java.lang.Integer"),
                Arguments.of(WithDouble.class, "ratio", "double"),
                Arguments.of(WithDuration.class, "length", "java.time.Duration"),
                Arguments.of(WithForeignEnum.class, "day", "java.time.DayOfWeek"),
                Arguments.of(WithOtherModuleEnum.class, "decision", RotationDecision.class.getName()),
                Arguments.of(WithObject.class, "anything", "java.lang.Object"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("allowed")
    void admitsEveryAllowedComponentType(Class<? extends Record> payload, String label) {
        assertThat(JobPayloadRules.violationsOf(payload)).isEmpty();
        assertThatCode(() -> JobPayloadRules.validate(payload)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{2} in {0}")
    @MethodSource("forbidden")
    void rejectsEveryForbiddenComponentTypeNamingTheTypeAndTheComponent(Class<? extends Record> payload,
            String component, String type) {
        assertThat(JobPayloadRules.violationsOf(payload)).singleElement().asString()
                .contains(payload.getName()).contains("'" + component + "'").contains(type);
        assertThatThrownBy(() -> JobPayloadRules.validate(payload)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(payload.getName()).hasMessageContaining("'" + component + "'");
    }

    @Test
    void reportsEveryViolationOfASingleRecord() {
        record Both(String email, BigDecimal amount, UUID id) { }

        assertThat(JobPayloadRules.violationsOf(Both.class)).hasSize(2);
    }

    @Test
    void validatesTheTypeOfAConcretePayloadAgainstTheJobType() {
        JobType<WithUuid> type = new JobType<>("identity.some-job", WithUuid.class,
                new RetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(9)));

        assertThatCode(() -> JobPayloadRules.validate(type, new WithUuid(UUID.randomUUID())))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAJobTypeWhoseDeclaredPayloadClassBreaksTheRule() {
        JobType<WithString> type = new JobType<>("identity.some-job", WithString.class,
                new RetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(9)));

        assertThatThrownBy(() -> JobPayloadRules.validate(type, new WithString("someone@example.org")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("identity.some-job")
                .hasMessageContaining("'email'").satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("someone@example.org"));
    }

    @Test
    void rejectsANullPayload() {
        JobType<WithUuid> type = new JobType<>("identity.some-job", WithUuid.class,
                new RetryPolicy(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(9)));

        assertThatThrownBy(() -> JobPayloadRules.validate(type, null)).isInstanceOf(NullPointerException.class);
    }
}

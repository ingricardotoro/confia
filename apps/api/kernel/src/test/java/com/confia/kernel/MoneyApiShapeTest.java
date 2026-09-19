package com.confia.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Reflection guard: no public member of the finished {@link Money} or {@link Percentage} uses
 * {@code double}, {@code float}, {@link Double} or {@link Float}, as a field type, a method
 * parameter, or a method return type (specs/money/spec.md, "Ausencia de fábrica desde coma
 * flotante", both scenarios). Stands in for the ArchUnit rule deferred to change 4 (design.md,
 * decision 4; proposal decision D3): the first cross-module consumer of {@code Money} does not
 * exist yet, so there is nothing for an ArchUnit rule to scan today.
 */
class MoneyApiShapeTest {

    private static final Set<Class<?>> FORBIDDEN_TYPES =
            Set.of(double.class, float.class, Double.class, Float.class);

    @Test
    void moneyHasNoPublicMemberUsingAFloatingPointType() {
        assertNoFloatingPointMember(Money.class);
    }

    @Test
    void percentageHasNoPublicMemberUsingAFloatingPointType() {
        assertNoFloatingPointMember(Percentage.class);
    }

    private static void assertNoFloatingPointMember(Class<?> type) {
        List<Method> publicMethods = Arrays.stream(type.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(publicMethods).isNotEmpty();
        for (Method method : publicMethods) {
            assertThat(method.getReturnType())
                    .as("%s.%s must not return a floating-point type", type.getSimpleName(),
                            method.getName())
                    .isNotIn(FORBIDDEN_TYPES);
            assertThat(method.getParameterTypes())
                    .as("%s.%s must not accept a floating-point parameter", type.getSimpleName(),
                            method.getName())
                    .noneMatch(FORBIDDEN_TYPES::contains);
        }

        List<Field> publicFields = Arrays.stream(type.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()))
                .toList();
        for (Field field : publicFields) {
            assertThat(field.getType())
                    .as("%s.%s must not be a floating-point type", type.getSimpleName(),
                            field.getName())
                    .isNotIn(FORBIDDEN_TYPES);
        }
    }
}

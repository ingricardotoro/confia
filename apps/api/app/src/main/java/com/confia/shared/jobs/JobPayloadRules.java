package com.confia.shared.jobs;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The type rule for the payload of a task (background-jobs-with-db-scheduler design.md decision 8;
 * ADR-0016 compliance 6; scenario BJ52). A payload is a {@link Record} whose components are only
 * {@link UUID}, {@link Instant}, {@link LocalDate}, {@code boolean}, {@code int}, {@code long} or an
 * {@code enum} of the same module as the payload. Never a {@code String}, a {@code BigDecimal}, a
 * {@code Money}, a collection or another record.
 *
 * <p>A naming rule could never prove that a {@code String} holds no e-mail address; a type rule
 * can, because none of the allowed types can carry free text, a name, an address or an amount. The
 * rule runs when a job is scheduled and, over every registered job type, in the architecture tests.
 */
public final class JobPayloadRules {

    private static final Set<Class<?>> ALLOWED_TYPES = Set.of(UUID.class, Instant.class, LocalDate.class,
            boolean.class, int.class, long.class);
    private static final String BASE_PACKAGE = "com.confia.";

    private JobPayloadRules() {
    }

    /**
     * Every component of {@code payloadClass} that breaks the rule, one message each; empty when the
     * class obeys it. Each message names the class, the component and its type.
     */
    public static List<String> violationsOf(Class<? extends Record> payloadClass) {
        Objects.requireNonNull(payloadClass, "payloadClass");
        List<String> violations = new ArrayList<>();
        for (RecordComponent component : payloadClass.getRecordComponents()) {
            if (!isAllowed(payloadClass, component.getType())) {
                violations.add(payloadClass.getName() + ": component '" + component.getName() + "' of type "
                        + component.getType().getTypeName() + " is not allowed in a job payload");
            }
        }
        return violations;
    }

    /**
     * @throws IllegalArgumentException naming the class and each offending component
     */
    public static void validate(Class<? extends Record> payloadClass) {
        List<String> violations = violationsOf(payloadClass);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", violations));
        }
    }

    /**
     * Checks {@code payload} against the declaration of {@code type}: it must be an instance of the
     * declared class, and that class must obey the rule. The message never repeats a value of the
     * payload.
     *
     * @throws IllegalArgumentException when the payload is of another class or breaks the rule
     */
    public static <T extends Record> void validate(JobType<T> type, T payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        if (!type.payloadClass().isInstance(payload)) {
            throw new IllegalArgumentException(
                    "job type '" + type.name() + "' takes " + type.payloadClass().getName() + " as its payload");
        }
        List<String> violations = violationsOf(type.payloadClass());
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("job type '" + type.name() + "': " + String.join("; ", violations));
        }
    }

    private static boolean isAllowed(Class<?> payloadClass, Class<?> componentType) {
        if (ALLOWED_TYPES.contains(componentType)) {
            return true;
        }
        return componentType.isEnum() && sameModule(payloadClass, componentType);
    }

    /** The module is the first segment after {@code com.confia.}; anything outside it is nobody's. */
    private static boolean sameModule(Class<?> first, Class<?> second) {
        String module = moduleOf(first);
        return module != null && module.equals(moduleOf(second));
    }

    private static String moduleOf(Class<?> type) {
        String name = type.getName();
        if (!name.startsWith(BASE_PACKAGE)) {
            return null;
        }
        String rest = name.substring(BASE_PACKAGE.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? null : rest.substring(0, dot);
    }
}

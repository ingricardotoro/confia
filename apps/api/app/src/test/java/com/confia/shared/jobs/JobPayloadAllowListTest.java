package com.confia.shared.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.jobs.infrastructure.TracedJobSerializer;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The approved list of fields of each job type (ADR-0016 compliance 6; design.md decision 8; scenarios
 * BJ12 and BJ13): the set of JSON keys of a serialized document equals the list written in this test,
 * and {@code meta} has its own list. A new field with no entry breaks the test; so does a registered
 * type with no entry. The registry is read from the real worker context.
 *
 * <p>The first one-time job, {@code identity.password-reset-issuance}, has its entry here (task 3.2,
 * scenarios I237 and BJ13). The fixtures below prove the check can fail: a type that declares {@code email} and
 * another that declares an amount.
 */
class JobPayloadAllowListTest {

    /** The approved payload keys of each registered type; the only place the list is written. */
    static final Map<String, Set<String>> APPROVED = Map.of(
            // The internal identifier of the account, and nothing else (decision 18; scenario I237).
            "identity.password-reset-issuance", Set.of("accountId"));

    static final Set<String> APPROVED_META = Set.of("correlationId", "requestId", "actorId", "actorKind",
            "institutionId", "traceparent");

    record EmailPayload(UUID id, String email) { }

    record AmountPayload(UUID id, BigDecimal amount) { }

    /** An instance of the payload class with a value in every component. */
    static Record sampleOf(Class<? extends Record> payloadClass) {
        try {
            RecordComponent[] components = payloadClass.getRecordComponents();
            Object[] values = new Object[components.length];
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                values[i] = valueOf(types[i]);
            }
            var constructor = payloadClass.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object valueOf(Class<?> type) {
        if (type == UUID.class) {
            return UUID.randomUUID();
        } else if (type == Instant.class) {
            return Instant.now().truncatedTo(ChronoUnit.SECONDS);
        } else if (type == LocalDate.class) {
            return LocalDate.now();
        } else if (type == boolean.class) {
            return true;
        } else if (type == int.class) {
            return 1;
        } else if (type == long.class) {
            return 1L;
        } else if (type == String.class) {
            return "text";
        } else if (type == BigDecimal.class) {
            return BigDecimal.TEN;
        } else if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        throw new IllegalArgumentException("no sample for " + type.getName());
    }

    /** The problems of the registered types against the approved list: unknown keys and missing entries. */
    static List<String> problems(Map<String, Class<? extends Record>> registered,
            Map<String, Set<String>> approved) {
        TracedJobSerializer serializer = new TracedJobSerializer();
        JsonMapper mapper = JsonMapper.builder().build();
        List<String> problems = new ArrayList<>();
        for (var entry : registered.entrySet()) {
            Set<String> allowed = approved.get(entry.getKey());
            if (allowed == null) {
                problems.add("job type " + entry.getKey() + " has no entry in the approved list");
                continue;
            }
            JobMeta meta = new JobMeta(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ActorKind.STAFF,
                    UUID.randomUUID(), "00-trace-01");
            JsonNode document = mapper.readTree(serializer.serialize(entry.getKey(),
                    new TracedJob<>(sampleOf(entry.getValue()), meta)));
            Set<String> payloadKeys = new TreeSet<>(document.get("payload").propertyNames());
            Set<String> metaKeys = new TreeSet<>(document.get("meta").propertyNames());
            for (String key : payloadKeys) {
                if (!allowed.contains(key)) {
                    problems.add("job type " + entry.getKey() + " declares the field '" + key
                            + "' which is not in the approved list");
                }
            }
            if (!payloadKeys.containsAll(allowed)) {
                problems.add("job type " + entry.getKey() + " lacks approved fields " + allowed);
            }
            if (!metaKeys.equals(APPROVED_META)) {
                problems.add("meta of " + entry.getKey() + " has keys " + metaKeys);
            }
        }
        approved.keySet().stream().filter(name -> !registered.containsKey(name))
                .forEach(name -> problems.add("the approved list has an entry for unregistered type " + name));
        return problems;
    }

    @Test
    void everyRegisteredTypeMatchesItsApprovedFieldsExactly() {
        Map<String, Class<? extends Record>> registered = new TreeMap<>();
        for (JobDefinition definition : RegisteredJobs.ofTheWorker()) {
            if (definition instanceof OneTimeJob<?> job) {
                registered.put(job.name(), job.type().payloadClass());
            }
        }

        assertThat(problems(registered, APPROVED)).isEmpty();
        assertThat(registered.keySet()).as("the registry and the list name the same types")
                .isEqualTo(APPROVED.keySet());
    }

    @Test
    void aTypeThatDeclaresAnEmailFailsNamingTheTypeAndTheField() {
        assertThat(problems(Map.of("identity.email-probe", EmailPayload.class),
                Map.of("identity.email-probe", Set.of("id"))))
                .singleElement().asString().contains("identity.email-probe").contains("'email'");
    }

    @Test
    void aTypeThatDeclaresAnAmountFailsNamingTheTypeAndTheField() {
        assertThat(problems(Map.of("identity.amount-probe", AmountPayload.class),
                Map.of("identity.amount-probe", Set.of("id"))))
                .singleElement().asString().contains("identity.amount-probe").contains("'amount'");
    }

    @Test
    void aRegisteredTypeWithoutAnEntryFailsAndSoDoesAnEntryWithoutAType() {
        assertThat(problems(Map.of("identity.email-probe", EmailPayload.class), Map.of()))
                .singleElement().asString().contains("has no entry");
        assertThat(problems(Map.of(), Map.of("identity.ghost", Set.of("id"))))
                .singleElement().asString().contains("unregistered type identity.ghost");
    }

    @Test
    void aTypeThatMatchesItsListPasses() {
        record Fine(UUID id, Instant at) { }

        assertThat(problems(Map.of("identity.fine-probe", Fine.class),
                Map.of("identity.fine-probe", Set.of("id", "at")))).isEmpty();
    }
}

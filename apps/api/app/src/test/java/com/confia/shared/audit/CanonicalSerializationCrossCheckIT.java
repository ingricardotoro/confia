package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.support.SharedPostgresContainer;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Property-based cross-check between the PL/pgSQL canonical serialization
 * ({@code shared_audit_row_hash}, V3__chain_shared_audit_log.sql) and its Java twin
 * ({@link CanonicalAuditRowSerializer}, task 4.3) — design.md decision 6;
 * specs/audit-trail/spec.md, requirement "Reproducibilidad de la serialización canónica entre
 * PL/pgSQL y Java", scenario "Ambas implementaciones producen el mismo hash sobre entradas
 * generadas".
 *
 * <p><strong>No {@code @SpringBootTest}.</strong> Probe S8 (apply-progress.md, task 4.1) confirmed
 * empirically that jqwik's {@code TestEngine} never processes JUnit Jupiter extensions — a jqwik
 * {@code @Property} class cannot extend a {@code @SpringBootTest}-annotated base class and observe
 * a live Spring context. This class therefore does not extend
 * {@code com.confia.support.CommittingPostgresIntegrationTest} or
 * {@code com.confia.support.PostgresIntegrationTest}; it opens plain JDBC connections directly
 * over {@link SharedPostgresContainer#dataSourceFor(String)} (design.md decision 6 and decision
 * 14, both already naming this exact route).
 *
 * <p>Every generated {@link CanonicalAuditRow} exercises the SAME {@code shared_audit_row_hash}
 * SQL function the {@code shared_audit_log_chain()} trigger calls (V3 migration) — never a
 * convenience reimplementation inside this test.
 */
class CanonicalSerializationCrossCheckIT {

    private static final DataSource DATA_SOURCE = SharedPostgresContainer.dataSourceFor("confia_admin_app");

    /**
     * The exact function the {@code shared_audit_log_chain()} trigger calls
     * (V3__chain_shared_audit_log.sql). Every parameter is explicitly cast so a SQL {@code NULL}
     * parameter is never type-ambiguous, matching the function's own declared signature exactly.
     */
    private static final String ROW_HASH_QUERY = """
            select shared_audit_row_hash(
                ?::bytea, ?::bigint, ?::uuid, ?::timestamptz, ?::uuid, ?::text, ?::text,
                ?::inet, ?::text, ?::uuid, ?::text, ?::text, ?::text, ?::text, ?::text,
                ?::jsonb, ?::jsonb, ?::text, ?::uuid)
            """;

    private final CanonicalAuditRowSerializer serializer = new CanonicalAuditRowSerializer();

    @Property
    void pgAndJavaProduceTheSameRowHash(@ForAll("rows") CanonicalAuditRow row) throws SQLException {
        byte[] fromPostgres = rowHashFromPostgres(row);
        byte[] fromJava = serializer.rowHash(row);

        assertThat(fromJava).as("row=%s", row).isEqualTo(fromPostgres);
    }

    private byte[] rowHashFromPostgres(CanonicalAuditRow row) throws SQLException {
        try (Connection connection = DATA_SOURCE.getConnection()) {
            // Proves the hash is independent of session TimeZone (design.md §6.3: occurred_at is
            // encoded in microseconds since the epoch, never ::text, so no session setting can
            // change it — divergence family D8). America/New_York genuinely observes daylight
            // saving; Honduras (America/Tegucigalpa, the server's own zone) has not since 2006.
            try (Statement setTimeZone = connection.createStatement()) {
                setTimeZone.execute("set timezone = 'America/New_York'");
            }
            try (PreparedStatement statement = connection.prepareStatement(ROW_HASH_QUERY)) {
                bind(statement, row);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getBytes(1);
                }
            }
        }
    }

    private void bind(PreparedStatement statement, CanonicalAuditRow row) throws SQLException {
        int i = 1;
        statement.setBytes(i++, row.prevHash());
        statement.setLong(i++, row.id());
        statement.setObject(i++, row.institutionId());
        statement.setObject(i++, row.occurredAt().atOffset(ZoneOffset.UTC));
        statement.setObject(i++, row.actorId());
        statement.setString(i++, row.actorKind());
        statement.setString(i++, row.actorLabel());
        statement.setString(i++, row.sourceIp());
        statement.setString(i++, row.userAgent());
        statement.setObject(i++, row.requestId());
        statement.setString(i++, row.traceId());
        statement.setString(i++, row.action());
        statement.setString(i++, row.entityType());
        statement.setString(i++, row.entityId());
        statement.setString(i++, row.outcome());
        statement.setString(i++, toJsonText(row.beforeValue()));
        statement.setString(i++, toJsonText(row.afterValue()));
        statement.setString(i++, row.reason());
        statement.setObject(i++, row.approverId());
    }

    // ---- JSON-text harness: turns a generated JsonNode tree into the JSON literal bound to a
    // ?::jsonb parameter. Deliberately independent of CanonicalAuditRowSerializer#canonicalJson —
    // this only needs to produce SYNTACTICALLY VALID JSON, not the canonical form, so the
    // production canonicalizer is never used to build its own test input. ----

    private String toJsonText(JsonNode node) {
        return node == null ? null : jsonText(node);
    }

    private String jsonText(JsonNode node) {
        if (node.isNull()) {
            return "null";
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "true" : "false";
        }
        if (node.isNumber()) {
            // Always plain decimal text: PostgreSQL's jsonb literal parser also accepts JSON
            // exponential notation, but writing plain avoids depending on that at all.
            return node.decimalValue().toPlainString();
        }
        if (node.isTextual()) {
            return jsonTextString(node.textValue());
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(jsonText(element));
                first = false;
            }
            return builder.append(']').toString();
        }
        if (node.isObject()) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (String key : node.propertyNames()) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(jsonTextString(key)).append(':').append(jsonText(node.get(key)));
                first = false;
            }
            return builder.append('}').toString();
        }
        throw new IllegalStateException("Unsupported generated JSON node type: " + node.getNodeType());
    }

    private String jsonTextString(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }

    // ---- Generators: one explicit branch per divergence family (design.md §6.5, D1-D11) ----

    @Provide
    Arbitrary<CanonicalAuditRow> rows() {
        Arbitrary<Tuple.Tuple7<byte[], Long, UUID, Instant, UUID, String, String>> groupA = Combinators
                .combine(prevHashes(), ids(), uuids(), occurredAts(), nullableUuids(), actorKinds(),
                        textField())
                .as(Tuple::of);
        Arbitrary<Tuple.Tuple7<String, String, UUID, String, String, String, String>> groupB = Combinators
                .combine(nullableInets(), nullableTextField(), uuids(), nullableTextField(), textField(),
                        textField(), textField())
                .as(Tuple::of);
        Arbitrary<Tuple.Tuple5<String, JsonNode, JsonNode, String, UUID>> groupC = Combinators
                .combine(textField(), jsonValues(), jsonValues(), nullableTextField(), nullableUuids())
                .as(Tuple::of);

        return Combinators.combine(groupA, groupB, groupC)
                .as((a, b, c) -> new CanonicalAuditRow(a.get1(), a.get2(), a.get3(), a.get4(), a.get5(),
                        a.get6(), a.get7(), b.get1(), b.get2(), b.get3(), b.get4(), b.get5(), b.get6(),
                        b.get7(), c.get1(), c.get2(), c.get3(), c.get4(), c.get5()));
    }

    /** 32 bytes, matching {@code shared_audit_log}'s {@code prev_hash} length check. */
    private Arbitrary<byte[]> prevHashes() {
        return Arbitraries.bytes().array(byte[].class).ofSize(32);
    }

    private Arbitrary<Long> ids() {
        return Arbitraries.longs().between(0L, 1_000_000_000_000L);
    }

    private Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    private Arbitrary<UUID> nullableUuids() {
        return uuids().injectNull(0.2);
    }

    private Arbitrary<String> actorKinds() {
        return Arbitraries.of("staff", "guardian", "system");
    }

    /**
     * Family D8 (marcas de tiempo con zona): random instants across a wide range with genuine
     * microsecond precision, plus curated edge cases (the Unix epoch itself, an instant before
     * 1970, and the 2024 America/New_York daylight-saving transition instants). Every instant is
     * bound as an absolute point in time ({@link java.time.OffsetDateTime}), never formatted text,
     * so none of this depends on which session TimeZone {@link #rowHashFromPostgres} happens to
     * set.
     */
    private Arbitrary<Instant> occurredAts() {
        long minEpochSecond = Instant.parse("1900-01-01T00:00:00Z").getEpochSecond();
        long maxEpochSecond = Instant.parse("2100-01-01T00:00:00Z").getEpochSecond();
        Arbitrary<Instant> random = Combinators
                .combine(Arbitraries.longs().between(minEpochSecond, maxEpochSecond),
                        Arbitraries.longs().between(0, 999_999))
                .as((seconds, micros) -> Instant.ofEpochSecond(seconds, micros * 1000));
        Arbitrary<Instant> curated = Arbitraries.of(Instant.EPOCH,
                Instant.parse("1969-12-31T23:59:59.999999Z"), Instant.parse("2024-03-10T06:59:59Z"),
                Instant.parse("2024-03-10T07:00:00Z"), Instant.parse("2024-11-03T05:59:59Z"),
                Instant.parse("2024-11-03T06:00:00Z"));
        return Arbitraries.oneOf(random, curated);
    }

    /**
     * Families D3 (texto no ASCII), D4 (control y comillas), D5 (vacío) and D11 (longitud en bytes
     * frente a caracteres) — every branch is an explicit case, not a statistical hope.
     */
    private Arbitrary<String> textField() {
        Arbitrary<String> ascii = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(0).ofMaxLength(48);
        Arbitrary<String> nonAscii = Arbitraries.of("Ελληνικά", "العربية", "中文测试内容", "😀", "𝔘𝔫𝔦𝔠𝔬𝔡𝔢",
                "é", "é", "áb́ć", "Ｚ", "Über");
        Arbitrary<String> controlAndQuotes = Arbitraries.of("\"quoted\"", "back\\slash", "forward/slash",
                "line\nbreak", "tab\there", "\r\ncarriage", "",
                "mixed\"\\\n\tend");
        Arbitrary<String> empty = Arbitraries.just("");
        return Arbitraries.oneOf(ascii, nonAscii, controlAndQuotes, empty);
    }

    private Arbitrary<String> nullableTextField() {
        return textField().injectNull(0.2);
    }

    /** Family D9 (formas de inet): curated, already-canonical PostgreSQL {@code host()} forms. */
    private Arbitrary<String> inets() {
        return Arbitraries.of("192.168.1.1", "192.168.1.0/24", "10.0.0.255/32", "0.0.0.0", "::1",
                "2001:db8::1", "2001:db8::1/64", "::ffff:192.0.2.1", "fe80::1/64");
    }

    private Arbitrary<String> nullableInets() {
        return inets().injectNull(0.2);
    }

    /**
     * Families D1 (escala), D2 (magnitud), D5 (vacío/nulo dentro de JSON), D6 (orden de claves) y
     * D7 (claves repetidas y anidamiento). {@code injectNull} at the top level produces the actual
     * SQL-{@code NULL} case for {@code before_value}/{@code after_value}; {@link NullNode} inside
     * the tree produces the distinct JSON-{@code null}-literal case.
     */
    private Arbitrary<JsonNode> jsonValues() {
        return jsonNode(3).injectNull(0.15);
    }

    private Arbitrary<JsonNode> jsonNode(int depth) {
        Arbitrary<JsonNode> leaf = jsonLeaf();
        if (depth <= 0) {
            return leaf;
        }
        Arbitrary<JsonNode> array = jsonNode(depth - 1).list().ofMaxSize(4).map(elements -> {
            ArrayNode arrayNode = JsonNodeFactory.instance.arrayNode();
            elements.forEach(arrayNode::add);
            return (JsonNode) arrayNode;
        });
        Arbitrary<JsonNode> object = objectKeys().list().ofMaxSize(4).uniqueElements().flatMap(keys -> {
            List<Arbitrary<JsonNode>> valueArbitraries = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                valueArbitraries.add(jsonNode(depth - 1));
            }
            return Combinators.combine(valueArbitraries).as(values -> {
                ObjectNode objectNode = JsonNodeFactory.instance.objectNode();
                for (int i = 0; i < keys.size(); i++) {
                    objectNode.set(keys.get(i), values.get(i));
                }
                return (JsonNode) objectNode;
            });
        });
        return Arbitraries.oneOf(leaf, array, object);
    }

    private Arbitrary<JsonNode> jsonLeaf() {
        Arbitrary<JsonNode> nullLiteral = Arbitraries.just((JsonNode) NullNode.instance);
        Arbitrary<JsonNode> booleans = Arbitraries.of(true, false).map(v -> (JsonNode) BooleanNode.valueOf(v));
        Arbitrary<JsonNode> numbers = numberValues().map(v -> (JsonNode) new DecimalNode(v));
        Arbitrary<JsonNode> strings = textField().map(v -> (JsonNode) new StringNode(v));
        return Arbitraries.oneOf(nullLiteral, booleans, numbers, strings);
    }

    /** Family D6: keys that order oppositely by UTF-8 bytes and by UTF-16 code units, keys that
     * differ only by length, and the empty key. */
    private Arbitrary<String> objectKeys() {
        return Arbitraries.oneOf(
                Arbitraries.of("a", "aa", "ab", "", "z", "Z", "Ｚ", "😀", "key", "clave"),
                textField());
    }

    /** Families D1 (escala) y D2 (magnitud). */
    private Arbitrary<BigDecimal> numberValues() {
        Arbitrary<BigDecimal> curatedScale = Arbitraries
                .of("0", "1", "1.0", "1.000", "0.10", "-0", "-0.0", "100", "0.1", "0.2", "-1.50")
                .map(BigDecimal::new);
        Arbitrary<BigDecimal> exponential = Arbitraries.of("1e2", "1E+2", "1e-2", "1.5e10", "-2.5e-6")
                .map(BigDecimal::new);
        Arbitrary<BigDecimal> magnitude = Arbitraries.oneOf(
                Arbitraries.strings().withCharRange('1', '9').ofLength(1)
                        .flatMap(first -> Arbitraries.strings().withCharRange('0', '9').ofLength(39)
                                .map(rest -> new BigDecimal(first + rest))),
                Arbitraries.of("1e300", "1e-300", "-1e300").map(BigDecimal::new),
                Arbitraries.strings().withCharRange('0', '9').ofLength(30)
                        .map(digits -> new BigDecimal("0." + digits)));
        Arbitrary<BigDecimal> random = Arbitraries.bigDecimals().ofScale(4)
                .between(new BigDecimal("-1000000"), new BigDecimal("1000000"));
        return Arbitraries.oneOf(curatedScale, exponential, magnitude, random);
    }
}

package com.confia.shared.audit;

import com.confia.shared.security.Digests;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The Java twin of {@code shared_audit_canonical_json}, {@code shared_audit_row_preimage} and
 * {@code shared_audit_row_hash} (V3__chain_shared_audit_log.sql; design.md decision 6, §6.1-§6.4).
 * A silent divergence between this class and the SQL functions it mirrors produces false positives
 * of tampering, not a loud failure — {@link CanonicalSerializationCrossCheckIT} is the property
 * that keeps the two honest, calling the real SQL function on one side and this class on the
 * other, never a convenience reimplementation of either.
 *
 * <p><strong>Two deliberate deviations from RFC 8785</strong> (design.md §6.4, "Dos desviaciones
 * de RFC 8785, declaradas"), written here and in the SQL function's own comment so a reader of
 * either implementation finds the other and the reason for the difference:
 *
 * <ol>
 *   <li>Object keys order by their <em>unsigned UTF-8 byte sequence</em>
 *       ({@link #compareObjectKeys}), never by {@link String#compareTo} (UTF-16 code unit order).
 *       {@code ORDER BY convert_to(k, 'UTF8')} on the PL/pgSQL side is the same comparison.
 *   <li>Numbers canonicalize as exact {@link BigDecimal} text ({@link #canonNumber}), never as an
 *       ECMAScript double: {@code jsonb} stores {@code numeric} exactly, and forcing it through a
 *       64-bit double would lose precision in a control over money.
 * </ol>
 *
 * <p>Key ordering is a protected, overridable hook purely so the permanent divergence fixture
 * {@code com.confia.shared.audit.fixture.Utf16OrderingCanonicalAuditRowSerializer} (design.md
 * §6.5's deterministic pair) can substitute the deliberately wrong order without duplicating the
 * rest of this class. Production code never overrides it.
 */
public class CanonicalAuditRowSerializer {

    private static final byte[] FORMAT_TAG = "confia.audit.v1".getBytes(StandardCharsets.UTF_8);
    private static final byte ABSENT = 0x00;
    private static final byte PRESENT = 0x01;

    /** {@code sha256(preimage(row))} — the exact composition {@code shared_audit_row_hash} uses. */
    public byte[] rowHash(CanonicalAuditRow row) {
        return Digests.sha256(preimage(row));
    }

    /**
     * The preimage of design.md §6.2: the format tag, the raw 32-byte {@code prev_hash}, then the
     * 18 signed fields, each {@code F(v)}-encoded by {@link #writeField}, in the exact order
     * {@code shared_audit_row_preimage} writes them.
     */
    public byte[] preimage(CanonicalAuditRow row) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(FORMAT_TAG);
        out.writeBytes(row.prevHash());
        writeField(out, canonLong(row.id()));
        writeField(out, canonUuid(row.institutionId()));
        writeField(out, canonInstant(row.occurredAt()));
        writeField(out, canonUuid(row.actorId()));
        writeField(out, row.actorKind());
        writeField(out, row.actorLabel());
        writeField(out, canonInet(row.sourceIp()));
        writeField(out, row.userAgent());
        writeField(out, canonUuid(row.requestId()));
        writeField(out, row.traceId());
        writeField(out, row.action());
        writeField(out, row.entityType());
        writeField(out, row.entityId());
        writeField(out, row.outcome());
        writeField(out, row.beforeValue() == null ? null : canonicalJson(row.beforeValue()));
        writeField(out, row.afterValue() == null ? null : canonicalJson(row.afterValue()));
        writeField(out, row.reason());
        writeField(out, canonUuid(row.approverId()));
        return out.toByteArray();
    }

    /**
     * The recursive JSON canonicalization of design.md §6.4, mirroring
     * {@code shared_audit_canonical_json}: no whitespace anywhere, numbers as exact trimmed
     * decimal text (never exponential, {@code -0} normalized to {@code 0}), strings escaped like
     * RFC 8785 ({@code to_json(text)::text} already matches this — sonda S1), array elements in
     * original order, object keys sorted by {@link #compareObjectKeys}.
     *
     * <p>Public — not only so the divergence fixture can call it directly on the same input the
     * cross-check property compares, but also because a future reader of an already-parsed
     * {@code before_value}/{@code after_value} (task 5.3) needs to canonicalize a bare {@link
     * JsonNode} without building a full {@link CanonicalAuditRow}.
     */
    public String canonicalJson(JsonNode node) {
        if (node.isNull()) {
            return "null";
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? "true" : "false";
        }
        if (node.isNumber()) {
            return canonNumber(node.decimalValue());
        }
        if (node.isTextual()) {
            return canonString(node.textValue());
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder("[");
            boolean first = true;
            for (JsonNode element : node) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(canonicalJson(element));
                first = false;
            }
            return builder.append(']').toString();
        }
        if (node.isObject()) {
            List<String> keys = new ArrayList<>(node.propertyNames());
            keys.sort(this::compareObjectKeys);
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (String key : keys) {
                if (!first) {
                    builder.append(',');
                }
                builder.append(canonString(key)).append(':').append(canonicalJson(node.get(key)));
                first = false;
            }
            return builder.append('}').toString();
        }
        throw new IllegalArgumentException("Unsupported JSON node type for canonicalization: "
                + node.getNodeType());
    }

    /**
     * Ascending order by the unsigned UTF-8 byte sequence of each key (design.md §6.4, deviation
     * 1) — {@code memcmp} semantics, independent of any Java or database collation.
     * {@code ORDER BY convert_to(k, 'UTF8')} on the PL/pgSQL side is the exact same comparison.
     * NEVER {@link String#compareTo}, which orders by UTF-16 code unit and diverges from this on
     * the deterministic pair {@code "Ｚ"}/{@code "😀"} (design.md §6.5).
     */
    protected int compareObjectKeys(String a, String b) {
        byte[] bytesA = a.getBytes(StandardCharsets.UTF_8);
        byte[] bytesB = b.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytesA.length, bytesB.length);
        for (int i = 0; i < length; i++) {
            int comparison = Byte.compareUnsigned(bytesA[i], bytesB[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(bytesA.length, bytesB.length);
    }

    /**
     * {@code F(v)} of design.md §6.2: {@code 0x00} if {@code canon} is absent (the column was SQL
     * {@code NULL}), else {@code 0x01 || int8_be(byte length) || utf8(canon)}. The eight-byte
     * length is network-order, matching PostgreSQL's {@code int8send(bigint)} (sonda S6).
     */
    private static void writeField(ByteArrayOutputStream out, String canon) {
        if (canon == null) {
            out.write(ABSENT);
            return;
        }
        out.write(PRESENT);
        byte[] utf8 = canon.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(Long.BYTES).putLong(utf8.length).array());
        out.writeBytes(utf8);
    }

    /**
     * Trimmed exact decimal text (design.md §6.4 "número"): no leading {@code +}, no leading
     * zeros beyond a single {@code 0}, no trailing fractional zeros, no decimal point without a
     * fraction, never exponential notation, {@code -0} normalized to {@code 0}. {@link
     * BigDecimal#stripTrailingZeros()} followed by {@link BigDecimal#toPlainString()} matches
     * PostgreSQL's {@code trim_scale(numeric)::text} (numeric text output is never exponential —
     * verified directly against the real engine, not assumed).
     */
    private static String canonNumber(BigDecimal value) {
        if (value.compareTo(BigDecimal.ZERO) == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * RFC 8785 string escaping, with design.md §6.4's own two exceptions already built in by
     * construction: {@code /} is never escaped, and no character at or above {@code U+0020} is
     * escaped regardless of ASCII-ness (verified against {@code to_json(text)::text} by sonda S1 —
     * it escapes only {@code "}, {@code \}, and the C0 control range with a lowercase four-hex-digit
     * escape (U+0000-U+001F)
     * for the characters with no short form).
     */
    private static String canonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        // Built without ever writing a literal backslash immediately followed by
                        // 'u' in source: that raw two-character sequence is intercepted by javac's
                        // own unicode-escape prescan (JLS 3.3) before tokenization even runs, and
                        // fails to compile once anything other than four hex digits follows it.
                        result.append('\\').append('u').append(String.format("%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    /**
     * design.md §6.3: address plus mask, always explicit. Never reformats or recompresses the
     * address itself — {@code value} is expected to already be in the form PostgreSQL's own
     * {@code host(v)} would produce; this only appends the mask when {@code value} does not
     * already carry one ({@code /32} for an address with no {@code ':'}, {@code /128} otherwise).
     */
    private static String canonInet(String value) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('/') >= 0) {
            return value;
        }
        return value.indexOf(':') >= 0 ? value + "/128" : value + "/32";
    }

    /**
     * design.md §6.3: microseconds since the Unix epoch, a signed decimal integer — never
     * {@code timestamptz::text}, which depends on session {@code TimeZone}/{@code DateStyle}.
     */
    private static String canonInstant(Instant value) {
        return Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, value));
    }

    private static String canonLong(long value) {
        return Long.toString(value);
    }

    private static String canonUuid(UUID value) {
        return value == null ? null : value.toString();
    }
}

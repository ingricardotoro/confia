package com.confia.shared.security;

import com.confia.shared.audit.CanonicalAuditRowSerializer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Hashes a request payload's canonicalized JSON form so a repeated idempotency key can be rejected
 * against a different payload without ever comparing raw text (design.md, decision 8;
 * specs/build-integrity/spec.md, requirement "Rechazo de la misma clave con carga útil distinta,
 * comparada por hash canonicalizado").
 *
 * <p><strong>Delegates canonicalization, never duplicates it.</strong> The canonical JSON form
 * this class hashes is produced by {@link CanonicalAuditRowSerializer#canonicalJson(JsonNode)},
 * the same class {@code shared_audit_log}'s tamper-evidence chain already uses and that
 * {@code CanonicalSerializationCrossCheckIT} already verifies by property against the real
 * PostgreSQL engine. Writing a second canonicalization here would be exactly the divergence risk
 * design.md decision 8 names and rejects; a reader of either class finds the other in this
 * Javadoc and in {@link CanonicalAuditRowSerializer}'s own class Javadoc.
 *
 * <p>{@link #FORMAT_VERSION} is the first bytes of the preimage, not decoration: it marks this
 * hash as an idempotency hash rather than an audit hash on sight, and gives a deliberate,
 * explicit break point if the rule ever needs to change without invalidating every record already
 * on file (design.md, decision 8).
 */
public final class RequestPayloadHasher {

    public static final String FORMAT_VERSION = "confia.idempotency.v1";

    private static final HexFormat HEX = HexFormat.of();

    /**
     * {@code hex(sha256(utf8(FORMAT_VERSION) || utf8(canonicalJson(payload))))}, lowercase
     * (design.md, section 6.4). A request with no body passes the JSON null literal, whose
     * canonical form is {@code "null"} — this method does not special-case it. A Java {@code null}
     * reference is a programming error, not an absent body, and is rejected with {@link
     * NullPointerException} rather than producing a silent result (ADR-0019, point 6).
     */
    public String hash(JsonNode payload) {
        Objects.requireNonNull(payload, "payload");
        String canonicalJson = new CanonicalAuditRowSerializer().canonicalJson(payload);
        byte[] preimage = concat(
                FORMAT_VERSION.getBytes(StandardCharsets.UTF_8),
                canonicalJson.getBytes(StandardCharsets.UTF_8));
        return HEX.formatHex(Digests.sha256(preimage));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}

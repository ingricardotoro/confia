package com.confia.shared.security.token;

import static com.confia.shared.security.token.JwsFixtures.CURRENT_KID;
import static com.confia.shared.security.token.JwsFixtures.PREVIOUS_KID;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the access-token tests share: one ring with a signing key and a verify-only key, a foreign key
 * that is not in it, a clock stopped at a chosen instant, and the claims of a valid token as an
 * ordered map that a test edits (removes a claim, changes a type, adds one) before signing it with the
 * JDK's own signer. A token built here never passes through the issuer under test, so a defect in the
 * issuer cannot hide in the test of the verifier.
 */
final class AccessTokenFixtures {

    /** 10:00:00 on 2026-10-12, the instant the scenarios of the specification use. */
    static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    static final KeyPair CURRENT = JwsFixtures.newPair();
    static final KeyPair PREVIOUS = JwsFixtures.newPair();
    static final KeyPair FOREIGN = JwsFixtures.newPair();
    static final String FOREIGN_KID = "portal-2026a";

    static final SigningKeyRing RING = SigningKeyRing.of(
            SigningKey.signing(CURRENT_KID, CURRENT.getPublic(), CURRENT.getPrivate()),
            SigningKey.verifyOnly(PREVIOUS_KID, PREVIOUS.getPublic()));
    static final CompactJws JWS = new CompactJws(RING);

    static final UUID ACCOUNT = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");
    static final UUID INSTITUTION = UUID.fromString("c3a8e9a0-5b1e-4f7e-9d0e-2b6f0a1c4d11");
    static final UUID SESSION = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5");
    static final UUID TOKEN_ID = UUID.fromString("9b2e7c4a-1d3f-4a5b-86c7-d8e9f0a1b2c3");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AccessTokenFixtures() {
    }

    static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    static AccessTokenVerifier verifierAt(Instant instant) {
        return new AccessTokenVerifier(JWS, clockAt(instant));
    }

    static AccessTokenIssuer issuerAt(Instant instant) {
        return new AccessTokenIssuer(JWS, clockAt(instant));
    }

    /** The nine claims of a valid access token issued at {@code issuedAt}, in the order of the design. */
    static Map<String, Object> accessClaims(Instant issuedAt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "confia-admin");
        claims.put("aud", "confia-admin");
        claims.put("sub", ACCOUNT.toString());
        claims.put("exp", issuedAt.getEpochSecond() + 600);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("jti", TOKEN_ID.toString());
        claims.put("sid", SESSION.toString());
        claims.put("tenant", INSTITUTION.toString());
        claims.put("amr", List.of("pwd"));
        return claims;
    }

    /** The nine claims of a valid restricted MFA token issued at {@code issuedAt}. */
    static Map<String, Object> mfaClaims(Instant issuedAt, String purpose) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "confia-admin");
        claims.put("aud", "confia-admin-mfa");
        claims.put("sub", ACCOUNT.toString());
        claims.put("exp", issuedAt.getEpochSecond() + 300);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("jti", TOKEN_ID.toString());
        claims.put("tenant", INSTITUTION.toString());
        claims.put("amr", List.of("pwd"));
        claims.put("purpose", purpose);
        return claims;
    }

    static Map<String, Object> without(Map<String, Object> claims, String name) {
        Map<String, Object> copy = new LinkedHashMap<>(claims);
        copy.remove(name);
        return copy;
    }

    static Map<String, Object> with(Map<String, Object> claims, String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(claims);
        copy.put(name, value);
        return copy;
    }

    static String json(Map<String, Object> claims) {
        return JSON.writeValueAsString(claims);
    }

    /** A token with a valid signature of the current administrative key over exactly these claims. */
    static String signed(Map<String, Object> claims) {
        return signedText(json(claims));
    }

    static String signedText(String payloadJson) {
        return JwsFixtures.issuedToken(CURRENT_KID, payloadJson, CURRENT.getPrivate());
    }

    static String signedBy(String kid, PrivateKey key, Map<String, Object> claims) {
        return JwsFixtures.issuedToken(kid, json(claims), key);
    }

    static JsonNode payloadOf(String token) {
        return JSON.readTree(segment(token, 1));
    }

    static String payloadTextOf(String token) {
        return new String(segment(token, 1), StandardCharsets.UTF_8);
    }

    static String headerTextOf(String token) {
        return new String(segment(token, 0), StandardCharsets.UTF_8);
    }

    private static byte[] segment(String token, int index) {
        return Base64.getUrlDecoder().decode(token.split("[.]")[index]);
    }

    static List<String> namesOf(JsonNode object) {
        return List.copyOf(object.propertyNames());
    }
}

package com.confia.shared.security.token;

import static com.confia.shared.security.token.JwsFixtures.CURRENT_KID;
import static com.confia.shared.security.token.JwsFixtures.PREVIOUS_KID;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the access-token tests share: one ring with a signing key and a verify-only key, a clock
 * stopped at a chosen instant, and the helpers that decode a compact token back into its header and
 * payload with the JDK's own base64 decoder, so that what a test asserts is what a client would see on
 * the wire and not what the issuer believes it wrote.
 */
final class AccessTokenFixtures {

    /** 10:00:00 on 2026-10-12, the instant the scenarios of the specification use. */
    static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    static final KeyPair CURRENT = JwsFixtures.newPair();
    static final KeyPair PREVIOUS = JwsFixtures.newPair();

    static final SigningKeyRing RING = SigningKeyRing.of(
            SigningKey.signing(CURRENT_KID, CURRENT.getPublic(), CURRENT.getPrivate()),
            SigningKey.verifyOnly(PREVIOUS_KID, PREVIOUS.getPublic()));
    static final CompactJws JWS = new CompactJws(RING);

    static final UUID ACCOUNT = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");
    static final UUID INSTITUTION = UUID.fromString("c3a8e9a0-5b1e-4f7e-9d0e-2b6f0a1c4d11");
    static final UUID SESSION = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AccessTokenFixtures() {
    }

    static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    static AccessTokenIssuer issuerAt(Instant instant) {
        return new AccessTokenIssuer(JWS, clockAt(instant));
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

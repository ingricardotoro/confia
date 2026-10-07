package com.confia.shared.security.token;

import com.confia.shared.security.AuthenticationMethod;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The claims of the administrative access token in one place
 * (session-tokens-and-web-layer design.md, decision 4): exactly {@code iss}, {@code aud}, {@code sub},
 * {@code exp}, {@code iat}, {@code jti}, {@code sid}, {@code tenant} and {@code amr}. There is no
 * {@code permissions} claim (owner decision 11: an empty set could be read as authoritative) and no
 * personal datum. The verifier will read the same list from here, which keeps the two from drifting
 * apart.
 */
final class AccessTokenClaims {

    /** The issuer of the administrative tokens. */
    static final String ISSUER = "confia-admin";

    /** The audience of the access token. */
    static final String AUDIENCE = "confia-admin";

    /** The exact life of the access token. */
    static final Duration LIFETIME = Duration.ofSeconds(600);

    private AccessTokenClaims() {
    }

    /**
     * The payload of a new access token: members in the order of the design, without spaces. Every
     * value is a UUID, a whole number or a constant, so none needs escaping.
     *
     * @param methods must contain {@link AuthenticationMethod#PASSWORD}
     */
    static byte[] serialize(UUID accountId, UUID institutionId, UUID sessionId, UUID tokenId,
            Set<AuthenticationMethod> methods, Instant issuedAt) {
        if (!methods.contains(AuthenticationMethod.PASSWORD)) {
            throw new IllegalArgumentException("a session always starts with the password");
        }
        List<String> amr = new ArrayList<>();
        for (AuthenticationMethod method : AuthenticationMethod.values()) {
            if (methods.contains(method)) {
                amr.add("\"" + method.claimValue() + "\"");
            }
        }
        String json = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + accountId
                + "\",\"exp\":" + issuedAt.plus(LIFETIME).getEpochSecond() + ",\"iat\":"
                + issuedAt.getEpochSecond() + ",\"jti\":\"" + tokenId + "\",\"sid\":\"" + sessionId
                + "\",\"tenant\":\"" + institutionId + "\",\"amr\":[" + String.join(",", amr) + "]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}

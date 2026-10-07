package com.confia.shared.security.token;

import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The claims of the administrative access token in one place, to write and to read
 * (session-tokens-and-web-layer design.md, decision 4): exactly {@code iss}, {@code aud}, {@code sub},
 * {@code exp}, {@code iat}, {@code jti}, {@code sid}, {@code tenant} and {@code amr}. There is no
 * {@code permissions} claim (owner decision 11: an empty set could be read as authoritative) and no
 * personal datum. Keeping the two directions side by side is what keeps the issuer and the verifier
 * from drifting apart.
 */
final class AccessTokenClaims {

    /** The issuer of the administrative tokens. */
    static final String ISSUER = "confia-admin";

    /** The audience of the access token. */
    static final String AUDIENCE = "confia-admin";

    /** The exact life of the access token. */
    static final Duration LIFETIME = Duration.ofSeconds(600);

    private static final String PASSWORD = AuthenticationMethod.PASSWORD.claimValue();
    private static final String OTP = AuthenticationMethod.ONE_TIME_PASSWORD.claimValue();

    private static final Set<String> NAMES = Set.of("iss", "aud", "sub", "exp", "iat", "jti", "sid",
            "tenant", "amr");

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

    /**
     * Reads the claims of a token whose signature has verified, applying every rule of the design.
     *
     * @throws TokenRejectedException {@code CLAIMS_INVALID} for any breach of the closed list, the
     *     types, the issuer, the audience or the time rules, and {@code EXPIRED} only when everything
     *     else is intact and {@code exp} has passed
     */
    static AuthenticatedActor read(JsonNode claims, Instant now) {
        ClaimReader reader = new ClaimReader(claims, NAMES);
        reader.requireText("iss", ISSUER);
        reader.requireText("aud", AUDIENCE);
        UUID account = reader.uuid("sub");
        UUID tokenId = reader.uuid("jti");
        UUID session = reader.uuid("sid");
        UUID institution = reader.uuid("tenant");
        Set<AuthenticationMethod> methods = methodsOf(reader);
        long issuedAt = reader.epochSecond("iat");
        long expiresAt = reader.epochSecond("exp");
        ClaimReader.requireWindow(issuedAt, expiresAt, LIFETIME, now);
        return new AuthenticatedActor(account, institution, session, tokenId, methods,
                Instant.ofEpochSecond(issuedAt), Instant.ofEpochSecond(expiresAt));
    }

    /** {@code amr} is {@code ["pwd"]} or {@code ["pwd","otp"]}, in that order and nothing else. */
    private static Set<AuthenticationMethod> methodsOf(ClaimReader reader) {
        List<String> amr = reader.texts("amr");
        if (amr.equals(List.of(PASSWORD))) {
            return Set.of(AuthenticationMethod.PASSWORD);
        }
        if (amr.equals(List.of(PASSWORD, OTP))) {
            return Set.of(AuthenticationMethod.PASSWORD, AuthenticationMethod.ONE_TIME_PASSWORD);
        }
        throw ClaimReader.invalid();
    }
}

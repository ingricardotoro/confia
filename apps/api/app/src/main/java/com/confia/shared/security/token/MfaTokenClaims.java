package com.confia.shared.security.token;

import com.confia.shared.security.AuthenticationMethod;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The claims of the restricted MFA token, after the verifier has accepted it (session-tokens-and-web-layer
 * design.md, decision 4): who is completing the second factor, in which institution, and for what. It is
 * not a session: it has no {@code sid} and issuing it creates no refresh-token family. Its audience is
 * {@code confia-admin-mfa}, so the access verifier rejects it by audience alone.
 *
 * <p>Printing it leaves out the token identifier, which is never written to a log.
 *
 * @param accountId the staff account ({@code sub})
 * @param institutionId the institution ({@code tenant})
 * @param tokenId the identifier of this token ({@code jti})
 * @param purpose what the token may be used for
 * @param issuedAt when it was issued ({@code iat})
 * @param expiresAt when it stops being valid ({@code exp})
 */
public record MfaTokenClaims(UUID accountId, UUID institutionId, UUID tokenId, MfaPurpose purpose,
        Instant issuedAt, Instant expiresAt) {

    /** The audience of the restricted token; the access token's is {@code confia-admin}. */
    static final String AUDIENCE = "confia-admin-mfa";

    /** The exact life of the restricted token: enough to open an authenticator and type a code. */
    static final Duration LIFETIME = Duration.ofSeconds(300);

    private static final Set<String> NAMES = Set.of("iss", "aud", "sub", "exp", "iat", "jti", "tenant",
            "amr", "purpose");

    public MfaTokenClaims {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(institutionId, "institutionId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** The payload of a new restricted token: members in the order of the design, without spaces. */
    static byte[] serialize(UUID accountId, UUID institutionId, UUID tokenId, MfaPurpose purpose,
            Instant issuedAt) {
        String json = "{\"iss\":\"" + AccessTokenClaims.ISSUER + "\",\"aud\":\"" + AUDIENCE
                + "\",\"sub\":\"" + accountId + "\",\"exp\":" + issuedAt.plus(LIFETIME).getEpochSecond()
                + ",\"iat\":" + issuedAt.getEpochSecond() + ",\"jti\":\"" + tokenId
                + "\",\"tenant\":\"" + institutionId + "\",\"amr\":[\""
                + AuthenticationMethod.PASSWORD.claimValue() + "\"],\"purpose\":\""
                + purpose.claimValue() + "\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads the claims of a token whose signature has verified, applying every rule of the design.
     *
     * @throws TokenRejectedException {@code CLAIMS_INVALID} for any breach of the closed list, the
     *     types, the issuer, the audience or the time rules, and {@code EXPIRED} only when everything
     *     else is intact and {@code exp} has passed
     */
    static MfaTokenClaims read(JsonNode claims, Instant now) {
        ClaimReader reader = new ClaimReader(claims, NAMES);
        reader.requireText("iss", AccessTokenClaims.ISSUER);
        reader.requireText("aud", AUDIENCE);
        UUID account = reader.uuid("sub");
        UUID tokenId = reader.uuid("jti");
        UUID institution = reader.uuid("tenant");
        if (!reader.texts("amr").equals(List.of(AuthenticationMethod.PASSWORD.claimValue()))) {
            throw ClaimReader.invalid();
        }
        MfaPurpose purpose = MfaPurpose.fromClaimValue(reader.text("purpose"))
                .orElseThrow(ClaimReader::invalid);
        long issuedAt = reader.epochSecond("iat");
        long expiresAt = reader.epochSecond("exp");
        ClaimReader.requireWindow(issuedAt, expiresAt, LIFETIME, now);
        return new MfaTokenClaims(account, institution, tokenId, purpose,
                Instant.ofEpochSecond(issuedAt), Instant.ofEpochSecond(expiresAt));
    }

    @Override
    public String toString() {
        return "MfaTokenClaims[accountId=" + accountId + ", institutionId=" + institutionId
                + ", purpose=" + purpose + ", issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }
}

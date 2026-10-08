package com.confia.shared.web.harness;

import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.security.token.Base64Url;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.MfaPurpose;
import com.confia.shared.security.token.SigningKey;
import com.confia.shared.security.token.SigningKeyRing;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tokens of the bearer-filter tests: a key ring with a pair generated at run time (never a key in
 * the repository, CLAUDE.md regla 13), the verifier the harness chain uses, and the means to mint a
 * valid token, an expired one, a restricted one, one of the portal's key, one with an extra claim and
 * a signature the group order makes malleable. Every token is built with the production issuer or with
 * the production codec, so the filter is tested against what the process really issues.
 */
public final class HarnessTokens {

    /** The kid of the administrative key. */
    public static final String ADMIN_KID = "admin-2026a";
    /** The kid the portal would use; its key is not in the ring. */
    public static final String PORTAL_KID = "portal-2026a";

    public static final UUID ACCOUNT = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2");
    public static final UUID INSTITUTION = UUID.fromString("c3a8e9a0-5b1e-4f7e-9d0e-2b6f0a1c4d11");
    public static final UUID SESSION = UUID.fromString("0f6c1d2e-3a4b-4c5d-8e9f-a0b1c2d3e4f5");

    /** The order of the Ed25519 group: 2^252 + 27742317777372353535851937790883648493. */
    private static final BigInteger GROUP_ORDER = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final int SIGNATURE_BYTES = 64;
    private static final int SCALAR_BYTES = 32;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KeyPair admin = newPair();
    private final KeyPair portal = newPair();
    private final CompactJws jws = new CompactJws(
            SigningKeyRing.of(SigningKey.signing(ADMIN_KID, admin.getPublic(), admin.getPrivate())));
    private final KeyPair stranger = newPair();
    private final CompactJws strangerJws = new CompactJws(SigningKeyRing
            .of(SigningKey.signing(ADMIN_KID, stranger.getPublic(), stranger.getPrivate())));
    private final CompactJws portalJws = new CompactJws(SigningKeyRing
            .of(SigningKey.signing(PORTAL_KID, portal.getPublic(), portal.getPrivate())));

    /** The verifier the harness chain uses, on the real clock. */
    public AccessTokenVerifier verifier() {
        return new AccessTokenVerifier(jws, Clock.systemUTC());
    }

    /** A valid access token for {@link #ACCOUNT} in {@link #INSTITUTION}, issued now. */
    public String access() {
        return accessAt(Instant.now());
    }

    /** A valid access token for {@link #ACCOUNT} in {@code institution}, issued now. */
    public String accessForInstitution(UUID institution) {
        return new AccessTokenIssuer(jws, Clock.systemUTC())
                .issueAccess(ACCOUNT, institution, SESSION, Set.of(AuthenticationMethod.PASSWORD))
                .compact();
    }

    /** An access token issued at {@code issuedAt}: expired when that was more than ten minutes ago. */
    public String accessAt(Instant issuedAt) {
        return new AccessTokenIssuer(jws, Clock.fixed(issuedAt, java.time.ZoneOffset.UTC))
                .issueAccess(ACCOUNT, INSTITUTION, SESSION, Set.of(AuthenticationMethod.PASSWORD))
                .compact();
    }

    /** A restricted MFA token: valid, but of the other audience. */
    public String restricted() {
        return new AccessTokenIssuer(jws, Clock.systemUTC())
                .issueMfa(ACCOUNT, INSTITUTION, MfaPurpose.MFA_VERIFY).compact();
    }

    /** A well-formed access token under the administrative kid, signed by a key that is not the ring's. */
    public String signedByAStranger() {
        return strangerJws.sign(claims(Map.of()).getBytes(StandardCharsets.UTF_8));
    }

    /** An access token signed by the portal's key under the portal's kid. */
    public String signedByThePortal() {
        return portalJws.sign(claims(Map.of()).getBytes(StandardCharsets.UTF_8));
    }

    /** An access token with a valid signature and an extra {@code permissions} claim. */
    public String withPermissions() {
        return jws.sign(claims(Map.of("permissions", java.util.List.of("payments:write")))
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code token} with {@code S + L} in place of {@code S}: the same message and the same point,
     * a second encoding of the signature that a verifier without the range check accepts.
     */
    public static String malleable(String token) {
        String[] segments = token.split("[.]");
        byte[] signature = Base64Url.decode(segments[2]).orElseThrow();
        byte[] scalarLittleEndian = Arrays.copyOfRange(signature, SCALAR_BYTES, SIGNATURE_BYTES);
        byte[] scalar = reversed(scalarLittleEndian);
        byte[] shifted = new BigInteger(1, scalar).add(GROUP_ORDER).toByteArray();
        byte[] padded = new byte[SCALAR_BYTES];
        System.arraycopy(shifted, Math.max(0, shifted.length - SCALAR_BYTES),
                padded, Math.max(0, SCALAR_BYTES - shifted.length),
                Math.min(shifted.length, SCALAR_BYTES));
        byte[] forged = signature.clone();
        System.arraycopy(reversed(padded), 0, forged, SCALAR_BYTES, SCALAR_BYTES);
        return segments[0] + "." + segments[1] + "." + Base64Url.encode(forged);
    }

    /** {@code token} with its signature cut to 63 bytes. */
    public static String withShortSignature(String token) {
        String[] segments = token.split("[.]");
        byte[] signature = Base64Url.decode(segments[2]).orElseThrow();
        return segments[0] + "." + segments[1] + "."
                + Base64Url.encode(Arrays.copyOf(signature, SIGNATURE_BYTES - 1));
    }

    /** The claims of a valid access token issued now, with {@code extra} added. */
    private static String claims(Map<String, Object> extra) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "confia-admin");
        claims.put("aud", "confia-admin");
        claims.put("sub", ACCOUNT.toString());
        claims.put("exp", now + 600);
        claims.put("iat", now);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("sid", SESSION.toString());
        claims.put("tenant", INSTITUTION.toString());
        claims.put("amr", java.util.List.of("pwd"));
        claims.putAll(extra);
        return JSON.writeValueAsString(claims);
    }

    private static byte[] reversed(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            out[i] = bytes[bytes.length - 1 - i];
        }
        return out;
    }

    private static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

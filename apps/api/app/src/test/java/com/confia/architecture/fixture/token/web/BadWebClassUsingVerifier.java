package com.confia.architecture.fixture.token.web;

import com.confia.shared.security.token.AccessTokenIssuer;
import com.confia.shared.security.token.AccessTokenVerifier;
import com.confia.shared.security.token.CompactJws;
import com.confia.shared.security.token.SigningKeyRing;

/**
 * Deliberate violation fixture (session-tokens-and-web-layer design.md, decision 17; BI14): a class of
 * a {@code web} package that interprets tokens itself, with the verifier, the codec, the key ring and
 * the issuer, the four things {@code WebLayerTokenIsolationTest} forbids outside the authentication
 * filter of the edge and the chain configuration that builds it. A controller reads the actor from the
 * already authenticated principal and never from a token. Permanent, never removed: it is what proves
 * that rule rejects something (ADR-0018).
 */
public final class BadWebClassUsingVerifier {

    private final AccessTokenVerifier verifier;
    private final CompactJws codec;
    private final SigningKeyRing ring;
    private final AccessTokenIssuer issuer;

    public BadWebClassUsingVerifier(AccessTokenVerifier verifier, CompactJws codec, SigningKeyRing ring,
            AccessTokenIssuer issuer) {
        this.verifier = verifier;
        this.codec = codec;
        this.ring = ring;
        this.issuer = issuer;
    }

    public String accountOf(String authorizationHeader) {
        return verifier.verifyAccess(authorizationHeader).accountId().toString() + codec + ring + issuer;
    }
}

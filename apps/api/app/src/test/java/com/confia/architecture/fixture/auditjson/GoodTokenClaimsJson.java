package com.confia.shared.security.token;

/**
 * Deliberate non-violation fixture (BI43): the claims and header of a signed token are JSON that is
 * not an audit detail, so the package {@code shared.security.token} may keep assembling them. The
 * package is declared in the source, which is what the rule reads, not the folder of the file.
 * Permanent, never removed: it is what proves {@link com.confia.architecture.AuditJsonOnlySourceTest}
 * leaves the token package alone.
 */
public final class GoodTokenClaimsJson {

    public String header(String kid) {
        return "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}";
    }
}

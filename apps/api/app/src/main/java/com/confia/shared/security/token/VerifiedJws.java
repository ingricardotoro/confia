package com.confia.shared.security.token;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A token whose header, signature and payload encoding the codec has verified: the kid of the key
 * that signed it and its payload as a JSON object with unique member names. The claim rules (closed
 * list, types, audience, time) are applied by the caller, after this point (design.md, decision 2,
 * step 6).
 *
 * <p>The parsed tree is private and {@link #claims()} hands out a deep copy each time, so a caller
 * can neither change what another caller reads nor reach the object the codec built (follow-up S2 of
 * the codec review). The class prints its kid and nothing of the payload.
 */
public final class VerifiedJws {

    private final String kid;
    private final ObjectNode claims;

    /** Only the codec creates one; it owns {@code claims} and keeps no other reference to it. */
    VerifiedJws(String kid, ObjectNode claims) {
        this.kid = kid;
        this.claims = claims;
    }

    public String kid() {
        return kid;
    }

    /** A copy of the payload object: changing it changes nothing in this instance. */
    public JsonNode claims() {
        return claims.deepCopy();
    }

    @Override
    public String toString() {
        return "VerifiedJws[kid=" + kid + "]";
    }
}

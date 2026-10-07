package com.confia.shared.security.token;

import tools.jackson.databind.node.ObjectNode;

/**
 * A token whose header, signature and payload encoding the codec has verified: the kid of the key
 * that signed it and its payload as a JSON object with unique member names. The claim rules (closed
 * list, types, audience, time) are applied by the caller, after this point (design.md, decision 2,
 * step 6).
 */
public record VerifiedJws(String kid, ObjectNode claims) {
}

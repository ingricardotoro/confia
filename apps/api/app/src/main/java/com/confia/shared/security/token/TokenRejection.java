package com.confia.shared.security.token;

/**
 * Why a token was not accepted, in the order of the six verification steps of the codec
 * (session-tokens-and-web-layer design.md, decision 2). It carries no data on purpose: the caller
 * uses it to choose a response code and nothing else, and nothing of the token is ever logged.
 */
public enum TokenRejection {

    /** Step 1 or 3: length, characters, segments, or a signature segment that is not 64 bytes. */
    MALFORMED,
    /** Step 2: the header is not the canonical header of a kid in the ring. */
    UNKNOWN_HEADER,
    /** Step 4: the signature does not verify with the key of the kid. */
    BAD_SIGNATURE,
    /** Step 5: the payload is not a canonical base64url JSON object with unique member names. */
    MALFORMED_CLAIMS,
    /** Step 6: the claims break the closed list or the time rules (task 1.4). */
    CLAIMS_INVALID,
    /** Step 6: the claims are intact and signed, and {@code exp} has passed (task 1.4). */
    EXPIRED
}

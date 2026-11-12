package com.confia.shared.web.ratelimit;

/**
 * What a {@link RateLimited} policy counts requests by. {@link #ADDRESS} is the client address of the request,
 * as for every policy before the per-account limits. {@link #ACCOUNT} is the staff account of the verified
 * access token of the request: it is read from the authenticated principal and from nowhere else, never from a
 * header or a body, and a request without one is refused as lack of capacity ({@code 503}), never admitted.
 */
public enum RateLimitKey {

    ADDRESS,

    ACCOUNT
}

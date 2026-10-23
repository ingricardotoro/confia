package com.confia.shared.security;

/**
 * What a restricted second-factor token may be used for, as the web layer sees it: the purpose of the
 * token without the token's own types (session-endpoints-cookie-and-csrf design.md, decision 6). The
 * authentication filter is the only class that maps the claim of the token ({@code mfa-verify},
 * {@code mfa-enroll}) to this enumeration.
 */
public enum SecondFactorPurpose {

    /** The password was right and the account has a verified second factor to present. */
    VERIFY,

    /** The password was right and the account must enroll, or finish enrolling, its second factor. */
    ENROLL
}

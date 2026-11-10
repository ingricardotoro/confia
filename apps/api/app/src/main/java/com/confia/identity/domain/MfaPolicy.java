package com.confia.identity.domain;

/**
 * Whether a staff account owes a second factor, decided from nothing the account row can store
 * (rbac-permission-matrix-and-audit-integration design.md, D14; owner decisions O4 and Q1). The
 * policy receives the account and answers; it reads no role, no permission and no property, so
 * assigning or removing a role never turns the requirement on or off.
 *
 * <p>Production has one definition, {@link #STAFF}. A test supplies its own policy to reach the
 * outcome {@code Authenticated}, which no production account can obtain.
 */
@FunctionalInterface
public interface MfaPolicy {

    /** Whether {@code account} owes a second factor. */
    boolean requiresSecondFactor(StaffAccountId account);

    /** The policy of production: every staff account, with or without a role (owner decision Q1). */
    MfaPolicy STAFF = account -> true;
}

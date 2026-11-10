package com.confia.identity.web;

import com.confia.identity.domain.MfaPolicy;
import com.confia.identity.domain.StaffAccountId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The test policy of the second factor (rbac-permission-matrix-and-audit-integration design.md,
 * decision D14): the accounts a test declares exempt owe no second factor, every other account owes
 * one, as in production. It exists only in the test tree and reaches the real process through
 * {@link SecondFactorExemptionsConfiguration}. The exemptions are held by the instance, so each
 * process context has its own set and two processes of one JVM never share one: there is no
 * {@code static} field here, and the probe {@code SecondFactorExemptionsProbeIT} fails if one is
 * added. Public so the use-case tests of {@code identity.application} build their own instance.
 */
public final class SecondFactorExemptions implements MfaPolicy {

    private final Set<StaffAccountId> exempt = ConcurrentHashMap.newKeySet();

    /** A policy under which no account owes a second factor, for fixtures that stand for none. */
    public static MfaPolicy nobodyOwes() {
        return account -> false;
    }

    /** Declares that {@code account} owes no second factor in the policy that owns this set. */
    public void exempt(StaffAccountId account) {
        exempt.add(account);
    }

    /** The account owes the second factor again, as if the requirement began after a session did. */
    public void revokeExemption(StaffAccountId account) {
        exempt.remove(account);
    }

    @Override
    public boolean requiresSecondFactor(StaffAccountId account) {
        return !exempt.contains(account);
    }
}

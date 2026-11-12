package com.confia.guardians.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a member of the staff asks to record about the consent of a guardian: one combination of channel and purpose,
 * the action, the evidence and, for a grant, the version of the privacy policy the guardian accepted. Built only by
 * {@link #parse}, which owns the rules between fields: the version is required to grant and omitted to withdraw, and
 * it has the shape {@code ^[A-Za-z0-9._-]{1,32}$} (for example {@code 2026-01}). The time of the event is not here:
 * the server decides it.
 */
public record ConsentChange(ConsentChannel channel, ConsentPurpose purpose, ConsentAction action,
        EvidenceSource evidenceSource, String policyVersion) {

    /** The shape of a policy version; the database repeats it. */
    public static final Pattern POLICY_VERSION = Pattern.compile("^[A-Za-z0-9._-]{1,32}$");

    public ConsentChange {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
        if (action == ConsentAction.GRANT) {
            if (policyVersion == null || !POLICY_VERSION.matcher(policyVersion).matches()) {
                throw new InvalidConsentException("policyVersion", policyVersion == null
                        ? InvalidConsentException.REQUIRED : InvalidConsentException.INVALID);
            }
        } else if (policyVersion != null) {
            throw new InvalidConsentException("policyVersion", InvalidConsentException.NOT_ALLOWED);
        }
    }

    /**
     * The change a member of the staff asked for, from the raw members of the request. The first member that breaks a
     * rule is reported, in the order channel, purpose, action, evidence source and policy version.
     */
    public static ConsentChange parse(String channel, String purpose, String action, String evidenceSource,
            String policyVersion) {
        return new ConsentChange(ConsentChannel.parse(channel), ConsentPurpose.parse(purpose),
                ConsentAction.parse(action), EvidenceSource.parseForStaff(evidenceSource), policyVersion);
    }
}

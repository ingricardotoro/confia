package com.confia.probe.infrastructure;

/**
 * THROWAWAY PROBE — DO NOT KEEP.
 *
 * <p>Part of the deliberate violation used to prove, against the real remote, that continuous
 * integration turns red when an architecture rule is broken (build-integrity requirement 6,
 * scenario "empuje con violación"). This commit is reverted immediately after the red run is
 * observed and recorded in the verification report.
 */
public final class ProbeInfrastructure {

    private ProbeInfrastructure() {
    }

    public static String value() {
        return "probe";
    }
}

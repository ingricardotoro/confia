package com.confia.probe.domain;

import com.confia.probe.infrastructure.ProbeInfrastructure;

/**
 * THROWAWAY PROBE — DO NOT KEEP.
 *
 * <p>Deliberate layer violation: a {@code domain} class depending on {@code infrastructure},
 * which the layered architecture rule must reject. Used to prove that a push carrying an
 * architecture violation turns the remote build red (build-integrity requirement 6). Reverted
 * immediately after the red run is recorded.
 */
public final class ProbeDomain {

    private ProbeDomain() {
    }

    public static String describe() {
        return ProbeInfrastructure.value();
    }
}

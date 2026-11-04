package com.confia.architecture.fixture.domainpurity.kernel;

import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Deliberate violation fixture (build-integrity scenario BI132): a catalog type that depends on
 * something other than the JDK, which the rule on the permission catalog must reject. Permanent.
 */
public enum BadKernelPermission {
    ONE;

    public Class<?> framework() {
        return Component.class;
    }

    public Map<String, String> none() {
        return Map.of();
    }
}

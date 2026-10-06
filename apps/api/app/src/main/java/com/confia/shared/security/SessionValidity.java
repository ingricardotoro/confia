package com.confia.shared.security;

import java.util.UUID;

/**
 * Whether a session is still valid, declared here so that {@code shared} never depends on {@code
 * identity} (web-edge-foundations design.md, decision 14). A port with no implementation and no
 * bean in this change: {@code WebEdgeScopeExclusionInventoryTest} declares it a port without an
 * adapter. The implementation, by {@code identity}, belongs to {@code
 * session-tokens-and-web-layer} (slice C5b), which may adjust the exact shape because nothing
 * consumes it yet.
 */
public interface SessionValidity {

    /** Whether the session identified by {@code sessionId} is still valid at this instant. */
    boolean isActive(UUID sessionId);
}

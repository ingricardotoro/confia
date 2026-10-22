package com.confia.architecture.fixture.auditjson;

/**
 * Deliberate violation fixture (BI43): the detail JSON of an audit entry assembled by
 * concatenation. Permanent, never removed: it is what proves {@link
 * com.confia.architecture.AuditJsonOnlySourceTest} rejects a hand-built document.
 */
public final class BadConcatenatedAuditJson {

    public String afterValue(int attempts) {
        return "{\"consecutiveFailures\":" + attempts + "}";
    }
}

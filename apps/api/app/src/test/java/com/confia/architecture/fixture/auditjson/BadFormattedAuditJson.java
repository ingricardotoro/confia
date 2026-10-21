package com.confia.architecture.fixture.auditjson;

/**
 * Deliberate violation fixture (BI43): the detail JSON of an audit entry assembled with {@code
 * String.format}. Permanent, never removed.
 */
public final class BadFormattedAuditJson {

    public String afterValue(String reason) {
        return String.format("{\"reason\":\"%s\"}", reason);
    }
}

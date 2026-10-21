package com.confia.architecture.fixture.auditjson;

/**
 * Deliberate violation fixture (BI43): the detail JSON of an audit entry written as a text block.
 * Permanent, never removed.
 */
public final class BadTextBlockAuditJson {

    public String afterValue() {
        return """
                {
                  "outcome": "issuance-scheduled"
                }
                """;
    }
}

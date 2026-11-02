/**
 * Adapter layer of the {@code shared} module (ADR-0015 rule 4: jOOQ lives only in {@code
 * infrastructure}). {@link com.confia.shared.infrastructure.JooqAuditLogReader} is this module's
 * first adapter, implementing {@link com.confia.shared.audit.AuditLogReader} against the real
 * {@code shared_audit_log} table (design.md decision 11; F0 change 5, part B, PR B3b). {@link
 * com.confia.shared.infrastructure.JooqScheduledTaskQueueReader} reads the state of the job queue from {@code
 * scheduled_tasks} with one plain-SQL statement of the approved list (background-jobs-with-db-scheduler design.md
 * decision 20; ADR-0015 rule 9).
 */
package com.confia.shared.infrastructure;

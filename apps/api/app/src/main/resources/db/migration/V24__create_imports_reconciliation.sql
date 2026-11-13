-- Twenty-fourth migration (F1, deliverable 6; docs/cortes/imports-reconciliation.md). The fourth and last cut of the
-- migration tool: the record a signed reconciliation leaves of what the migration left in the system for an academic
-- year, next to the total the official roster of the institution declares.
--
-- imports_reconciliation holds ONLY counts, the reference the signer typed for the official roster (an office document
-- number or similar, never a name or a document of a person) and who signed when. The counts of what the import created
-- and left rejected are computed by the server from imports_batch when it signs; the client sends none. It is
-- append-only for the application role (INSERT and SELECT, no UPDATE and no DELETE): a correction is a new record, so
-- the history of what was signed is never rewritten.
--
-- The difference between the active enrollments and the official total is not stored: it is derived when shown.

CREATE TABLE imports_reconciliation (
    institution_id              UUID         NOT NULL,
    id                          UUID         NOT NULL,
    academic_year_id            UUID         NOT NULL,
    official_reference          VARCHAR(100) NOT NULL,
    official_enrollment_count   INTEGER      NOT NULL,
    enrollments_active          INTEGER      NOT NULL,
    students_created            INTEGER      NOT NULL,
    students_rejected           INTEGER      NOT NULL,
    guardians_created           INTEGER      NOT NULL,
    guardians_rejected          INTEGER      NOT NULL,
    guardian_links_created      INTEGER      NOT NULL,
    guardian_links_rejected     INTEGER      NOT NULL,
    enrollments_created         INTEGER      NOT NULL,
    enrollments_rejected        INTEGER      NOT NULL,
    signed_by                   UUID         NOT NULL,
    signed_at                   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT imports_reconciliation_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT imports_reconciliation_year_fk FOREIGN KEY (institution_id, academic_year_id)
        REFERENCES organization_academic_year (institution_id, id),
    CONSTRAINT imports_reconciliation_reference_chk CHECK (
        length(official_reference) > 0 AND official_reference = btrim(official_reference)
        AND official_reference !~ '[[:cntrl:]]'),
    CONSTRAINT imports_reconciliation_counts_chk CHECK (
        official_enrollment_count >= 0 AND enrollments_active >= 0
        AND students_created >= 0 AND students_rejected >= 0
        AND guardians_created >= 0 AND guardians_rejected >= 0
        AND guardian_links_created >= 0 AND guardian_links_rejected >= 0
        AND enrollments_created >= 0 AND enrollments_rejected >= 0)
);

-- The history, newest first.
CREATE INDEX imports_reconciliation_history_idx
    ON imports_reconciliation (institution_id, signed_at DESC, id DESC);

ALTER TABLE imports_reconciliation ENABLE ROW LEVEL SECURITY;
ALTER TABLE imports_reconciliation FORCE  ROW LEVEL SECURITY;

CREATE POLICY imports_reconciliation_institution_isolation ON imports_reconciliation
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON imports_reconciliation FROM PUBLIC;

-- INSERT and SELECT only: nothing is updated or deleted. The record holds no personal data, so the read-only role reads it.
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.
GRANT SELECT, INSERT ON imports_reconciliation TO confia_admin_app;
GRANT SELECT ON imports_reconciliation TO confia_readonly;

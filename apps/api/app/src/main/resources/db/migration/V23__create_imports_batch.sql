-- Twenty-third migration (F1, deliverable 6; docs/cortes/imports-students.md). Creates the record of the batches of
-- the bulk import of students and gives the student the reference it had in the system it comes from.
--
-- imports_batch holds ONLY counts and the SHA-256 of the file that was simulated or applied. It never holds the file,
-- a row of it, a value, a name, a document or a rejected value: the file is personal data of minors in bulk and the
-- only copy of it that stays is the one in students_student. The rows rejected by a run are returned to the person who
-- ran it and are not stored. The table is append-only for the application role (INSERT and SELECT, no UPDATE and no
-- DELETE): a simulation is a VALIDATED row and an application is a new APPLIED row that points to the simulation it
-- came from, so the history of what was run is never rewritten.
--
-- Counts: every data row of the file is exactly one of valid (it is created, or it would be), rejected (a defect) or
-- skipped (already imported), so total_rows = valid_rows + rejected_rows + skipped_rows. A simulation creates nothing
-- (created_rows = 0); an application has created_rows = valid_rows.
--
-- students_student.external_ref is the identifier the student had in the source system (the legacyId column of the
-- file). It is what makes an import idempotent: the unique index (institution_id, external_ref) is the last word when
-- two applications of the same file run at once. It is not sensitive to the point of being encrypted, but the read-only
-- role does not get it (the column-level grant of V17 is not widened).

CREATE TABLE imports_batch (
    institution_id UUID        NOT NULL,
    id             UUID        NOT NULL,
    kind           TEXT        NOT NULL,
    status         TEXT        NOT NULL,
    file_sha256    VARCHAR(64) NOT NULL,
    total_rows     INTEGER     NOT NULL,
    valid_rows     INTEGER     NOT NULL,
    rejected_rows  INTEGER     NOT NULL,
    created_rows   INTEGER     NOT NULL,
    skipped_rows   INTEGER     NOT NULL,
    simulation_id  UUID,
    created_by     UUID        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    applied_at     TIMESTAMPTZ,
    CONSTRAINT imports_batch_pk PRIMARY KEY (institution_id, id),
    CONSTRAINT imports_batch_simulation_fk FOREIGN KEY (institution_id, simulation_id)
        REFERENCES imports_batch (institution_id, id),
    CONSTRAINT imports_batch_kind_chk   CHECK (kind IN ('STUDENTS')),
    CONSTRAINT imports_batch_status_chk CHECK (status IN ('VALIDATED', 'APPLIED')),
    CONSTRAINT imports_batch_sha_chk    CHECK (file_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT imports_batch_counts_chk CHECK (
        total_rows >= 0 AND total_rows <= 10000 AND valid_rows >= 0 AND rejected_rows >= 0
        AND created_rows >= 0 AND skipped_rows >= 0
        AND total_rows = valid_rows + rejected_rows + skipped_rows),
    -- A simulation creates nothing and is not applied; an application has created what it says was valid.
    CONSTRAINT imports_batch_status_shape_chk CHECK (
        (status = 'VALIDATED' AND created_rows = 0 AND applied_at IS NULL AND simulation_id IS NULL)
        OR (status = 'APPLIED' AND created_rows = valid_rows AND applied_at IS NOT NULL))
);

-- The history, newest first, and the lookup of a simulation of this account by the fingerprint of the file.
CREATE INDEX imports_batch_history_idx ON imports_batch (institution_id, created_at DESC, id DESC);
CREATE INDEX imports_batch_actor_idx ON imports_batch (institution_id, created_by, created_at DESC, id DESC);
CREATE INDEX imports_batch_validated_idx ON imports_batch (institution_id, created_by, file_sha256)
    WHERE status = 'VALIDATED';

ALTER TABLE imports_batch ENABLE ROW LEVEL SECURITY;
ALTER TABLE imports_batch FORCE  ROW LEVEL SECURITY;

CREATE POLICY imports_batch_institution_isolation ON imports_batch
    USING      (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid)
    WITH CHECK (institution_id = NULLIF(current_setting('app.institution_id', true), '')::uuid);

REVOKE ALL ON imports_batch FROM PUBLIC;

-- INSERT and SELECT only: nothing is updated or deleted. The batch holds no personal data, so the read-only role reads it.
-- confia_portal_app: no grant, staff data. confia_owner: schema owner, without BYPASSRLS.
GRANT SELECT, INSERT ON imports_batch TO confia_admin_app;
GRANT SELECT ON imports_batch TO confia_readonly;

-- The reference of the student in the system it was imported from. NULL for a student registered by hand. The unique
-- index treats NULLs as distinct, so any number of students without a reference coexist.
ALTER TABLE students_student ADD COLUMN external_ref VARCHAR(64);
ALTER TABLE students_student ADD CONSTRAINT students_student_external_ref_chk CHECK (
    external_ref IS NULL OR (length(external_ref) > 0 AND external_ref = btrim(external_ref)));
CREATE UNIQUE INDEX students_student_external_ref_uq ON students_student (institution_id, external_ref);
